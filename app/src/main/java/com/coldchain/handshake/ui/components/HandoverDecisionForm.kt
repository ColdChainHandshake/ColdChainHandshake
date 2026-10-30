package com.coldchain.handshake.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coldchain.handshake.handover.HandoverEvaluation
import com.coldchain.handshake.handover.HandoverVerdictEngine
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.VvmStage

/**
 * What the receiver found, and the verdict to record. It offers only what
 * [HandoverVerdictEngine.decide] would accept, so the screen can't drift from the rules.
 *
 * @param enabled False while the record can't be made yet, such as during history sync.
 * @param waitingLabel Button text to show instead, saying why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoverDecisionForm(
    evaluation: HandoverEvaluation,
    vvmStage: VvmStage,
    onVvmStageChange: (VvmStage) -> Unit,
    shakeTest: ShakeTestResult,
    onShakeTestChange: (ShakeTestResult) -> Unit,
    enabled: Boolean,
    waitingLabel: String?,
    onRecord: (verdict: HandoverVerdict, reason: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var chosen by rememberSaveable { mutableStateOf<HandoverVerdict?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }

    val allowed = HandoverVerdict.entries.filter { it >= evaluation.verdict }
    val verdict = chosen?.takeIf { it in allowed } ?: evaluation.verdict
    val decision = HandoverVerdictEngine.decide(evaluation, verdict, reason)

    Column(modifier = modifier) {
        OptionSelector(
            title = "Vial monitor",
            options = VvmStage.entries,
            selected = vvmStage,
            label = ::vvmLabel,
            onSelect = onVvmStageChange
        )
        Spacer(modifier = Modifier.height(10.dp))

        OptionSelector(
            title = "Shake test",
            options = ShakeTestResult.entries,
            selected = shakeTest,
            label = ::shakeTestLabel,
            onSelect = onShakeTestChange
        )
        Spacer(modifier = Modifier.height(10.dp))

        OptionSelector(
            title = "Verdict to record",
            options = allowed,
            selected = verdict,
            label = ::verdictLabel,
            onSelect = { chosen = it }
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            label = { Text("Reason") },
            modifier = Modifier.fillMaxWidth()
        )

        if (decision.isFailure) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Add a reason before recording this verdict.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onRecord(verdict, reason.trim().ifEmpty { null }) },
            enabled = enabled && decision.isSuccess,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(waitingLabel ?: "Record ${verdict.name} handover")
        }
    }
}

private fun vvmLabel(stage: VvmStage): String = when (stage) {
    VvmStage.NOT_RECORDED -> "Not recorded"
    VvmStage.STAGE_1 -> "Stage 1 (usable)"
    VvmStage.STAGE_2 -> "Stage 2 (usable)"
    VvmStage.STAGE_3 -> "Stage 3 (discard point)"
    VvmStage.STAGE_4 -> "Stage 4 (past discard point)"
}

private fun shakeTestLabel(result: ShakeTestResult): String = when (result) {
    ShakeTestResult.NOT_DONE -> "Not done"
    ShakeTestResult.PASSED -> "Passed"
    ShakeTestResult.FAILED -> "Failed (freeze damage)"
}

private fun verdictLabel(verdict: HandoverVerdict): String = when (verdict) {
    HandoverVerdict.PASS -> "Pass: accept the shipment"
    HandoverVerdict.UNKNOWN -> "Unknown: hold the shipment"
    HandoverVerdict.FAIL -> "Fail: quarantine the shipment"
}
