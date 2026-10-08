package com.nexoniarz.nexopass

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
        val words = NexoPass.loadWords(assets.open("eff_large_wordlist.txt").use { it.readBytes() })
        val prefs = Prefs(this)
        val app = AppState(filesDir, words, prefs)
        setContent {
            val dark = when (prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            NexoTheme(dark) {
                ParticleBackground(dark = dark, animated = prefs.particles) { App(app) }
            }
        }
    }
}

/**
 * The whole app: accounts on disk, the app unlock, and the keyring (every
 * account's vault key and master) while unlocked. Nothing secret is written
 * anywhere except encrypted.
 */
class AppState(filesDir: File, val words: List<String>, val prefs: Prefs) {
    private val accountsDir = File(filesDir, "accounts")
    val quick = QuickUnlock(filesDir, prefs)

    var accounts by mutableStateOf(listOf<String>())
        private set
    var appUnlocked by mutableStateOf(false)
        private set
    /** Unlocked with a master (recovery, first run, update): an app unlock must be set now. */
    var needsAppSetup by mutableStateOf(false)
        private set
    var session by mutableStateOf<Session?>(null)
        private set
    /** Set when the account's master isn't in the keyring yet. */
    var needsMaster by mutableStateOf<String?>(null)
        private set
    /** True after moving a pre-accounts vault into the account "main". */
    var migrated by mutableStateOf(false)
        private set
    var firstRunCheck by mutableStateOf<String?>(null)

    private var keyring: MutableMap<String, Unlocked> = LinkedHashMap()
    private var innerKey: ByteArray? = null

    init {
        // 1.0/1.1 kept one vault in filesDir; it becomes the account "main".
        val old = File(filesDir, "vault.bin")
        if (old.exists() && !File(accountsDir, "main").exists()) {
            File(accountsDir, "main").mkdirs()
            old.renameTo(File(accountsDir, "main/vault.bin"))
            File(filesDir, "vault.bin.bak").takeIf { it.exists() }?.renameTo(File(accountsDir, "main/vault.bin.bak"))
            quick.wipe() // the old format held a single master
            prefs.currentAccount = "main"
            migrated = true
        }
        refreshAccounts()
    }

    val current get() = prefs.currentAccount

    fun refreshAccounts() {
        accounts = accountsDir.listFiles()?.filter { File(it, "vault.bin").exists() }?.map { it.name }?.sorted() ?: emptyList()
        if (accounts.isNotEmpty() && current !in accounts) prefs.currentAccount = accounts.first()
    }

    private fun vaultFile(name: String) = File(accountsDir, "$name/vault.bin")

    /** After the PIN/password/pattern or biometrics. */
    fun unlockApp(r: QuickUnlock.Result.Ok) {
        innerKey = r.innerKey
        keyring = r.keyring
        needsAppSetup = false
        openAccount(current)
        appUnlocked = true
    }

    /**
     * Opens an account with its master (recovery, update, or an account not in
     * the keyring yet). Slow. False when the master doesn't open it.
     */
    fun unlockWithMaster(name: String, master: ByteArray): Boolean {
        val u = Unlocked(NexoPass.vaultKey(master), master)
        if (!Vault(vaultFile(name), u.vaultKey).load()) return false
        keyring[name] = u
        if (innerKey == null) needsAppSetup = true else saveKeyring()
        needsMaster = null
        open(name, u)
        appUnlocked = true
        return true
    }

    /** New account: empty vault, master into the keyring. Slow. */
    fun createAccount(name: String, master: ByteArray) {
        val u = Unlocked(NexoPass.vaultKey(master), master)
        Vault(vaultFile(name), u.vaultKey).save()
        keyring[name] = u
        refreshAccounts()
        firstRunCheck = NexoPass.checkWords(master, words)
        if (innerKey == null) needsAppSetup = true else saveKeyring()
        needsMaster = null
        open(name, u)
        appUnlocked = true
    }

    fun openAccount(name: String) {
        prefs.currentAccount = name
        session?.close()
        session = null
        val u = keyring[name]
        needsMaster = if (u != null && open(name, u)) null else name
    }

    private fun open(name: String, u: Unlocked): Boolean {
        val s = Session(name, vaultFile(name), words, prefs, u)
        if (!s.load()) return false
        session?.close()
        session = s
        prefs.currentAccount = name
        return true
    }

    /** Slow (Argon2). */
    fun setupApp(method: String, secret: String) {
        innerKey = quick.setup(method, secret, keyring)
    }

    fun finishAppSetup() {
        needsAppSetup = false
    }

    fun innerKey() = innerKey!!

    fun masterOf(name: String) = keyring[name]?.master?.decodeToString()

    fun renameAccount(old: String, new: String) {
        session?.close()
        session = null
        File(accountsDir, old).renameTo(File(accountsDir, new))
        keyring.remove(old)?.let { keyring[new] = it }
        saveKeyring()
        refreshAccounts()
        openAccount(new)
    }

