package com.nexoniarz.nexopass.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nexoniarz.nexopass.app.AppState
import com.nexoniarz.nexopass.app.LocalPlatform
import com.nexoniarz.nexopass.app.Platform
import com.nexoniarz.nexopass.app.Prefs
import com.nexoniarz.nexopass.core.NexoPass
import com.nexoniarz.nexopass.core.PgpVaultCodec
import com.nexoniarz.nexopass.ui.App
import com.nexoniarz.nexopass.ui.NexoTheme
import com.nexoniarz.nexopass.ui.ParticleBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.io.File
import java.util.Timer
import kotlin.concurrent.schedule

fun main() = application {
    val platform = remember { DesktopPlatform() }
    val app = remember { AppState(platform, NexoPass.loadWords(platform.resource("eff_large_wordlist.txt"))) }
    val state = rememberWindowState(size = DpSize(500.dp, 880.dp), position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = ::exitApplication,
        title = "NexoPass",
        state = state,
        icon = painterResource("nexopass.png"),
        onPreviewKeyEvent = { e ->
            // Esc goes back, like the back gesture on the phone.
            e.key == Key.Escape && e.type == KeyEventType.KeyDown && platform.back()
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(420, 640)
            platform.window = window
        }
        val dark = when (platform.prefs.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
        CompositionLocalProvider(LocalPlatform provides platform) {
            NexoTheme(dark) {
                ParticleBackground(dark = dark, animated = platform.prefs.particles) {
                    // Phone-sized column in the middle of a wide window.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 640.dp).fillMaxHeight()) { App(app) }
                    }
                }
            }
        }
    }
}

class DesktopPlatform : Platform {
    override val prefs = Prefs(ConfigKV())
    override val lock = ScriptLock(prefs)
    override val codec = PgpVaultCodec
    override val accountsDir: File = Paths.accounts
    override val migrated = migrate()
    override val minAutoLockMinutes = 1
    override val animationsEnabled = true
    override val aboutText =
        if (Paths.windows) "Same passwords as the phone app and the nexopass command on Linux."
        else "Shares accounts, site lists and the app password with the nexopass command. Same passwords as the phone app."

    var window: Frame? = null
    private var onBack: (() -> Unit)? = null

    fun back(): Boolean {
        val b = onBack ?: return false
        b()
        return true
    }

    /** Older nexopass kept one vault in the data folder; it becomes the account "main" (like the script does). */
    private fun migrate(): Boolean {
        val old = File(Paths.data, "vault.gpg")
        if (!old.exists() || File(Paths.accounts, "main").exists()) return false
        File(Paths.accounts, "main").mkdirs()
        old.renameTo(File(Paths.accounts, "main/vault.gpg"))
        File(Paths.data, "vault.gpg.bak").takeIf { it.exists() }?.renameTo(File(Paths.accounts, "main/vault.gpg.bak"))
        File(Paths.data, "quick.gpg").delete()
        File(Paths.data, "quick.meta").delete()
        return true
    }

    override fun resource(name: String): ByteArray =
        DesktopPlatform::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() }

    override fun copySecret(text: String, seconds: Int) {
        val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(SecretSelection(text), null)
        Timer(true).schedule(seconds * 1000L) {
            runCatching {
                if (clipboard.getData(DataFlavor.stringFlavor) == text) clipboard.setContents(StringSelection(""), null)
            }
        }
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        val current by rememberUpdatedState(onBack)
        DisposableEffect(enabled) {
            val handler = { current() }
            if (enabled) this@DesktopPlatform.onBack = handler
            onDispose { if (this@DesktopPlatform.onBack === handler) this@DesktopPlatform.onBack = null }
        }
    }

    @Composable
    override fun AutoLock(app: AppState) {
        val focused by rememberUpdatedState(LocalWindowInfo.current.isWindowFocused)
        LaunchedEffect(Unit) {
            var awaySince = 0L
            while (true) {
                delay(2_000)
                val limit = maxOf(prefs.autoLockMinutes, minAutoLockMinutes) * 60_000L
                if (focused) {
                    awaySince = 0
                } else {
                    if (awaySince == 0L) awaySince = System.currentTimeMillis()
                    if (app.appUnlocked && !app.needsAppSetup && System.currentTimeMillis() - awaySince >= limit) app.lock()
                }
            }
        }
    }

    @Composable
    override fun rememberExport(content: () -> ByteArray, onDone: (Boolean) -> Unit): (String) -> Unit {
        val scope = rememberCoroutineScope()
        return { name ->
            val d = FileDialog(window, "Export site list", FileDialog.SAVE).apply {
                file = name
                isVisible = true
            }
            if (d.file != null) {
                val target = File(d.directory, d.file)
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { writeAtomically(target, content()) }.isSuccess }
                    onDone(ok)
                }
            }
        }
    }

    @Composable
    override fun rememberImport(onBytes: (ByteArray?) -> Unit): () -> Unit = {
        val d = FileDialog(window, "Import site list", FileDialog.LOAD).apply { isVisible = true }
        if (d.file != null) onBytes(runCatching { File(d.directory, d.file).readBytes() }.getOrNull())
    }
}

/**
 * Plain text plus KDE's password hint, so Klipper keeps the password out of
 * its clipboard history. Other desktops just see text.
 */
private class SecretSelection(private val text: String) : Transferable {
    private val hint = DataFlavor("application/x-kde-passwordManagerHint;class=java.io.InputStream")

    init {
        (SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap).apply {
            addUnencodedNativeForFlavor(hint, "x-kde-passwordManagerHint")
            addFlavorForUnencodedNative("x-kde-passwordManagerHint", hint)
        }
    }

    override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor, hint)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor || flavor == hint
    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        DataFlavor.stringFlavor -> text
        else -> "secret".byteInputStream()
    }
}
