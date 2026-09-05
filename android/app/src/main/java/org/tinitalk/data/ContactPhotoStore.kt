package org.tinitalk.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ContactPhotoReader {
    val revision: StateFlow<Long>
    fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap?
    fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap?
}

data class StoredContactPhoto(
    val file: File,
    val revision: Long,
)

class ContactPhotoWriteToken internal constructor(
    val address: ContactAddress,
    internal val serverGeneration: Long,
    internal val addressGeneration: Long = 0L,
)

fun interface ContactPhotoEncoder {
    fun encode(bitmap: Bitmap, output: OutputStream): Boolean
}

object WebpContactPhotoEncoder : ContactPhotoEncoder {
    override fun encode(bitmap: Bitmap, output: OutputStream): Boolean {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        return bitmap.compress(format, 85, output)
    }
}

class ContactPhotoStore(
    private val root: File,
    private val bitmapCache: LruCache<String, Bitmap>,
    private val encoder: ContactPhotoEncoder = WebpContactPhotoEncoder,
    private val decoder: ((File, Int) -> Bitmap?)? = null,
) : ContactPhotoReader {
    private val lock = Any()
    private val versionRoot = File(root, "v1")
    private val trashRoot = File(versionRoot, ".trash")
    private val revisions = MutableStateFlow(0L)
    private val serverGenerations = mutableMapOf<String, Long>()
    private val addressGenerations = mutableMapOf<String, Long>()
    private val cacheIndex = mutableMapOf<String, String>()

    override val revision: StateFlow<Long> = revisions

    fun beginReplace(address: ContactAddress): ContactPhotoWriteToken = synchronized(lock) {
        ContactPhotoWriteToken(
            address,
            serverGenerations[address.serverUrl] ?: 0L,
            addressGenerations[addressKey(address)] ?: 0L,
        )
    }

    fun photo(address: ContactAddress): StoredContactPhoto? = synchronized(lock) {
        val file = fileFor(address)
        if (file.isFile) StoredContactPhoto(file, revisions.value) else null
    }

    override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = synchronized(lock) {
        val bucket = bucketFor(targetPixels)
        val key = cacheIndex[cacheIndexKey(address, bucket)] ?: return null
        bitmapCache.get(key)
    }

    override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
        val bucket = bucketFor(targetPixels)
        val indexKey = cacheIndexKey(address, bucket)
        while (true) {
            val (file, expectedCacheKey) = synchronized(lock) {
                val currentFile = fileFor(address).takeIf(File::isFile) ?: return null
                val currentCacheKey = cacheKey(address, currentFile, bucket)
                val indexedKey = cacheIndex[indexKey]
                if (indexedKey != null && indexedKey != currentCacheKey) {
                    bitmapCache.remove(indexedKey)
                    cacheIndex.remove(indexKey)
                }
                bitmapCache.get(currentCacheKey)?.let { bitmap ->
                    cacheIndex[indexKey] = currentCacheKey
                    return bitmap
                }
                currentFile to currentCacheKey
            }

            val customDecoder = decoder
            val decoded = if (customDecoder == null) {
                decodeBitmap(file, bucket)
            } else {
                customDecoder(file, bucket)
            }

            var retry = false
            val stableBitmap = synchronized(lock) {
                val currentFile = fileFor(address).takeIf(File::isFile)
                val currentCacheKey = currentFile?.let { cacheKey(address, it, bucket) }
                if (currentCacheKey != expectedCacheKey) {
                    retry = true
                    null
                } else if (decoded == null) {
                    cacheIndex.remove(indexKey)?.let(bitmapCache::remove)
                    null
                } else {
                    bitmapCache.put(expectedCacheKey, decoded)
                    cacheIndex[indexKey] = expectedCacheKey
                    decoded
                }
            }
            if (!retry) return stableBitmap
            decoded?.recycle()
        }
    }

    fun replace(token: ContactPhotoWriteToken, square: Bitmap): Result<StoredContactPhoto> = synchronized(lock) {
        runCatching {
            val currentGeneration = serverGenerations[token.address.serverUrl] ?: 0L
            check(currentGeneration == token.serverGeneration) { "stale contact photo write token" }
            val currentAddressGeneration = addressGenerations[addressKey(token.address)] ?: 0L
            check(currentAddressGeneration == token.addressGeneration) { "stale contact photo write token" }
            val target = fileFor(token.address)
            ensureInsideRoot(target)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, ".tmp-${UUID.randomUUID()}")
            try {
                FileOutputStream(temp).use { output ->
                    if (!encoder.encode(square, output)) {
                        throw IllegalStateException("contact photo encode failed")
                    }
                    output.fd.sync()
                }
                moveReplacing(temp, target)
                invalidateAddress(token.address)
                StoredContactPhoto(target, publishRevision())
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    fun remove(address: ContactAddress): Result<Boolean> = synchronized(lock) {
        runCatching {
            val file = fileFor(address)
            invalidateAddress(address)
            if (!file.exists()) return@runCatching false
            val deleted = file.delete()
            if (deleted) {
                publishRevision()
                deleteEmptyParents(file.parentFile)
            }
            deleted
        }
    }

    fun removeServer(serverUrl: String): Result<Boolean> = synchronized(lock) {
        runCatching {
            val normalized = normalizeServerUrl(serverUrl)
            bumpServerGeneration(normalized)
            invalidateServer(normalized)
            val serverDir = serverDir(normalized)
            if (!serverDir.exists()) return@runCatching false
            trashRoot.mkdirs()
            val trashTarget = File(trashRoot, "${sha256(normalized)}-${UUID.randomUUID()}")
            moveReplacing(serverDir, trashTarget)
            publishRevision()
            true
        }
    }

    fun purgeTrash(): Result<Unit> = synchronized(lock) {
        runCatching {
            if (trashRoot.exists()) {
                trashRoot.deleteRecursively()
            }
            trashRoot.mkdirs()
            Unit
        }
    }

    private fun fileFor(address: ContactAddress): File =
        File(serverDir(address.serverUrl), "${sha256(address.login)}.webp").also(::ensureInsideRoot)

    private fun serverDir(serverUrl: String): File =
        File(versionRoot, sha256(serverUrl)).also(::ensureInsideRoot)

    private fun ensureInsideRoot(file: File) {
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        check(filePath.startsWith(rootPath)) { "contact photo path escapes root" }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private fun publishRevision(): Long {
        val next = revisions.value + 1L
        revisions.value = next
        return next
    }

    private fun bumpServerGeneration(serverUrl: String) {
        serverGenerations[serverUrl] = (serverGenerations[serverUrl] ?: 0L) + 1L
    }

    private fun invalidateAddress(address: ContactAddress) {
        addressGenerations[addressKey(address)] = (addressGenerations[addressKey(address)] ?: 0L) + 1L
        val prefix = "${addressKey(address)}|"
        val removedKeys = cacheIndex.filterKeys { it.startsWith(prefix) }.values.toSet()
        cacheIndex.keys.removeAll { it.startsWith(prefix) }
        removedKeys.forEach(bitmapCache::remove)
    }

    private fun invalidateServer(serverUrl: String) {
        val prefix = "$serverUrl\u0000"
        val removedKeys = cacheIndex.filterKeys { it.startsWith(prefix) }.values.toSet()
        cacheIndex.keys.removeAll { it.startsWith(prefix) }
        removedKeys.forEach(bitmapCache::remove)
        addressGenerations.keys
            .filter { it.startsWith(prefix) }
            .forEach { key -> addressGenerations[key] = (addressGenerations[key] ?: 0L) + 1L }
    }

    private fun cacheKey(address: ContactAddress, file: File, bucket: Int): String =
        listOf(
            addressKey(address),
            addressGenerations[addressKey(address)] ?: 0L,
            file.lastModified(),
            file.length(),
            bucket,
        ).joinToString("|")

    private fun cacheIndexKey(address: ContactAddress, bucket: Int): String =
        "${addressKey(address)}|$bucket"

    private fun addressKey(address: ContactAddress): String =
        "${address.serverUrl}\u0000${address.login}"

    private fun bucketFor(targetPixels: Int): Int = targetPixels.coerceAtLeast(1)

    private fun decodeBitmap(file: File, targetPixels: Int): Bitmap? {
        if (!hasSupportedImageHeader(file)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPixels)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun hasSupportedImageHeader(file: File): Boolean {
        val header = ByteArray(12)
        val read = file.inputStream().use { input -> input.read(header) }
        if (read < 4) return false
        val png = header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4e.toByte() &&
            header[3] == 0x47.toByte()
        val jpeg = header[0] == 0xff.toByte() && header[1] == 0xd8.toByte()
        val webp = read >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        return png || jpeg || webp
    }

    private fun sampleSize(width: Int, height: Int, targetPixels: Int): Int {
        var sample = 1
        val target = targetPixels.coerceAtLeast(1)
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    private fun deleteEmptyParents(start: File?) {
        var current = start
        while (current != null && current != versionRoot && current.list()?.isEmpty() == true) {
            val parent = current.parentFile
            current.delete()
            current = parent
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
