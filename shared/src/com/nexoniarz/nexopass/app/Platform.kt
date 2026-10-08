package com.nexoniarz.nexopass.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.nexoniarz.nexopass.core.VaultCodec
import java.io.File

/** What opens one account: its vault key and its master. */
class Unlocked(val vaultKey: ByteArray, val master: ByteArray)

sealed interface UnlockResult {
    class Ok(val innerKey: ByteArray, val keyring: MutableMap<String, Unlocked>) : UnlockResult
    class Wrong(val attemptsLeft: Int) : UnlockResult
    /** Too many wrong attempts (or the stored data is gone): recover with a master. */
    object Wiped : UnlockResult
}

/**
 * The app unlock: a secret (PIN, password, pattern) that opens the keyring
 * holding every account's master. Phone and PC store it differently.
 */
interface AppLock {
    val isSet: Boolean
    /** pin, password, pattern; "none" when not set. */
    val method: String
    /** Methods this platform offers, best first. */
    val methods: List<String>
    /** Why [secret] is not acceptable for [method], or null. */
    fun problem(method: String, secret: String): String?

    /** New secret; returns the key that encrypts the keyring. Slow. */
    fun setup(method: String, secret: String, keyring: Map<String, Unlocked>): ByteArray
    /** Re-encrypts the keyring after accounts changed. */
    fun save(innerKey: ByteArray, keyring: Map<String, Unlocked>)
    /** Slow. Wrong guesses are counted; too many wipe the unlock. */
    fun unlock(secret: String): UnlockResult
    fun wipe()

    /** off, strong, device. */
    val bioMode: String get() = "off"
    fun bioOptions(): List<String> = emptyList()
    fun enableBio(mode: String, innerKey: ByteArray, done: (String?) -> Unit) = done("Not available")
    fun disableBio() {}
    /** Errors are "" when the user just cancelled. */
    fun unlockBio(cancelText: String, onOk: (UnlockResult.Ok) -> Unit, onError: (String) -> Unit) = onError("")
}

/** Everything that differs between Android and the PC. */
interface Platform {
    val lock: AppLock
    val codec: VaultCodec
    /** Folder with one sub-folder per account. */
    val accountsDir: File
    val prefs: Prefs
    /** True once after an older single-vault layout was moved into the account "main". */
    val migrated: Boolean
    /** Shown in About. */
    val aboutText: String
    /** PC: auto-lock never goes below this, so file dialogs don't lock the app. */
    val minAutoLockMinutes: Int get() = 0

    fun resource(name: String): ByteArray
    fun copySecret(text: String, seconds: Int)
    val animationsEnabled: Boolean

    @Composable fun BackHandler(enabled: Boolean, onBack: () -> Unit)
    /** Locks the app after the configured time away. */
    @Composable fun AutoLock(app: AppState)
    /** Returns a function that asks where to save, then writes [content]. */
    @Composable fun rememberExport(content: () -> ByteArray, onDone: (Boolean) -> Unit): (suggestedName: String) -> Unit
    /** Returns a function that asks for a file and passes its bytes (null if none/unreadable). */
    @Composable fun rememberImport(onBytes: (ByteArray?) -> Unit): () -> Unit
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("No platform") }
