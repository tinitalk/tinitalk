package org.tinitalk.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoDraft
import org.tinitalk.data.ContactPhotoFailure
import org.tinitalk.data.ContactPhotoProcessor
import org.tinitalk.data.ContactPhotoResult
import org.tinitalk.data.ContactPhotoStore
import org.tinitalk.data.ContactPhotoWriteToken
import org.tinitalk.data.NormalizedCropSquare

data class ContactPhotoEditTarget(
    val accountId: AccountId,
    val address: ContactAddress,
    val displayName: String,
)

enum class ContactPhotoSource { Gallery, Files }

enum class ContactPhotoEditorPhase {
    Idle,
    Picking,
    Preparing,
    Cropping,
    Saving,
    Removing,
}

data class ContactPhotoEditorState(
    val phase: ContactPhotoEditorPhase = ContactPhotoEditorPhase.Idle,
    val target: ContactPhotoEditTarget? = null,
    val source: ContactPhotoSource? = null,
    val draft: ContactPhotoDraft? = null,
    val hasPhoto: Boolean = false,
    val message: String? = null,
) {
    val busy: Boolean
        get() = phase == ContactPhotoEditorPhase.Preparing ||
            phase == ContactPhotoEditorPhase.Saving ||
            phase == ContactPhotoEditorPhase.Removing
}

fun interface ContactPhotoWorker {
    fun execute(block: () -> Unit)
}

fun interface ContactPhotoMainPoster {
    fun post(block: () -> Unit)
}

internal data class ContactPhotoEditorDependencies(
    val beginReplace: (ContactAddress) -> ContactPhotoWriteToken,
    val hasPhoto: (ContactAddress) -> Boolean,
    val importDraft: (Uri) -> ContactPhotoResult<ContactPhotoDraft>,
    val render: (ContactPhotoDraft, NormalizedCropSquare) -> ContactPhotoResult<Bitmap>,
    val replace: (ContactPhotoWriteToken, Bitmap) -> Result<*>,
    val remove: (ContactAddress) -> Result<Boolean>,
    val discard: (ContactPhotoDraft) -> Boolean,
    val isTargetCurrent: (ContactPhotoEditTarget) -> Boolean,
)

