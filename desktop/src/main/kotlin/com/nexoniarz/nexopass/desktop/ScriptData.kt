package com.nexoniarz.nexopass.desktop

import com.nexoniarz.nexopass.app.AppLock
import com.nexoniarz.nexopass.app.KeyValue
import com.nexoniarz.nexopass.app.Prefs
import com.nexoniarz.nexopass.app.UnlockResult
import com.nexoniarz.nexopass.app.Unlocked
import com.nexoniarz.nexopass.core.Export
import com.nexoniarz.nexopass.core.NexoPass
import com.nexoniarz.nexopass.core.hex
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/*
 * The PC app reads and writes exactly the files of the `nexopass` script, so
 * the window and the terminal share accounts, site lists and the app password:
 *
 *   <data>/accounts/<name>/vault.gpg   site list (OpenPGP, see PgpVaultCodec)
 *   <data>/keyring.gpg                 "name<0x1F>master" lines, OpenPGP
 *   <data>/keyring.meta                method, salt, failed tries
 *   <config>                           the script's settings (key=value)
 *   desktop.conf next to it            settings only the window uses
 */

object Paths {
    val windows = System.getProperty("os.name").startsWith("Windows")
    private val home = System.getProperty("user.home")

    val data: File = System.getenv("NEXOPASS_HOME")?.let(::File)
        ?: if (windows) File(System.getenv("APPDATA") ?: home, "NexoPass")
        else File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "nexopass")

    val config: File = System.getenv("NEXOPASS_CONFIG")?.let(::File)
        ?: if (windows) File(data, "config")
        else File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "nexopass/config")

    val desktopConfig = File(config.parentFile, "desktop.conf")
    val accounts = File(data, "accounts")
}

/** Owner-only permissions where the file system supports it (Linux). */
fun privateFile(f: File) {
    runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
}

/** Writes through a temp file so a crash never leaves half a file. */
fun writeAtomically(f: File, bytes: ByteArray) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeBytes(bytes)
    privateFile(tmp)
    if (!tmp.renameTo(f)) {
        f.delete()
        check(tmp.renameTo(f)) { "Could not save ${f.name}" }
    }
}

private fun readKeyValues(f: File): LinkedHashMap<String, String> {
    val map = LinkedHashMap<String, String>()
    if (f.exists()) f.readLines().forEach { line ->
        if (!line.startsWith("#") && '=' in line) map[line.substringBefore('=')] = line.substringAfter('=')
    }
    return map
}

/** Rewrites one key, keeping every other line (the script's own keys too). */
private fun writeKeyValue(f: File, key: String, value: String, header: String) {
    val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf(header)
    val i = lines.indexOfFirst { it.startsWith("$key=") }
    if (i >= 0) lines[i] = "$key=$value" else lines += "$key=$value"
    f.parentFile?.mkdirs()
    writeAtomically(f, (lines.joinToString("\n") + "\n").toByteArray())
}

/** Settings shared with the script go to its config file; the rest to desktop.conf. */
class ConfigKV : KeyValue {
    private val scriptKeys = setOf("clip_seconds", "default_mode", "max_attempts", "current_account")
    private fun file(key: String) = if (key in scriptKeys) Paths.config else Paths.desktopConfig
    private fun header(key: String) =
        if (key in scriptKeys) "# NexoPass settings, change with: nexopass settings" else "# NexoPass window settings"

    private fun get(key: String) = readKeyValues(file(key))[key]
    private fun put(key: String, value: String) = writeKeyValue(file(key), key, value, header(key))

    override fun getInt(key: String, def: Int) = get(key)?.toIntOrNull() ?: def
    override fun putInt(key: String, value: Int) = put(key, value.toString())
    override fun getString(key: String, def: String) = get(key) ?: def
    override fun putString(key: String, value: String) = put(key, value)
    override fun getBool(key: String, def: Boolean) = get(key)?.toBooleanStrictOrNull() ?: def
    override fun putBool(key: String, value: Boolean) = put(key, value.toString())
}

/**
 * The script's app password: keyring.gpg encrypted with
 * Argon2id(password, random salt; 4 passes, 256 MiB) as hex.
 */
class ScriptLock(private val prefs: Prefs) : AppLock {
    private val keyringFile = File(Paths.data, "keyring.gpg")
    private val metaFile = File(Paths.data, "keyring.meta")

    override val isSet get() = keyringFile.exists() && metaFile.exists()
    override val method get() = if (isSet) meta()["method"] ?: "password" else "none"
    override val methods = listOf("password", "pin")

    override fun problem(method: String, secret: String) = when (method) {
        "pin" -> if (!secret.matches(Regex("[0-9]{6,32}"))) "A PIN is 6 to 32 digits (this PC has no TPM chip, so short PINs are weak)." else null
        else -> if (secret.length < 8) "Use at least 8 characters." else null
    }

    private fun meta() = readKeyValues(metaFile)

    private fun writeMeta(method: String, salt: String, failed: Int) {
        writeAtomically(metaFile, "method=$method\nsalt=$salt\nfailed=$failed\n".toByteArray())
    }

    private fun key(secret: String, salt: String) =
        hex(NexoPass.argon2(secret.toByteArray(), salt, 4, 18, 32)).toByteArray()

    override fun setup(method: String, secret: String, keyring: Map<String, Unlocked>): ByteArray {
        val salt = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val k = key(secret, salt)
        writeMeta(method, salt, 0)
        save(k, keyring)
        return k
    }

    override fun save(innerKey: ByteArray, keyring: Map<String, Unlocked>) {
        val text = keyring.entries.joinToString("") { (name, u) -> name + "\u001f" + u.master.decodeToString() + "\n" }
        writeAtomically(keyringFile, Export.encrypt(innerKey.decodeToString().toCharArray(), text.toByteArray()))
    }

    override fun unlock(secret: String): UnlockResult {
        val m = meta()
        val k = key(secret, m["salt"] ?: "")
        val plain = Export.decrypt(k.decodeToString().toCharArray(), keyringFile.readBytes())
        if (plain == null) {
            val failed = (m["failed"]?.toIntOrNull() ?: 0) + 1
            val left = prefs.maxAttempts - failed
            if (left <= 0) {
                wipe()
                return UnlockResult.Wiped
            }
            writeMeta(m["method"] ?: "password", m["salt"] ?: "", failed)
            return UnlockResult.Wrong(left)
        }
        writeMeta(m["method"] ?: "password", m["salt"] ?: "", 0)
        // Vault keys aren't stored: derive them (a fraction of a second each).
        val keyring = LinkedHashMap<String, Unlocked>()
        plain.decodeToString().lines().filter { '\u001f' in it }.forEach { line ->
            val master = line.substringAfter('\u001f').toByteArray()
            keyring[line.substringBefore('\u001f')] = Unlocked(NexoPass.vaultKey(master), master)
        }
        return UnlockResult.Ok(k, keyring)
    }

    override fun wipe() {
        keyringFile.delete()
        metaFile.delete()
    }
}
