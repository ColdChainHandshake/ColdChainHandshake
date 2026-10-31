package com.coldchain.handshake.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.ui.screens.AccountUnreachableScreen
import com.coldchain.handshake.ui.screens.ProfileSetupScreen
import com.coldchain.handshake.ui.screens.SignInScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1200dp")
class SignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val account = FakeAccountRepository()

    @Test
    fun aCodeIsSentThenVerified() {
        compose.setContent { SignInScreen(account) }

        compose.onNodeWithText("Send Code").assertIsNotEnabled()
        compose.onNodeWithText("Email").performTextInput("ana@example.com")
        compose.onNodeWithText("Send Code").performClick()

        compose.onNodeWithText("Verify").assertIsNotEnabled()
        compose.onNodeWithText("Code").performTextInput("123456")
        compose.onNodeWithText("Verify").performClick()
        compose.waitForIdle()

        assertEquals(listOf("ana@example.com"), account.codesRequestedFor)
        assertEquals(listOf("ana@example.com" to "123456"), account.verified)
    }

    @Test
    fun aFailedRequestIsShownAndStaysOnTheEmailStep() {
        account.failure = IllegalStateException("Too many requests")
        compose.setContent { SignInScreen(account) }

        compose.onNodeWithText("Email").performTextInput("ana@example.com")
        compose.onNodeWithText("Send Code").performClick()

        compose.onNodeWithText("Too many requests").assertExists()
        compose.onNodeWithText("Send Code").assertIsEnabled()
    }

    @Test
    fun theDebugWayInOnlyShowsWhereItIsOffered() {
        compose.setContent { SignInScreen(account) }

        compose.onNodeWithText("Continue Without an Account (Debug)").assertDoesNotExist()
    }

    @Test
    fun theScreenSaysThisIsAPrototype() {
        compose.setContent { SignInScreen(account) }

        compose.onNodeWithText("Prototype. Not for real vaccine or medicine stock.").assertExists()
    }

    @Test
    fun aNewPersonChoosesTheirRole() {
        compose.setContent { ProfileSetupScreen(account) }

        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Your Name").performTextInput("Ana")
        compose.onNode(hasText("Receiver") and isSelectable()).performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()

        assertEquals(UserProfile("Ana", MemberRole.RECEIVER), account.profileCompleted)
        assertTrue(account.state.value is AccountState.Ready)
    }

    @Test
    fun anUnreachableServerCanBeRetried() {
        compose.setContent { AccountUnreachableScreen(account) }

        compose.onNodeWithText("Try Again").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Try Again").assertIsEnabled()
    }
}
