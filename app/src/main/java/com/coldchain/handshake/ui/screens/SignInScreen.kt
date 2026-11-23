package com.coldchain.handshake.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.ui.components.OptionSelector
import kotlinx.coroutines.launch

private const val PROTOTYPE_NOTICE = "Prototype. Not for real vaccine or medicine stock."

@Composable
private fun AccountColumn(title: String, content: @Composable () -> Unit) {
    // These screens sit outside the main Scaffold, so they keep clear of the status bar, the cut-out and the
    // keyboard themselves. Seen on the emulator: without this the title drew under the clock.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
        Text(
            PROTOTYPE_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorLine(message: String?) {
    if (message != null) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * @param onUseWithoutAccount Offered only where working without a server is allowed, which is debug.
 */
@Composable
fun SignInScreen(
    account: AccountRepository,
    onUseWithoutAccount: (() -> Unit)? = null,
    notice: String? = null
) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun attempt(action: suspend () -> Result<Unit>, onSuccess: () -> Unit = {}) {
        busy = true
        error = null
        scope.launch {
            action()
                .onSuccess { onSuccess() }
                .onFailure { error = it.message ?: "That didn't work. Try again." }
            busy = false
        }
    }

    AccountColumn("Sign in") {
        ErrorLine(notice)
        if (!codeSent) {
            Text("Enter your email and we'll send you a code.")
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { attempt({ account.requestCode(email) }, onSuccess = { codeSent = true }) },
                enabled = !busy && email.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send Code")
            }
        } else {
            Text("Enter the code we sent to $email.")
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { attempt({ account.verifyCode(email, code) }) },
                enabled = !busy && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Verify")
            }
            TextButton(
                onClick = {
                    code = ""
                    error = null
                    codeSent = false
                },
                enabled = !busy
            ) {
                Text("Use Another Email")
            }
        }

        ErrorLine(error)

        if (onUseWithoutAccount != null) {
            TextButton(onClick = onUseWithoutAccount) {
                Text("Continue Without an Account (Debug)")
            }
        }
    }
}

@Composable
fun ProfileSetupScreen(account: AccountRepository) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(MemberRole.COURIER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountColumn("About you") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OptionSelector(
            title = "Your Role",
            options = MemberRole.entries.toList(),
            selected = role,
            label = ::roleLabel,
            onSelect = { role = it }
        )
        Text(
            "Your role can't be changed later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    account.completeProfile(name, role)
                        .onFailure { error = it.message ?: "That didn't work. Try again." }
                    busy = false
                }
            },
            enabled = !busy && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
        ErrorLine(error)
    }
}

@Composable
fun AccountUnreachableScreen(account: AccountRepository) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    AccountColumn("Can't reach the server") {
        Text("You're signed in, but your details couldn't be fetched. Check your connection.")
        Button(
            onClick = {
                busy = true
                scope.launch {
                    account.refresh()
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Try Again")
        }
    }
}

@Composable
fun AccountLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

fun roleLabel(role: MemberRole): String = when (role) {
    MemberRole.SENDER -> "Sender"
    MemberRole.COURIER -> "Courier"
    MemberRole.RECEIVER -> "Receiver"
}
