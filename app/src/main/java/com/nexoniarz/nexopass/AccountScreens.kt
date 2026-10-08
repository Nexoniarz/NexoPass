package com.nexoniarz.nexopass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val accountNameRegex = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

/** Logo, title and a line of text above the forms. */
@Composable
fun Header(subtitle: String) {
    Logo(80.dp)
    Spacer(Modifier.height(16.dp))
    Text("NexoPass", style = MaterialTheme.typography.displaySmall.copy(brush = BrandBrush, fontWeight = FontWeight.Bold))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(22.dp))
}

@Composable
fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

// --- master forms --------------------------------------------------------------------------

/** One master field with Show; for opening an existing account. */
@Composable
fun MasterUnlockForm(busy: Boolean, button: String, onSubmit: (String) -> Unit) {
    val master = remember { MasterState() }
    var revealed by remember { mutableStateOf(false) }
    fun submit() {
        val m = master.value()
        if (m.isNotEmpty() && !busy) onSubmit(m)
    }
    MasterInput(master, "Master", revealed) { submit() }
    TextButton(onClick = { revealed = !revealed }) {
        Icon(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (revealed) "Hide" else "Show")
    }
    GradientButton(if (busy) "Checking…" else button, enabled = !busy, onClick = ::submit)
}

/** Name + master twice; for a new account. */
@Composable
fun CreateAccountForm(app: AppState, busy: Boolean, defaultName: String, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    val master = remember { MasterState() }
    val repeat = remember { MasterState() }
    var revealed by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val value = master.value()
    val norm = name.trim().lowercase()

    fun submit() {
        val m = master.value()
        problem = when {
            !accountNameRegex.matches(norm) -> "Account name: lowercase letters, digits, - or _."
            norm in app.accounts -> "There is already an account called '$norm'."
            m.isEmpty() -> "Type the master first."
            m.toByteArray().size > NexoPass.MAX_MASTER_BYTES -> "Master is too long (max 127 bytes)."
            NexoPass.masterStrength(m) == NexoPass.Strength.TOO_SHORT -> "Too short: at least 3 words and 12 characters."
            m != repeat.value() -> "Masters don't match."
            else -> null
        }
        if (problem == null && !busy) onCreate(norm, m)
    }

    OutlinedTextField(
        name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Account name") },
        leadingIcon = { Icon(Icons.Rounded.AccountCircle, null) },
        supportingText = { Text("e.g. main, work. Each account has its own master and site list.") },
        shape = MaterialTheme.shapes.medium,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "The master: a new one, or the one you already use on your PC (same master, same passwords).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    MasterInput(master, "Master", revealed) {}
    Spacer(Modifier.height(12.dp))
    MasterInput(repeat, "Repeat master", revealed) { submit() }
    if (value.isNotEmpty()) StrengthBar(NexoPass.masterStrength(value))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { revealed = !revealed }) {
            Icon(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (revealed) "Hide" else "Show")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = {
            val m = NexoPass.newMaster(app.words)
            master.set(m)
            repeat.set(m)
            revealed = true
        }) {
            Icon(Icons.Rounded.Casino, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Random 6 words")
        }
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
    GradientButton(if (busy) "Creating…" else "Create account", enabled = !busy, onClick = ::submit)
}

@Composable
fun StrengthBar(s: NexoPass.Strength) {
    val (progress, color) = when (s) {
        NexoPass.Strength.TOO_SHORT -> .15f to MaterialTheme.colorScheme.error
        NexoPass.Strength.MINIMUM -> .45f to Color(0xFFF59E0B)
        NexoPass.Strength.RECOMMENDED -> .7f to Color(0xFF34D399)
        NexoPass.Strength.STRONG -> .85f to Color(0xFF34D399)
        NexoPass.Strength.SAFEST -> 1f to Cyan
    }
    Column(Modifier.padding(top = 12.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
        Text(s.label, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 6.dp))
    }
}

// --- screens -------------------------------------------------------------------------------

/** Required right after unlocking with a master: the app unlock replaces typing the master. */
@Composable
fun AppUnlockSetupScreen(app: AppState) {
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askBio by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val bioOptions = remember { QuickUnlock.bioOptions(activity) }

    CenteredColumn {
        Header(if (askBio) "One more thing." else "Choose how you unlock NexoPass. You won't type your master anymore.")
        GlassCard {
            if (!askBio) {
                MethodCard(Icons.Rounded.Pin, "PIN", "Fast. 4+ digits, 6 is better.") { method = "pin" }
                MethodCard(Icons.Rounded.Password, "Password", "The strongest option.") { method = "password" }
                MethodCard(Icons.Rounded.Pattern, "Pattern", "Connect 4+ dots.") { method = "pattern" }
                Text(
                    "Too many wrong tries remove it; then you recover with your master. Keep the master in your head.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text("Unlock with your fingerprint too?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your ${methodName(app.quick.method)} stays as the backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                GradientButton("Turn on", enabled = true) {
                    app.quick.enableBio(activity, bioOptions.first(), app.innerKey()) { err ->
                        if (err == null) app.finishAppSetup() else if (err.isNotEmpty()) error = err
                    }
                }
                TextButton(onClick = { app.finishAppSetup() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now") }
            }
            AnimatedVisibility(busy) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).clip(CircleShape)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    method?.let { m ->
        SecretSetupDialog(m, onCancel = { method = null }) { secret ->
            method = null
            busy = true
            scope.launch {
                withContext(Dispatchers.Default) { app.setupApp(m, secret) }
                busy = false
                if (bioOptions.isNotEmpty()) askBio = true else app.finishAppSetup()
            }
        }
    }
}

@Composable
private fun MethodCard(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null)
    }
}

/** An account whose master isn't in the keyring yet (added before, or after a recovery). */
@Composable
fun AccountMasterScreen(app: AppState, name: String, onAddAccount: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    CenteredColumn {
        Header("Account '$name' needs its master once. After that, your app unlock opens it.")
        GlassCard {
            MasterUnlockForm(busy, "Open '$name'") { m ->
                busy = true
                error = null
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { app.unlockWithMaster(name, m.toByteArray()) }
                    busy = false
                    if (!ok) error = "That master doesn't open '$name'."
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        val others = app.accounts.filter { it != name }
        if (others.isNotEmpty()) {
            Text("Or switch to:", modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            others.forEach { a -> TextButton(onClick = { app.openAccount(a) }) { Text(a) } }
        }
        Row(Modifier.padding(top = 8.dp)) {
            TextButton(onClick = onAddAccount) { Text("Add account") }
            TextButton(onClick = app::lock) { Text("Lock") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAccountScreen(app: AppState, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("New account") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GlassCard {
                CreateAccountForm(app, busy, defaultName = "") { name, m ->
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.Default) { app.createAccount(name, m.toByteArray()) }
                        busy = false
                        onDone()
                    }
                }
            }
        }
    }
}
