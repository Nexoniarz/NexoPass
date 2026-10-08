package com.nexoniarz.nexopass.ui

import com.nexoniarz.nexopass.app.*
import com.nexoniarz.nexopass.core.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LockScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    val quick = app.lock
    // Without an app unlock (update, too many wrong tries) or on request: a master.
    var recover by remember { mutableStateOf(!quick.isSet) }
    var account by remember { mutableStateOf(app.current) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    fun unlockQuick(secret: String) {
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.Default) { quick.unlock(secret) }
            when (r) {
                is UnlockResult.Ok -> withContext(Dispatchers.Default) { app.unlockApp(r) }
                is UnlockResult.Wrong -> {
                    attempt++
                    error = "Wrong ${methodName(quick.method)}. ${r.attemptsLeft} attempt(s) left."
                }
                UnlockResult.Wiped -> {
                    error = "Too many wrong attempts: the app unlock was removed. Recover with your master."
                    recover = true
                }
            }
            busy = false
        }
    }

    fun unlockBio() {
        error = null
        quick.unlockBio("Use ${methodName(quick.method)}", onOk = { r ->
            busy = true
            scope.launch {
                withContext(Dispatchers.Default) { app.unlockApp(r) }
                busy = false
            }
        }, onError = { if (it.isNotEmpty()) error = it })
    }

    fun unlockMaster(m: String) {
        busy = true
        error = null
        scope.launch {
            val ok = withContext(Dispatchers.Default) { app.unlockWithMaster(account, m.toByteArray()) }
            busy = false
            if (!ok) error = "That master doesn't open '$account'."
        }
    }

    LaunchedEffect(Unit) {
        if (!recover && quick.bioMode != "off") unlockBio()
    }

    if (app.accounts.isEmpty()) {
        FirstRun(app)
        return
    }

    CenteredColumn {
        Header(
            when {
                !recover && quick.method == "pattern" -> "Draw your pattern."
                !recover -> "Enter your ${methodName(quick.method)}."
                app.migrated -> "NexoPass now has accounts and an app unlock. Enter your master once to set it up."
                quick.isSet -> "Recover: enter the master of an account, then set a new app unlock."
                else -> "Enter the master of an account, then choose your app unlock."
            },
        )
        GlassCard {
            if (!recover) {
                SecretEntry(quick.method, enabled = !busy, clearKey = attempt) { unlockQuick(it) }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { recover = true; error = null }) { Text("Forgot it?") }
                    if (quick.bioMode != "off") BioButton(::unlockBio)
                }
            } else {
                if (app.accounts.size > 1) {
                    Text("Account", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    AccountChips(app.accounts, account) { account = it }
                    Spacer(Modifier.height(8.dp))
                }
                MasterUnlockForm(busy, "Unlock '$account'") { unlockMaster(it) }
                if (quick.isSet) {
                    Text(
                        "This replaces your app unlock. Other accounts will ask for their master once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            AnimatedVisibility(error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
            }
            AnimatedVisibility(busy) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp).clip(CircleShape)) }
        }
        if (recover && quick.isSet) {
            TextButton(onClick = { recover = false; error = null }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Back to ${methodName(quick.method)}")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountChips(accounts: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a ->
            FilterChip(selected = a == selected, onClick = { onPick(a) }, label = { Text(a) })
        }
    }
}

@Composable
private fun FirstRun(app: AppState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    CenteredColumn {
        Header("Welcome. Create your first account.")
        GlassCard {
            CreateAccountForm(app, busy, defaultName = "main") { name, m ->
                busy = true
                scope.launch {
                    withContext(Dispatchers.Default) { app.createAccount(name, m.toByteArray()) }
                    busy = false
                }
            }
        }
    }
}