class ContactPhotoEditorViewModel(
    private val worker: ContactPhotoWorker = ContactPhotoWorker { block ->
        Thread(block, "contact-photo-editor").start()
    },
    private val mainPoster: ContactPhotoMainPoster = ContactPhotoMainPoster { block ->
        Handler(Looper.getMainLooper()).post(block)
    },
) : ViewModel() {
    var state by mutableStateOf(ContactPhotoEditorState())
        private set

    private var dependencies: ContactPhotoEditorDependencies? = null
    private var operationId = 0
    private var pendingToken: ContactPhotoWriteToken? = null
    private var previousHasPhoto = false

    fun configure(
        processor: ContactPhotoProcessor,
        store: ContactPhotoStore,
        isTargetCurrent: (ContactPhotoEditTarget) -> Boolean,
    ) {
        dependencies = ContactPhotoEditorDependencies(
            beginReplace = store::beginReplace,
            hasPhoto = { address -> store.photo(address) != null },
            importDraft = processor::importDraft,
            render = processor::render,
            replace = store::replace,
            remove = store::remove,
            discard = processor::discard,
            isTargetCurrent = isTargetCurrent,
        )
    }

    internal fun configureForTest(dependencies: ContactPhotoEditorDependencies) {
        this.dependencies = dependencies
    }

    fun onTargetVisible(target: ContactPhotoEditTarget) {
        val deps = dependencies ?: return
        val currentOperation = ++operationId
        val oldState = state
        state = ContactPhotoEditorState(
            target = target,
            hasPhoto = if (oldState.target == target) oldState.hasPhoto else false,
            message = oldState.message,
        )
        worker.execute {
            val exists = deps.hasPhoto(target.address)
            mainPoster.post {
                if (currentOperation != operationId) return@post
                previousHasPhoto = exists
                state = state.copy(target = target, hasPhoto = exists)
            }
        }
    }

    fun beginPicking(target: ContactPhotoEditTarget, source: ContactPhotoSource): Boolean {
        val deps = dependencies ?: return false
        if (state.busy || state.phase == ContactPhotoEditorPhase.Picking) return false
        if (!deps.isTargetCurrent(target)) return false
        recycleAndDiscardDraft(state.draft)
        pendingToken = deps.beginReplace(target.address)
        previousHasPhoto = state.hasPhoto
        state = ContactPhotoEditorState(
            phase = ContactPhotoEditorPhase.Picking,
            target = target,
            source = source,
            hasPhoto = state.hasPhoto,
        )
        operationId++
        return true
    }

    fun onPickerResult(uri: Uri?) {
        val deps = dependencies ?: return
        val token = pendingToken
        val target = state.target
        val currentOperation = operationId
        if (state.phase != ContactPhotoEditorPhase.Picking || token == null || target == null) return
        if (uri == null) {
            pendingToken = null
            state = ContactPhotoEditorState(target = target, hasPhoto = previousHasPhoto)
            return
        }
        state = state.copy(phase = ContactPhotoEditorPhase.Preparing)
        worker.execute {
            if (!deps.isTargetCurrent(target)) {
                postIfCurrent(currentOperation) {
                    pendingToken = null
                    state = ContactPhotoEditorState()
                }
                return@execute
            }
            when (val imported = deps.importDraft(uri)) {
                is ContactPhotoResult.Success -> {
                    postIfCurrent(currentOperation) {
                        if (!deps.isTargetCurrent(target)) {
                            deps.discard(imported.value)
                            pendingToken = null
                            state = ContactPhotoEditorState()
                            return@postIfCurrent
                        }
                        state = state.copy(
                            phase = ContactPhotoEditorPhase.Cropping,
                            target = target,
                            draft = imported.value,
                        )
                    }
                }
                is ContactPhotoResult.Failure -> {
                    postIfCurrent(currentOperation) {
                        pendingToken = null
                        state = ContactPhotoEditorState(
                            target = target,
                            hasPhoto = previousHasPhoto,
                            message = contactPhotoMessage(imported.reason),
                        )
                    }
                }
            }
        }
    }

    fun save(crop: NormalizedCropSquare): Boolean {
        val deps = dependencies ?: return false
        val target = state.target ?: return false
        val draft = state.draft ?: return false
        val token = pendingToken ?: return false
        if (state.phase != ContactPhotoEditorPhase.Cropping) return false
        val currentOperation = ++operationId
        state = state.copy(phase = ContactPhotoEditorPhase.Saving)
        worker.execute {
            if (!deps.isTargetCurrent(target)) {
                deps.discard(draft)
                postIfCurrent(currentOperation) {
                    pendingToken = null
                    state = ContactPhotoEditorState()
                }
                return@execute
            }
            when (val rendered = deps.render(draft, crop)) {
                is ContactPhotoResult.Success -> {
                    if (!deps.isTargetCurrent(target)) {
                        rendered.value.recycle()
                        deps.discard(draft)
                        postIfCurrent(currentOperation) {
                            pendingToken = null
                            state = ContactPhotoEditorState()
                        }
                        return@execute
                    }
                    val replaced = deps.replace(token, rendered.value)
                    rendered.value.recycle()
                    deps.discard(draft)
                    postIfCurrent(currentOperation) {
                        pendingToken = null
                        state = ContactPhotoEditorState(
                            target = target,
                            hasPhoto = replaced.isSuccess,
                            message = if (replaced.isSuccess) null else contactPhotoMessage(ContactPhotoFailure.CannotSave),
                        )
                    }
                }
                is ContactPhotoResult.Failure -> {
                    deps.discard(draft)
                    postIfCurrent(currentOperation) {
                        pendingToken = null
                        state = ContactPhotoEditorState(
                            target = target,
                            hasPhoto = previousHasPhoto,
                            message = contactPhotoMessage(rendered.reason),
                        )
                    }
                }
            }
        }
        return true
    }

    fun cancelCrop() {
        if (state.phase != ContactPhotoEditorPhase.Cropping && state.phase != ContactPhotoEditorPhase.Saving) return
        operationId++
        recycleAndDiscardDraft(state.draft)
        pendingToken = null
        state = ContactPhotoEditorState(target = state.target, hasPhoto = previousHasPhoto)
    }

    fun remove(target: ContactPhotoEditTarget): Boolean {
        val deps = dependencies ?: return false
        if (state.busy || !deps.isTargetCurrent(target)) return false
        val currentOperation = ++operationId
        recycleAndDiscardDraft(state.draft)
        pendingToken = null
        state = ContactPhotoEditorState(
            phase = ContactPhotoEditorPhase.Removing,
            target = target,
            hasPhoto = state.hasPhoto,
        )
        worker.execute {
            val result = if (deps.isTargetCurrent(target)) deps.remove(target.address) else Result.failure(IllegalStateException())
            postIfCurrent(currentOperation) {
                state = ContactPhotoEditorState(
                    target = target,
                    hasPhoto = if (result.isSuccess) false else previousHasPhoto,
                    message = if (result.isSuccess) null else contactPhotoMessage(ContactPhotoFailure.CannotSave),
                )
            }
        }
        return true
    }

    fun onTargetHidden(target: ContactPhotoEditTarget) {
        if (state.target != target) return
        operationId++
        recycleAndDiscardDraft(state.draft)
        pendingToken = null
        state = ContactPhotoEditorState()
    }

    fun onMessageShown() {
        if (state.message != null) state = state.copy(message = null)
    }

    private fun postIfCurrent(currentOperation: Int, block: () -> Unit) {
        mainPoster.post {
            if (currentOperation != operationId) return@post
            block()
        }
    }

    private fun recycleAndDiscardDraft(draft: ContactPhotoDraft?) {
        val deps = dependencies ?: return
        if (draft == null) return
        draft.preview.recycle()
        deps.discard(draft)
    }
}

internal fun contactPhotoMessage(reason: ContactPhotoFailure): String = when (reason) {
    ContactPhotoFailure.CannotOpen -> "Не удалось открыть изображение"
    ContactPhotoFailure.TooLarge -> "Изображение слишком большое"
    ContactPhotoFailure.NoSpace -> "Недостаточно места для сохранения фото"
    ContactPhotoFailure.CannotSave -> "Не удалось сохранить фото"
}
