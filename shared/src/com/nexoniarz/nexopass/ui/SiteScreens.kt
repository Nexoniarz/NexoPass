package com.nexoniarz.nexopass.ui

import com.nexoniarz.nexopass.app.*
import com.nexoniarz.nexopass.core.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- site list -----------------------------------------------------------------------------

@Composable
fun ListScreen(app: AppState, session: Session, onOpen: (Rec) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onNewAccount: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var checkWords by remember { mutableStateOf<String?>(null) }
    val all = session.current
    val q = query.trim().lowercase()
    val list = all.filter { q.isEmpty() || q in it.site || q in it.user }
    val guess = NexoPass.normalizeSite(query)
    val didYouMean = if (list.isEmpty() && guess != null) NexoPass.suggestions(guess, "", all) else emptyList()

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add site") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Logo(44.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("NexoPass", style = MaterialTheme.typography.headlineSmall.copy(brush = BrandBrush, fontWeight = FontWeight.Bold))
                        AccountSwitcher(app, session, all.size, onNewAccount)
                    }
                    IconButton(onClick = { scope.launch { checkWords = withContext(Dispatchers.Default) { session.checkWords() } } }) {
                        Icon(Icons.Rounded.Verified, "Check words")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                    FilledTonalIconButton(onClick = app::lock) { Icon(Icons.Rounded.Lock, "Lock") }
                }
            }
            item {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Search sites") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                    singleLine = true,
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .7f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .85f),
                    ),
                )
            }
            if (didYouMean.isNotEmpty()) {
                item { SectionTitle("Did you mean") }
                items(didYouMean, key = { "s" + it.site + "\u001f" + it.user }) { SiteRow(it) { onOpen(it) } }
            } else if (list.isEmpty()) {
                item { EmptyState(if (all.isEmpty()) "No sites yet.\nTap \"Add site\" to start." else "Nothing found.") }
            }
            items(list, key = { it.site + "\u001f" + it.user }) { SiteRow(it) { onOpen(it) } }
        }
    }

    app.firstRunCheck?.let { words ->
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Rounded.Verified, null) },
            confirmButton = { TextButton(onClick = { app.firstRunCheck = null }) { Text("Got it") } },
            title = { Text("Check words") },
            text = { Text("$words\n\nYour PC shows the same two words for the same master. If they differ, the masters differ.") },
        )
    }
    checkWords?.let {
        AlertDialog(
            onDismissRequest = { checkWords = null },
            icon = { Icon(Icons.Rounded.Verified, null) },
            confirmButton = { TextButton(onClick = { checkWords = null }) { Text("OK") } },
            title = { Text("Check words") },
            text = { Text(it, style = MaterialTheme.typography.titleLarge) },
        )
    }
}

