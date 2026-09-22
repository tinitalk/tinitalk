package org.tinitalk.ui

import org.tinitalk.R
import org.tinitalk.i18n.appString

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.data.*
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru-w360dp-h800dp")
class FavoriteContactsTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tabsAreCompactAndStillSwitchSelection() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var favorites by mutableStateOf(true)
        activity.get().setContent {
            TiniTalkTheme(darkTheme = true) {
                FavoriteContactTabs(favorites) { favorites = it }
            }
        }
        composeRule.onNodeWithText(appString(R.string.text_favorites_247)).assertHeightIsEqualTo(36.dp).assertIsSelected()
        composeRule.onNodeWithText(appString(R.string.text_all_248)).performClick().assertIsSelected()
        composeRule.onNodeWithText(appString(R.string.text_favorites_247)).assertIsNotSelected()
        activity.pause().stop().destroy()
    }

    @Test
    fun orderPersistsAndUnavailableAccountsAreNotErased() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FavoriteContactsStore(context)
        val anna = AccountPeerKey(AccountId("one"), "anna")
        val otherAnna = AccountPeerKey(AccountId("two"), "anna")
        val alex = AccountPeerKey(AccountId("one"), "alex")
        store.setFavorite(anna, true)
        store.setFavorite(otherAnna, true)
        store.setFavorite(alex, true)
        store.setFavorite(anna, true)
        store.reorder(listOf(alex, anna))
        assertEquals(listOf(alex, otherAnna, anna), FavoriteContactsStore(context).load())
        store.setFavorite(anna, false)
        store.setFavorite(anna, true, 0)
        assertEquals(listOf(anna, alex, otherAnna), store.load())
        store.removeAccount(AccountId("one"))
        assertEquals(listOf(otherAnna), store.load())
    }

    @Test
    fun draggingReordersOnlyOnReleaseAndCancellationRestoresOrder() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var contacts by mutableStateOf(List(4) {
            AccountContact(AccountId("one"), "https://example.com", Contact("person-$it", "Человек $it"))
        })
        var saves = 0
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ReorderableFavoriteContacts(contacts, rememberLazyListState(), onReorder = { order ->
                        saves++
                        contacts = order.map { key -> contacts.first { it.peerKey == key } }
                    }) { contact, modifier ->
                        Surface(onClick = {}, modifier = modifier.fillMaxWidth().height(82.dp).testTag(contact.login)) {
                            Text(contact.displayName)
                        }
                    }
                }
            }
        }
        val list = composeRule.onRoot()
        val first = composeRule.onNodeWithTag("person-0").fetchSemanticsNode().boundsInRoot
        val third = composeRule.onNodeWithTag("person-2").fetchSemanticsNode().boundsInRoot
        val listTop = list.fetchSemanticsNode().boundsInRoot.top
        list.performTouchInput {
            down(Offset(first.center.x, first.center.y - listTop))
        }
        composeRule.mainClock.advanceTimeBy(650)
        list.performTouchInput {
            advanceEventTime(650)
            moveTo(Offset(third.center.x, third.center.y - listTop), 300)
        }
        composeRule.runOnIdle { assertEquals(0, saves) }
        list.performTouchInput { up() }
        composeRule.runOnIdle {
            assertEquals(1, saves)
            assertEquals(listOf("person-1", "person-2", "person-0", "person-3"), contacts.map { it.login })
        }
        list.performTouchInput {
            down(Offset(first.center.x, first.center.y - listTop))
        }
        composeRule.mainClock.advanceTimeBy(650)
        list.performTouchInput {
            advanceEventTime(650)
            moveTo(Offset(third.center.x, third.center.y - listTop), 300)
            cancel()
        }
        composeRule.runOnIdle { assertEquals(1, saves) }
        val restoredFirst = composeRule.onNodeWithTag("person-1").fetchSemanticsNode().boundsInRoot
        val restoredThird = composeRule.onNodeWithTag("person-0").fetchSemanticsNode().boundsInRoot
        assertTrue("Cancelled drag: first=$restoredFirst third=$restoredThird\n${list.printToString()}",
            restoredFirst.top < restoredThird.top)

        composeRule.runOnIdle {
            contacts = List(20) {
                AccountContact(AccountId("one"), "https://example.com", Contact("person-$it", "Человек $it"))
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollAction()).performScrollToIndex(0)
        val start = composeRule.onNodeWithTag("person-0").fetchSemanticsNode().boundsInRoot.center
        composeRule.mainClock.autoAdvance = false
        list.performTouchInput { down(Offset(start.x, start.y - listTop)) }
        composeRule.mainClock.advanceTimeBy(650)
        list.performTouchInput {
            advanceEventTime(650)
            moveTo(Offset(first.center.x, height - 20f), 300)
        }
        composeRule.mainClock.advanceTimeBy(700)
        list.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle {
            assertEquals(2, saves)
            assertTrue("Dragging near the bottom should scroll beyond the initial viewport: ${contacts.map { it.login }}",
                contacts.indexOfFirst { it.login == "person-0" } > 8)
        }
        activity.pause().stop().destroy()
    }
}