    /** True when [master] opens [name]. Slow. */
    fun checkMaster(name: String, master: ByteArray) = Vault(vaultFile(name), NexoPass.vaultKey(master)).load()

    fun detachAccount(name: String) {
        if (session?.name == name) {
            session?.close()
            session = null
        }
        File(accountsDir, name).deleteRecursively()
        keyring.remove(name)?.let { it.master.fill(0); it.vaultKey.fill(0) }
        saveKeyring()
        refreshAccounts()
        if (accounts.isEmpty()) {
            // Nothing left: start over.
            quick.wipe()
            lock()
        } else {
            openAccount(accounts.first())
        }
    }

    private fun saveKeyring() {
        innerKey?.let { quick.save(it, keyring) }
    }

    fun lock() {
        session?.close()
        session = null
        keyring.values.forEach { it.master.fill(0); it.vaultKey.fill(0) }
        keyring = LinkedHashMap()
        innerKey?.fill(0)
        innerKey = null
        needsMaster = null
        needsAppSetup = false
        appUnlocked = false
        migrated = false
    }
}

/** One open account. Nothing here is written to disk except its vault. */
class Session(val name: String, file: File, val words: List<String>, val prefs: Prefs, u: Unlocked) {
    private val master = u.master.copyOf()
    private val vault = Vault(file, u.vaultKey.copyOf())
    private val cache = ConcurrentHashMap<Rec, String>()
    var records by mutableStateOf(listOf<Rec>())
        private set

    fun load(): Boolean {
        if (!vault.load()) return false
        records = vault.records.toList()
        return true
    }

    fun close() {
        master.fill(0)
        cache.clear()
    }

    val current get() = records.filter { it.current }.sortedWith(compareBy({ it.site }, { it.user }))

    fun password(r: Rec) = cache.getOrPut(r) {
        NexoPass.password(master, words, r.site, r.user, r.version, r.mode)
    }

    fun checkWords() = NexoPass.checkWords(master, words)

    fun add(site: String, user: String, version: Int, mode: String) = edit { add(site, user, version, mode) }
    fun rotate(r: Rec, mode: String) = edit { rotate(r, mode) }
    fun remove(r: Rec) = edit { remove(r); r }
    fun removeVersion(r: Rec) = edit { removeVersion(r); r }

    /** Encrypted copy of the site list; slow (Argon2). */
    fun exportBytes(): ByteArray =
        Export.encrypt(NexoPass.exportPassphrase(master), Vault.text(vault.records).toByteArray())

    /** Merges an export into the list. Number of new entries, or null if it doesn't open. Slow. */
    fun importBytes(data: ByteArray): Int? {
        val plain = Export.decrypt(NexoPass.exportPassphrase(master), data) ?: return null
        val n = vault.mergeIn(Vault.parse(plain.decodeToString()))
        records = vault.records.toList()
        return n
    }

    private fun edit(block: Vault.() -> Rec): Rec {
        val r = vault.block()
        records = vault.records.toList()
        return r
    }
}

sealed interface Screen {
    data object List : Screen
    data object Add : Screen
    data object Settings : Screen
    data object NewAccount : Screen
    data class Detail(val site: String, val user: String) : Screen
}

@Composable
private fun App(app: AppState) {
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

    val stage = when {
        !app.appUnlocked -> 0
        app.needsAppSetup -> 1
        else -> 2
    }
    AnimatedContent(
        targetState = stage,
        transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = .96f)) togetherWith fadeOut(tween(200)) },
        label = "stage",
    ) { st ->
        when (st) {
            0 -> LockScreen(app)
            1 -> AppUnlockSetupScreen(app)
            else -> UnlockedApp(app)
        }
    }
}

@Composable
private fun UnlockedApp(app: AppState) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    BackHandler(screen != Screen.List) { screen = Screen.List }
    val needs = app.needsMaster
    val session = app.session
    if (screen == Screen.NewAccount) {
        NewAccountScreen(app, onBack = { screen = Screen.List }, onDone = { screen = Screen.List })
        return
    }
    if (needs != null || session == null) {
        AccountMasterScreen(app, needs ?: app.current, onAddAccount = { screen = Screen.NewAccount })
        return
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState != Screen.List
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "screen",
    ) { s ->
        when (s) {
            Screen.List -> ListScreen(
                app, session,
                onOpen = { screen = Screen.Detail(it.site, it.user) },
                onAdd = { screen = Screen.Add },
                onSettings = { screen = Screen.Settings },
                onNewAccount = { screen = Screen.NewAccount },
            )
            Screen.Add -> AddScreen(session, onBack = { screen = Screen.List }, onOpen = { screen = Screen.Detail(it.site, it.user) })
            Screen.Settings -> SettingsScreen(app, session, onBack = { screen = Screen.List })
            Screen.NewAccount -> {}
            is Screen.Detail -> DetailScreen(session, s.site, s.user, onBack = { screen = Screen.List })
        }
    }
}
