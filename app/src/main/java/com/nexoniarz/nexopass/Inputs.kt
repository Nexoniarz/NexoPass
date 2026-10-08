package com.nexoniarz.nexopass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// --- master as words ---------------------------------------------------------------------

/** Master typed as words: every space turns the word into a chip. Case never matters. */
class MasterState {
    val words: SnapshotStateList<String> = mutableStateListOf()
    var text by mutableStateOf("")

    fun onText(value: String) {
        val parts = value.split(' ', '\t', '\n')
        parts.dropLast(1).filter { it.isNotEmpty() }.forEach { words += it }
        text = parts.last()
    }

    fun value() = NexoPass.normalizeMaster((words + text).joinToString(" "))

    fun set(master: String) {
        words.clear()
        words += master.split(' ')
        text = ""
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MasterInput(state: MasterState, label: String, revealed: Boolean, onDone: () -> Unit) {
    Column {
        if (state.words.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                state.words.forEachIndexed { i, w ->
                    InputChip(
                        selected = false,
                        onClick = { state.words.removeAt(i) },
                        label = { Text(if (revealed) w.lowercase() else "•".repeat(w.length.coerceIn(3, 10))) },
                        trailingIcon = { Icon(Icons.Rounded.Close, "Remove word", Modifier.size(16.dp)) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = state::onText,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("type a word, then space") },
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            shape = MaterialTheme.shapes.medium,
        )
    }
}

// --- PIN ---------------------------------------------------------------------------------

@Composable
fun PinPad(onSubmit: (String) -> Unit, enabled: Boolean = true, clearKey: Any = Unit) {
    var pin by remember(clearKey) { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(20.dp)) {
            repeat(maxOf(4, pin.length)) { i ->
                Surface(
                    shape = CircleShape,
                    color = if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(14.dp),
                ) {}
            }
        }
        Spacer(Modifier.height(20.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("<", "0", "ok"))
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                for (k in row) {
                    when (k) {
                        "<" -> PadKey(icon = Icons.AutoMirrored.Rounded.Backspace, label = "Delete digit", enabled = enabled && pin.isNotEmpty()) { pin = pin.dropLast(1) }
                        "ok" -> PadKey(icon = Icons.Rounded.Check, label = "Confirm", enabled = enabled && pin.length >= 4, accent = true) {
                            val p = pin
                            pin = ""
                            onSubmit(p)
                        }
                        else -> PadKey(text = k, enabled = enabled && pin.length < 16) { pin += k }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(text: String? = null, icon: ImageVector? = null, label: String? = null, enabled: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (accent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .75f),
        modifier = Modifier.size(70.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (text != null) Text(text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            if (icon != null) Icon(icon, label)
        }
    }
}

// --- pattern -----------------------------------------------------------------------------

/** 3x3 pattern. Gives back the dots in order as digits 1-9, e.g. "1478". */
@Composable
fun PatternLock(onDone: (String) -> Unit, enabled: Boolean = true) {
    val path = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val done by rememberUpdatedState(onDone)
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.primary

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 300.dp)
        Canvas(
            Modifier
                .size(side)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    fun hit(o: Offset): Int? {
                        val cell = size.width / 3f
                        val col = (o.x / cell).toInt()
                        val row = (o.y / cell).toInt()
                        if (col !in 0..2 || row !in 0..2) return null
                        val c = Offset((col + .5f) * cell, (row + .5f) * cell)
                        return if ((o - c).getDistance() < cell * .32f) row * 3 + col else null
                    }
                    detectDragGestures(
                        onDragStart = { o ->
                            path.clear()
                            hit(o)?.let { path += it }
                            finger = o
                        },
                        onDrag = { change, _ ->
                            finger = change.position
                            hit(change.position)?.let { if (it !in path) path += it }
                        },
                        onDragEnd = {
                            val p = path.joinToString("") { (it + 1).toString() }
                            path.clear()
                            finger = null
                            if (p.isNotEmpty()) done(p)
                        },
                        onDragCancel = {
                            path.clear()
                            finger = null
                        },
                    )
                },
        ) {
            val cell = size.width / 3f
            fun center(i: Int) = Offset((i % 3 + .5f) * cell, (i / 3 + .5f) * cell)
            for (i in 1 until path.size) drawLine(line, center(path[i - 1]), center(path[i]), 10f, StrokeCap.Round)
            val f = finger
            if (f != null && path.isNotEmpty()) drawLine(line.copy(alpha = .5f), center(path.last()), f, 10f, StrokeCap.Round)
            for (i in 0 until 9) {
                val on = i in path
                drawCircle(if (on) line else dot.copy(alpha = .6f), radius = if (on) 18f else 11f, center = center(i))
                if (on) drawCircle(line.copy(alpha = .18f), radius = cell * .28f, center = center(i))
            }
        }
    }
}

// --- one entry for every quick-unlock method ----------------------------------------------

fun methodName(method: String) = when (method) {
    "pin" -> "PIN"
    "password" -> "password"
    "pattern" -> "pattern"
    else -> "master"
}

/** Asks once for a PIN, password or pattern. */
@Composable
fun SecretEntry(method: String, enabled: Boolean, clearKey: Any, onSubmit: (String) -> Unit) {
    when (method) {
        "pin" -> PinPad(onSubmit, enabled, clearKey)
        "pattern" -> PatternLock(onSubmit, enabled)
        else -> {
            var pw by remember(clearKey) { mutableStateOf("") }
            var shown by remember { mutableStateOf(false) }
            Column {
                OutlinedTextField(
                    pw, { pw = it }, Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pw.isNotEmpty()) onSubmit(pw) }),
                    trailingIcon = { TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") } },
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(12.dp))
                GradientButton("Continue", enabled = enabled && pw.isNotEmpty()) { onSubmit(pw) }
            }
        }
    }
}

/** Minimum for each method; null when fine. */
fun secretProblem(method: String, s: String) = when (method) {
    "pin" -> if (s.length < 4) "Use at least 4 digits (6 is better)." else null
    "pattern" -> if (s.length < 4) "Connect at least 4 dots." else null
    else -> if (s.length < 6) "Use at least 6 characters." else null
}

/** New PIN/password/pattern: enter it, then repeat it. */
@Composable
fun SecretSetupDialog(method: String, onCancel: () -> Unit, onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (first == null) "New ${methodName(method)}" else "Repeat ${methodName(method)}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (method == "pin") "Unlocks NexoPass on this phone. Your master keeps working too."
                    else "Used to unlock NexoPass on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
                SecretEntry(method, enabled = true, clearKey = attempt) { s ->
                    attempt++
                    val f = first
                    when {
                        f == null -> secretProblem(method, s)?.let { error = it } ?: run { first = s; error = null }
                        f != s -> { first = null; error = "They didn't match, start again." }
                        else -> onDone(s)
                    }
                }
                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun BioButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.clip(CircleShape)) {
        Icon(Icons.Rounded.Fingerprint, null)
        Spacer(Modifier.width(8.dp))
        Text("Use biometrics")
    }
}
