package com.nexoniarz.nexopass.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nexoniarz.nexopass.core.Export
import com.nexoniarz.nexopass.core.NexoPass
import com.nexoniarz.nexopass.core.Rec
import com.nexoniarz.nexopass.core.Vault
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The whole app: accounts on disk, the app unlock, and the keyring (every
 * account's vault key and master) while unlocked. Nothing secret is written
 * anywhere except encrypted.
 */
class AppState(val platform: Platform, val words: List<String>) {
    val prefs = platform.prefs
    val lock = platform.lock
    private val accountsDir = platform.accountsDir

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
    var migrated by mutableStateOf(platform.migrated)
        private set
    var firstRunCheck by mutableStateOf<String?>(null)

    private var keyring: MutableMap<String, Unlocked> = LinkedHashMap()
    private var innerKey: ByteArray? = null

    init {
        refreshAccounts()
    }

    val current get() = prefs.currentAccount

    fun refreshAccounts() {
        accounts = accountsDir.listFiles()
            ?.filter { File(it, platform.codec.fileName).exists() }
            ?.map { it.name }?.sorted() ?: emptyList()
        if (accounts.isNotEmpty() && current !in accounts) prefs.currentAccount = accounts.first()
    }

    private fun vault(name: String, key: ByteArray) = Vault(File(accountsDir, "$name/${platform.codec.fileName}"), key, platform.codec)

    /** After the PIN/password/pattern or biometrics. */
    fun unlockApp(r: UnlockResult.Ok) {
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
        if (!vault(name, u.vaultKey).load()) return false
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
        vault(name, u.vaultKey).save()
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
        val s = Session(name, vault(name, u.vaultKey.copyOf()), words, prefs, u.master)
        if (!s.load()) return false
        session?.close()
        session = s
        prefs.currentAccount = name
        return true
    }

    /** Slow (Argon2). */
    fun setupApp(method: String, secret: String) {
        innerKey = lock.setup(method, secret, keyring)
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
    fun checkMaster(name: String, master: ByteArray) = vault(name, NexoPass.vaultKey(master)).load()

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
            lock.wipe()
            lock()
        } else {
            openAccount(accounts.first())
        }
    }

    private fun saveKeyring() {
        innerKey?.let { lock.save(it, keyring) }
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
class Session(val name: String, private val vault: Vault, val words: List<String>, val prefs: Prefs, master: ByteArray) {
    private val master = master.copyOf()
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
