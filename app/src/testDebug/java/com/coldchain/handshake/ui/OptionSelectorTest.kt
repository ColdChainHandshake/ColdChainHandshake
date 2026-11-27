package com.coldchain.handshake.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.coldchain.handshake.ui.components.OptionSelector
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Found on the emulator (27 Nov, 5.5 audit): the Handover inspection's options (vial monitor, shake test, verdict)
 * were 85 px tall at 420 dpi, about 32 dp, under the 48 dp a finger needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OptionSelectorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyOptionIsAtLeastAFingerTall() {
        val options = listOf("Not done", "Passed", "Failed (freeze damage)")
        compose.setContent {
            OptionSelector(title = "Shake test", options = options, selected = "Not done", label = { it }, onSelect = {})
        }

        options.forEach { compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp) }
    }
}