@Composable
private fun SiteRow(r: Rec, onClick: () -> Unit) {
    GlassCard(Modifier.clickable(onClick = onClick), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.site, 44.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(r.site, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (r.user.isNotEmpty()) Text(r.user, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Tag("v${r.version}", MaterialTheme.colorScheme.secondaryContainer)
            Spacer(Modifier.width(6.dp))
            Tag(modeLabel(r.mode), MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

/** "main ▾ · 12 sites": tap to switch account or add one. */
@Composable
private fun AccountSwitcher(app: AppState, session: Session, sites: Int, onNewAccount: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(session.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Rounded.ArrowDropDown, "Switch account", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(" · $sites site${if (sites == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            app.accounts.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a, fontWeight = if (a == session.name) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = { Icon(if (a == session.name) Icons.Rounded.CheckCircle else Icons.Rounded.AccountCircle, null) },
                    onClick = { open = false; if (a != session.name) app.openAccount(a) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Add account") }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) }, onClick = { open = false; onNewAccount() })
        }
    }
}

// --- add site ------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(session: Session, onBack: () -> Unit, onOpen: (Rec) -> Unit) {
    var site by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1") }
    var mode by remember { mutableStateOf(session.prefs.defaultMode) }
    val normSite = NexoPass.normalizeSite(site)
    val normUser = NexoPass.normalizeUser(user)
    val ver = version.toIntOrNull()?.takeIf { it in 1..9999 }
    val existing = if (normSite != null && normUser != null) session.current.firstOrNull { it.site == normSite && it.user == normUser } else null
    val similar = if (normSite != null && normUser != null) NexoPass.suggestions(normSite, normUser, session.current) else emptyList()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Add site") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GlassCard {
                OutlinedTextField(
                    site, { site = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Site") },
                    placeholder = { Text("discord, allegro.pl, …") },
                    leadingIcon = { Icon(Icons.Rounded.Language, null) },
                    supportingText = {
                        Text(
                            when {
                                site.isBlank() -> "Case doesn't matter. Links work too."
                                normSite == null -> "Not a valid site name."
                                else -> "Saved as: $normSite"
                            },
                        )
                    },
                    isError = site.isNotBlank() && normSite == null,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    user, { user = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Account name (optional)") },
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    supportingText = { Text("Only if you have several accounts on this site.") },
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    version, { version = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Start at version") },
                    leadingIcon = { Icon(Icons.Rounded.History, null) },
                    supportingText = { Text("Keep 1, unless you already rotated this site on your PC.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = ver == null,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(4.dp))
                ModePicker(mode) { mode = it }
            }

            AnimatedVisibility(existing != null || similar.isNotEmpty()) {
                GlassCard(Modifier.padding(top = 12.dp)) {
                    if (existing != null) {
                        Text("${existing.label} is already on your list.", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { onOpen(existing) }) { Text("Open it") }
                    } else {
                        Text("Did you mean:", style = MaterialTheme.typography.titleSmall)
                        similar.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onOpen(r) }.padding(vertical = 6.dp)) {
                                Avatar(r.site, 32.dp)
                                Text(r.label, Modifier.weight(1f).padding(start = 10.dp))
                                Text("Open", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            "If not, \"$normSite\" is a different site and you can add it below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            GradientButton(
                "Add",
                enabled = normSite != null && normUser != null && ver != null && existing == null,
                onClick = { onOpen(session.add(normSite!!, normUser!!, ver!!, mode)) },
            )
        }
    }
}

@Composable
fun ModePicker(mode: String, onChange: (String) -> Unit) {
    val words = NexoPass.wordCount(mode)
    val chars = words == null
    val length = if (chars) mode.removePrefix("chars").toInt() else 20
    val count = words ?: 5
    Text("Password type", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !chars, onClick = { onChange(NexoPass.wordsMode(count)) }, label = { Text("Words") })
        FilterChip(selected = chars, onClick = { onChange("chars$length") }, label = { Text("Characters") })
    }
    if (chars) {
        Text(
            "$length random characters, for sites with a length limit",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = length.toFloat(),
            onValueChange = { onChange("chars${it.toInt()}") },
            valueRange = NexoPass.MIN_LENGTH.toFloat()..NexoPass.MAX_LENGTH.toFloat(),
            steps = NexoPass.MAX_LENGTH - NexoPass.MIN_LENGTH - 1,
        )
    } else {
        Text(
            "$count words, easy to type. More words, stronger password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = count.toFloat(),
            onValueChange = { onChange(NexoPass.wordsMode(it.toInt())) },
            valueRange = NexoPass.MIN_WORDS.toFloat()..NexoPass.MAX_WORDS.toFloat(),
            steps = NexoPass.MAX_WORDS - NexoPass.MIN_WORDS - 1,
        )
    }
}

// --- site detail ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(session: Session, site: String, user: String, onBack: () -> Unit) {
    val history = session.records.filter { it.site == site && it.user == user }.sortedByDescending { it.version }
    val current = history.firstOrNull { it.current }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var deleteVersion by remember { mutableStateOf<Rec?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val platform = LocalPlatform.current
    val seconds = session.prefs.clipSeconds
    val onCopy: (String) -> Unit = { pw ->
        platform.copySecret(pw, seconds)
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("Copied. Clipboard clears in $seconds s.") }
    }

    if (current == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Rounded.Delete, "Remove") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(site, 64.dp)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(site, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        if (user.isNotEmpty()) Text(user, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { PasswordCard(session, current, highlight = true, onCopy = onCopy) }
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Data breach?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Get a brand new password, the old one is archived.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        FilledTonalButton(onClick = { confirmRotate = true }) { Text("v${current.version + 1}") }
                    }
                }
            }
            val old = history.filter { !it.current }
            if (old.isNotEmpty()) {
                item { SectionTitle("Archive") }
                items(old, key = { it.version }) { PasswordCard(session, it, highlight = false, onCopy = onCopy, onDelete = { deleteVersion = it }) }
            }
        }
    }

    if (confirmRotate) {
        var mode by remember { mutableStateOf(current.mode) }
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            icon = { Icon(Icons.Rounded.Autorenew, null) },
            title = { Text("Switch to version ${current.version + 1}?") },
            text = {
                Column {
                    Text("Version ${current.version} goes to the archive. Then change the password on the site to the new one.")
                    Spacer(Modifier.height(12.dp))
                    ModePicker(mode) { mode = it }
                }
            },
            confirmButton = { TextButton(onClick = { session.rotate(current, mode); confirmRotate = false }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
        )
    }
    deleteVersion?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteVersion = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Delete v${r.version} from the archive?") },
            text = { Text("Only this old version disappears from the list. The current password doesn't change.") },
            confirmButton = { TextButton(onClick = { session.removeVersion(r); deleteVersion = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteVersion = null }) { Text("Cancel") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Remove ${current.label}?") },
            text = { Text("Removes the site and its archive from the list. The passwords themselves don't change, you can add the site again any time.") },
            confirmButton = { TextButton(onClick = { session.remove(current); confirmRemove = false; onBack() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PasswordCard(session: Session, r: Rec, highlight: Boolean, onCopy: (String) -> Unit, onDelete: (() -> Unit)? = null) {
    var pw by remember(r) { mutableStateOf<String?>(null) }
    var revealed by remember(r) { mutableStateOf(false) }
    LaunchedEffect(r) { pw = withContext(Dispatchers.Default) { session.password(r) } }

    val border = if (highlight) Modifier.border(1.5.dp, BrandBrush, MaterialTheme.shapes.large) else Modifier
    GlassCard(border, alpha = if (highlight) .85f else .55f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("v${r.version}", if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                modeLabel(r.mode) + " · " + if (r.current) "since ${r.since}" else "${r.since} → ${r.until}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = if (pw == null) 0 else if (revealed) 2 else 1,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
            label = "pw",
        ) { state ->
            when (state) {
                0 -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                2 -> Text(pw.orEmpty(), fontFamily = FontFamily.Monospace, fontSize = if (highlight) 19.sp else 16.sp, lineHeight = 26.sp)
                else -> Text("•".repeat(16), fontFamily = FontFamily.Monospace, fontSize = if (highlight) 19.sp else 16.sp, letterSpacing = 2.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(enabled = pw != null, onClick = { revealed = !revealed }) {
                Icon(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (revealed) "Hide" else "Show")
            }
            if (highlight) {
                Button(enabled = pw != null, onClick = { onCopy(pw!!) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy password")
                }
            } else {
                FilledTonalIconButton(enabled = pw != null, onClick = { onCopy(pw!!) }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                Spacer(Modifier.weight(1f))
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete this version") }
            }
        }
    }
}
