package org.tinitalk.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import org.tinitalk.data.AccountHistory
import org.tinitalk.data.AccountId
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.CallHistoryPage
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.HouseholdApi
import org.tinitalk.data.AuthStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Profile
import org.tinitalk.data.ServerInfo
import org.tinitalk.data.Session
import org.tinitalk.ui.theme.TiniTalkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryContactPhotoTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun repositoryBindsHistoryItemsToNormalizedServerUrl() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val session = Session("https://Talk.Example.com/", "owner", "token")
        auth.save(session)
        val accountId = auth.list().single().id
        val repository = ContactRepository(
            auth,
            apiFactory = { _, _, _, _ ->
                HistoryApi(CallHistoryPage(listOf(history(7, "alex")), 0, 7, 0))
            },
        )

        val page = requireNotNull(repository.loadCallHistory(accountId))

        assertEquals("https://talk.example.com", page.items.single().serverUrl)
        assertEquals(ContactAddress.of("https://talk.example.com", "alex"), page.items.single().address)
    }

    @Test
    fun sameLoginOnDifferentServersUsesDifferentPhotoAddresses() {
        val first = AccountHistory(AccountId("first"), "https://one.example", history(1, "alex", startedAt = 20))
        val second = AccountHistory(AccountId("second"), "https://two.example", history(2, "alex", startedAt = 10))

        assertNotEquals(first.address, second.address)
        assertEquals(accountScopedKey(first.accountId, "1"), accountScopedKey(first.accountId, first.id.toString()))
        assertNotEquals(accountScopedKey(first.accountId, "1"), accountScopedKey(second.accountId, "1"))
    }

    @Test
    fun sameNormalizedServerAndExactLoginSharePhotoAddressAcrossAccounts() {
        val first = AccountHistory(AccountId("first"), "https://one.example/", history(1, "alex"))
        val second = AccountHistory(AccountId("second"), "https://one.example", history(2, "alex"))

        assertEquals(first.address, second.address)
    }

    @Test
    fun historyRowsRequestPhotoForEachAccountBoundRow() {
        val reader = RecordingReader()
        val items = listOf(
            AccountHistory(AccountId("first"), "https://one.example", history(1, "alex", startedAt = 20)),
            AccountHistory(AccountId("second"), "https://two.example", history(2, "alex", startedAt = 10)),
        )

        render(reader) {
            Column {
                items.forEach { item ->
                    HistoryRow(item.item, contactAddress = item.address)
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { reader.requested.toSet().size == 2 }
        assertEquals(
            setOf(
                ContactAddress.of("https://one.example", "alex"),
                ContactAddress.of("https://two.example", "alex"),
            ),
            reader.requested.toSet(),
        )
        assertEquals(2, composeRule.onAllNodesWithTag("contact-avatar-photo").fetchSemanticsNodes().size)
    }

    @Test
    fun contactHistoryRowWithHiddenPeerDoesNotDrawSecondAvatar() {
        render(RecordingReader()) {
            HistoryRow(history(1, "alex"), showPeer = false, contactAddress = ContactAddress.of("https://one.example", "alex"))
        }

        composeRule.onNode(hasText("А")).assertDoesNotExist()
        assertEquals(0, composeRule.onAllNodesWithTag("contact-avatar-photo").fetchSemanticsNodes().size)
    }

    private fun render(reader: ContactPhotoReader, content: @androidx.compose.runtime.Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalContactPhotoReader provides reader) {
                        content()
                    }
                }
            }
        }
    }

    private class RecordingReader : ContactPhotoReader {
        private val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val requested = mutableListOf<ContactAddress>()
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
            requested += address
            return bitmap
        }
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
            requested += address
            return bitmap
        }
    }

    private class HistoryApi(private val page: CallHistoryPage) : HouseholdApi {
        override fun serverInfo() = ServerInfo("tinitalk", "ok", 4)
        override fun me() = Profile("owner", "Owner")
        override fun contactsPage(limit: Int, cursor: String) = error("not used")
        override fun updateContactName(login: String, customName: String) = error("not used")
        override fun calls(limit: Int, before: Long, peerLogin: String?) = page
        override fun markCallsRead(throughId: Long, peerLogin: String?) = CallUnreadState(0, emptyList())
    }

    private fun history(id: Long, login: String, startedAt: Long = 1): CallHistoryItem =
        CallHistoryItem(id, login, "Алексей", "incoming", "completed", true, startedAt, 0)
}
