package com.nexoniarz.nexopass.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexoniarz.nexopass.app.AppState
import com.nexoniarz.nexopass.app.KeyValue
import com.nexoniarz.nexopass.app.LocalPlatform
import com.nexoniarz.nexopass.app.Platform
import com.nexoniarz.nexopass.app.Prefs
import com.nexoniarz.nexopass.core.AesVaultCodec
import com.nexoniarz.nexopass.core.NexoPass
import com.nexoniarz.nexopass.ui.App
import com.nexoniarz.nexopass.ui.NexoTheme
import com.nexoniarz.nexopass.ui.ParticleBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// FragmentActivity because the system biometric prompt needs one.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No screenshots, no preview in the recent apps screen. Debug builds allow
        // screenshots so the UI can be checked during development.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        val platform = AndroidPlatform(this)
        val app = AppState(platform, NexoPass.loadWords(platform.resource("eff_large_wordlist.txt")))
        setContent {
            val dark = when (platform.prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            CompositionLocalProvider(LocalPlatform provides platform) {
                NexoTheme(dark) {
                    ParticleBackground(dark = dark, animated = platform.prefs.particles) { App(app) }
                }
            }
        }
    }
}

private class SharedPrefsKV(private val sp: SharedPreferences) : KeyValue {
    override fun getInt(key: String, def: Int) = sp.getInt(key, def)
    override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    override fun getString(key: String, def: String) = sp.getString(key, def) ?: def
    override fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()
    override fun getBool(key: String, def: Boolean) = sp.getBoolean(key, def)
    override fun putBool(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
}

class AndroidPlatform(private val activity: FragmentActivity) : Platform {
    override val prefs = Prefs(SharedPrefsKV(activity.getSharedPreferences("nexopass", Context.MODE_PRIVATE)))
    override val accountsDir = File(activity.filesDir, "accounts")
    override val codec = AesVaultCodec
    override val lock = AndroidLock(activity, activity.filesDir, prefs)
    override val migrated = migrate()
    override val aboutText = "Same passwords as nexopass on the PC. Nothing leaves this phone: the app has no internet permission."

    /** 1.0/1.1 kept one vault in filesDir; it becomes the account "main". */
    private fun migrate(): Boolean {
        val old = File(activity.filesDir, "vault.bin")
        if (!old.exists() || File(accountsDir, "main").exists()) return false
        File(accountsDir, "main").mkdirs()
        old.renameTo(File(accountsDir, "main/vault.bin"))
        File(activity.filesDir, "vault.bin.bak").takeIf { it.exists() }?.renameTo(File(accountsDir, "main/vault.bin.bak"))
        lock.wipe() // the old format held a single master
        prefs.currentAccount = "main"
        return true
    }

    override fun resource(name: String) = activity.assets.open(name).use { it.readBytes() }

    override val animationsEnabled =
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    /** Copies without a clipboard preview or keyboard history, and clears it after [seconds]. */
    override fun copySecret(text: String, seconds: Int) {
        val cm = activity.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("NexoPass", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }, seconds * 1000L)
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
        androidx.activity.compose.BackHandler(enabled, onBack)

    @Composable
    override fun AutoLock(app: AppState) {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            var stoppedAt = 0L
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> stoppedAt = SystemClock.elapsedRealtime()
                    Lifecycle.Event.ON_START -> {
                        val limit = app.prefs.autoLockMinutes * 60_000L
                        // Never lock in the middle of the required app-unlock setup.
                        if (stoppedAt > 0 && !app.needsAppSetup && SystemClock.elapsedRealtime() - stoppedAt >= limit) app.lock()
                    }
                    else -> {}
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    @Composable
    override fun rememberExport(content: () -> ByteArray, onDone: (Boolean) -> Unit): (String) -> Unit {
        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pgp-encrypted")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { activity.contentResolver.openOutputStream(uri)!!.use { it.write(content()) } }.isSuccess
                }
                onDone(ok)
            }
        }
        return { launcher.launch(it) }
    }

    @Composable
    override fun rememberImport(onBytes: (ByteArray?) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            onBytes(runCatching { activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull())
        }
        return { launcher.launch(arrayOf("*/*")) }
    }
}
