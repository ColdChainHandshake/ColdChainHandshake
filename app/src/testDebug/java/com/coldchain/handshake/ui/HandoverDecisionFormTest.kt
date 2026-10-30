package com.coldchain.handshake.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.coldchain.handshake.handover.HandoverEvaluation
import com.coldchain.handshake.handover.HandoverVerdictEngine
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.safety.TemperatureVerdict
import com.coldchain.handshake.ui.components.HandoverDecisionForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No emulator is needed: these run the real composable under Robolectric, so they show what a
 * person can and can't do at handover. The tall screen fits the whole form, which scrolls in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp")
class HandoverDecisionFormTest {

    @get:Rule
    val compose = createComposeRule()

    private var recorded: Pair<HandoverVerdict, String?>? = null
    private var vvmStage: VvmStage? = null
    private var shakeTest: ShakeTestResult? = null

    private val clean = HandoverVerdictEngine.evaluate(TemperatureVerdict.PASS, true, true, true)
    private val gap = HandoverVerdictEngine.evaluate(TemperatureVerdict.UNKNOWN, true, true, true)
    private val breach = HandoverVerdictEngine.evaluate(TemperatureVerdict.FAIL, true, true, true)

    private fun show(evaluation: HandoverEvaluation, enabled: Boolean = true, waitingLabel: String? = null) {
        compose.setContent {
            HandoverDecisionForm(
                evaluation = evaluation,
                vvmStage = VvmStage.NOT_RECORDED,
                onVvmStageChange = { vvmStage = it },
                shakeTest = ShakeTestResult.NOT_DONE,
                onShakeTestChange = { shakeTest = it },
                enabled = enabled,
                waitingLabel = waitingLabel,
                onRecord = { verdict, reason -> recorded = verdict to reason }
            )
        }
    }

    @Test
    fun aCleanResultCanBeRecordedAsAPass() {
        show(clean)

        compose.onNodeWithText("Record PASS handover").performClick()

        assertEquals(HandoverVerdict.PASS to null, recorded)
    }

    @Test
    fun theReceiverCanFailACleanResultOnceTheyGiveAReason() {
        show(clean)

        compose.onNodeWithText("Fail: quarantine the shipment").performClick()
        compose.onNodeWithText("Record FAIL handover").assertIsNotEnabled()

        compose.onNodeWithText("Reason").performTextInput("Seal broken")
        compose.onNodeWithText("Record FAIL handover").assertIsEnabled().performClick()

        assertEquals(HandoverVerdict.FAIL to "Seal broken", recorded)
    }

    @Test
    fun theReceiverCanRecordUnknownOnceTheyGiveAReason() {
        show(clean)

        compose.onNodeWithText("Unknown: hold the shipment").performClick()
        compose.onNodeWithText("Reason").performTextInput("Logger damaged")
        compose.onNodeWithText("Record UNKNOWN handover").performClick()

        assertEquals(HandoverVerdict.UNKNOWN to "Logger damaged", recorded)
    }

    @Test
    fun aGapCannotBePassedButCanBeRecordedWithoutTyping() {
        show(gap)

        compose.onNodeWithText("Pass: accept the shipment").assertDoesNotExist()
        compose.onNodeWithText("Record UNKNOWN handover").assertIsEnabled().performClick()

        assertEquals(HandoverVerdict.UNKNOWN to null, recorded)
    }

    @Test
    fun aBreachOffersOnlyFail() {
        show(breach)

        compose.onNodeWithText("Pass: accept the shipment").assertDoesNotExist()
        compose.onNodeWithText("Unknown: hold the shipment").assertDoesNotExist()
        compose.onNodeWithText("Record FAIL handover").assertIsEnabled().performClick()

        assertEquals(HandoverVerdict.FAIL to null, recorded)
    }

    @Test
    fun nothingCanBeRecordedWhileHistoryIsStillSyncing() {
        show(clean, enabled = false, waitingLabel = "Waiting for Complete History...")

        compose.onNodeWithText("Waiting for Complete History...").assertIsNotEnabled().performClick()

        assertNull(recorded)
    }

    @Test
    fun vialMonitorAndShakeTestResultsReachTheScreen() {
        show(clean)

        compose.onNodeWithText("Stage 3 (discard point)").performClick()
        compose.onNodeWithText("Failed (freeze damage)").performClick()

        assertEquals(VvmStage.STAGE_3, vvmStage)
        assertEquals(ShakeTestResult.FAILED, shakeTest)
    }
}
