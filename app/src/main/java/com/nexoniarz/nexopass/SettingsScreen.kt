package com.nexoniarz.nexopass

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

const val APP_VERSION = "1.2.0"

private fun bioLabel(mode: String) = when (mode) {
    "strong" -> "Fingerprint or face"
    "device" -> "Biometrics or phone screen lock"
    else -> "Off"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppState, session: Session, onBack: () -> Unit) {
    val prefs = app.prefs
    val quick = app.quick
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun toast(msg: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) }

    var dialog by remember { mutableStateOf<String?>(null) }
    var setupMethod by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var shownMaster by remember { mutableStateOf<String?>(null) }
    val bioOptions = remember { QuickUnlock.bioOptions(activity) }
    // Read so the rows refresh after changes.
    val method = prefs.quickMethod.let { quick.method }
    val bio = prefs.bioMode.let { quick.bioMode }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pgp-encrypted")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { activity.contentResolver.openOutputStream(uri)!!.use { it.write(session.exportBytes()) } }.isSuccess
            }
            busy = false
            toast(if (ok) "Exported. It opens only with this account's master, here or with nexopass on PC." else "Export failed.")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()?.let { session.importBytes(it) }
            }
            busy = false
            toast(if (n == null) "Can't open it: exported from another account (different master), or not a NexoPass export." else "Imported: $n new entr${if (n == 1) "y" else "ies"}.")
        }
    }
    fun exportNow() = exportLauncher.launch("nexopass-${session.name}-${LocalDate.now()}.pgp")

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))

            SectionTitle("Account: ${session.name}")
            GlassCard(padding = 4.dp) {
                SettingRow(Icons.Rounded.DriveFileRenameOutline, "Rename", "Currently \"${session.name}\"") { dialog = "rename" }
                SettingRow(Icons.Rounded.Visibility, "Show master", "Needs your ${methodName(method)}") { dialog = "verify-master" }
                SettingRow(Icons.Rounded.Upload, "Export", "Encrypted copy of this account's site list") { exportNow() }
                SettingRow(Icons.Rounded.Download, "Import", "Merge an export from this phone or your PC") { importLauncher.launch(arrayOf("*/*")) }
                SettingRow(Icons.Rounded.LinkOff, "Detach this account", "Remove it from this phone", danger = true) { dialog = "detach" }
            }

            SectionTitle("App unlock")
            GlassCard(padding = 4.dp) {
                SettingRow(Icons.Rounded.Pin, "Unlock with", methodName(method).replaceFirstChar { it.uppercase() } + " · tap to change") { dialog = "verify-change" }
                SettingRow(
                    Icons.Rounded.Fingerprint, "Biometrics",
                    if (bioOptions.isEmpty()) "Not available on this phone" else bioLabel(bio),
                    enabled = bioOptions.isNotEmpty(),
                ) { dialog = "bio" }
                SettingRow(Icons.Rounded.Timer, "Auto-lock", if (prefs.autoLockMinutes == 0) "As soon as you leave the app" else "${prefs.autoLockMinutes} min after leaving") { dialog = "autolock" }
                SettingRow(Icons.Rounded.GppMaybe, "Wrong attempts allowed", "${prefs.maxAttempts}, then you recover with a master") { dialog = "attempts" }
            }

            SectionTitle("Passwords")
            GlassCard(padding = 4.dp) {
                SettingRow(Icons.Rounded.Password, "New sites use", modeLabel(prefs.defaultMode)) { dialog = "mode" }
                SettingRow(Icons.Rounded.ContentPaste, "Clipboard clears after", "${prefs.clipSeconds} s") { dialog = "clip" }
            }

            SectionTitle("Appearance")
            GlassCard(padding = 4.dp) {
                SettingRow(Icons.Rounded.Palette, "Theme", prefs.theme.replaceFirstChar { it.uppercase() }) { dialog = "theme" }
                SwitchRow(Icons.Rounded.AutoAwesome, "Animated background", "Particles behind everything", prefs.particles) { prefs.particles = it }
            }

            SectionTitle("About")
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(48.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("NexoPass $APP_VERSION", style = MaterialTheme.typography.titleMedium.copy(brush = BrandBrush, fontWeight = FontWeight.Bold))
                        Text("Made by Nexoniarz", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Same passwords as nexopass on NixOS. Nothing leaves this phone: the app has no internet permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { dialog = "license" }, contentPadding = PaddingValues(0.dp)) { Text("License: Apache License 2.0") }
            }
        }
    }

    when (dialog) {
        "rename" -> RenameDialog(app, session.name, onDismiss = { dialog = null }) { new ->
            dialog = null
            app.renameAccount(session.name, new)
        }
        "verify-master" -> VerifyDialog(app, "to see the master of '${session.name}'", onDismiss = { dialog = null }) {
            dialog = null
            shownMaster = app.masterOf(session.name)
        }
        "verify-change" -> VerifyDialog(app, "to change how you unlock", onDismiss = { dialog = null }) { dialog = "method" }
        "method" -> ChoiceDialog(
            "Unlock with",
            listOf("pin" to "PIN", "password" to "Password", "pattern" to "Pattern"),
            method,
            onDismiss = { dialog = null },
        ) { picked ->
            dialog = null
            setupMethod = picked
        }
        "bio" -> ChoiceDialog("Biometrics", listOf("off" to "Off") + bioOptions.map { it to bioLabel(it) }, bio, onDismiss = { dialog = null }) { picked ->
            dialog = null
            if (picked == "off") {
                quick.disableBio()
                toast("Biometrics off.")
            } else {
                quick.enableBio(activity, picked, app.innerKey()) { err ->
                    if (err == null) toast("Biometric unlock is on.") else if (err.isNotEmpty()) toast(err)
                }
            }
        }
        "autolock" -> ChoiceDialog(
            "Auto-lock",
            listOf(0, 1, 5, 15, 30, 60).map { it.toString() to if (it == 0) "Immediately" else "After $it min" },
            prefs.autoLockMinutes.toString(),
            onDismiss = { dialog = null },
        ) { prefs.autoLockMinutes = it.toInt(); dialog = null }
        "attempts" -> ChoiceDialog("Wrong attempts allowed", listOf("3", "5", "10").map { it to it }, prefs.maxAttempts.toString(), onDismiss = { dialog = null }) {
            prefs.maxAttempts = it.toInt()
            dialog = null
        }
        "clip" -> ChoiceDialog("Clipboard clears after", listOf(10, 15, 30, 60, 120).map { it.toString() to "$it seconds" }, prefs.clipSeconds.toString(), onDismiss = { dialog = null }) {
            prefs.clipSeconds = it.toInt()
            dialog = null
        }
        "theme" -> ChoiceDialog("Theme", listOf("system" to "Follow the system", "dark" to "Dark", "light" to "Light"), prefs.theme, onDismiss = { dialog = null }) {
            prefs.theme = it
            dialog = null
        }
        "mode" -> {
            var mode by remember { mutableStateOf(prefs.defaultMode) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("New sites use") },
                text = { Column { ModePicker(mode) { mode = it } } },
                confirmButton = { TextButton(onClick = { prefs.defaultMode = mode; dialog = null }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "detach" -> DetachDialog(app, session.name, onExport = ::exportNow, onDismiss = { dialog = null }) {
            dialog = null
            onBack()
        }
        "license" -> LicenseDialog { dialog = null }
    }

    setupMethod?.let { m ->
        SecretSetupDialog(m, onCancel = { setupMethod = null }) { secret ->
            setupMethod = null
            val hadBio = bio
            busy = true
            scope.launch {
                withContext(Dispatchers.Default) { app.setupApp(m, secret) }
                busy = false
                toast("You now unlock with your ${methodName(m)}.")
                // The biometric copy holds the old key: set it up again right away.
                if (hadBio != "off") {
                    quick.enableBio(activity, hadBio, app.innerKey()) { err ->
                        if (err != null && err.isNotEmpty()) toast(err) else if (err != null) toast("Biometrics are off now; turn them on again any time.")
                    }
                }
            }
        }
    }
    shownMaster?.let { m ->
        AlertDialog(
            onDismissRequest = { shownMaster = null },
            icon = { Icon(Icons.Rounded.Key, null) },
            title = { Text("Master of '${session.name}'") },
            text = {
                Column {
                    Text(m, fontFamily = FontFamily.Monospace, fontSize = 20.sp, lineHeight = 30.sp)
                    Text(
                        "Make sure nobody is looking. Screenshots are blocked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { shownMaster = null }) { Text("Hide") } },
        )
    }
}

/** Confirms it's you with the app unlock (or biometrics). */
@Composable
private fun VerifyDialog(app: AppState, reason: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    val quick = app.quick
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Confirm it's you", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter your ${methodName(quick.method)} $reason.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
                SecretEntry(quick.method, enabled = !busy, clearKey = attempt) { secret ->
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.Default) { quick.unlock(secret) }
                        busy = false
                        attempt++
                        when (r) {
                            is QuickUnlock.Result.Ok -> onOk()
                            is QuickUnlock.Result.Wrong -> error = "Wrong ${methodName(quick.method)}. ${r.attemptsLeft} attempt(s) left."
                            QuickUnlock.Result.Wiped -> app.lock()
                        }
                    }
                }
                if (quick.bioMode != "off") {
                    Spacer(Modifier.height(12.dp))
                    BioButton {
                        quick.unlockBio(activity, "Cancel", onOk = { onOk() }, onError = { if (it.isNotEmpty()) error = it })
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun RenameDialog(app: AppState, current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val norm = name.trim().lowercase()
    val problem = when {
        norm == current -> null
        !accountNameRegex.matches(norm) -> "Lowercase letters, digits, - or _."
        norm in app.accounts -> "There is already an account called '$norm'."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename account") },
        text = {
            OutlinedTextField(
                name, { name = it }, singleLine = true,
                label = { Text("Account name") },
                isError = problem != null,
                supportingText = { problem?.let { Text(it) } },
            )
        },
        confirmButton = { TextButton(enabled = problem == null && norm != current, onClick = { onRename(norm) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Export offer, then app unlock, then the account's master, then "yes". */
@Composable
private fun DetachDialog(app: AppState, name: String, onExport: () -> Unit, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var yes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val master = remember { MasterState() }

    when (step) {
        0 -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.LinkOff, null) },
            title = { Text("Detach '$name'?") },
            text = {
                Text(
                    "This removes the account from this phone: its site list with the archive and its saved master. " +
                        "Your logins keep their passwords and the master can recreate them, but the site list is gone " +
                        "unless you export it first (or it's still on your PC).",
                )
            },
            confirmButton = { TextButton(onClick = { step = 1 }) { Text("Continue") } },
            dismissButton = {
                Row {
                    TextButton(onClick = onExport) { Text("Export first") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )
        1 -> VerifyDialog(app, "to detach '$name'", onDismiss = onDismiss) { step = 2 }
        else -> Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp)) {
                    Text("Last check", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Enter the master of '$name' and type yes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    MasterInput(master, "Master of '$name'", revealed = false) {}
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(yes, { yes = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Type yes") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            enabled = !busy && yes.trim() == "yes" && master.value().isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = {
                                busy = true
                                error = null
                                val m = master.value().toByteArray()
                                scope.launch {
                                    val ok = withContext(Dispatchers.Default) { app.checkMaster(name, m) }
                                    busy = false
                                    if (ok) {
                                        app.detachAccount(name)
                                        onDone()
                                    } else {
                                        error = "That master doesn't open '$name'. Nothing was removed."
                                    }
                                }
                            },
                        ) { Text("Detach") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember { context.assets.open("LICENSE").use { it.readBytes().decodeToString() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apache License 2.0") },
        text = {
            Text(
                "Copyright 2026 Nexoniarz\n\n$text",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.outline
                    danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, value: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = key == selected, onClick = { onPick(key) }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == selected, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
