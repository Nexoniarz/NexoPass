package com.nexoniarz.nexopass.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Must stay byte-for-byte compatible with the nexopass bash script.
object NexoPass {
    const val WORDLIST_SHA256 = "addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e"

    // Changing any of these changes every password.
    private const val SALT_PREFIX = "nexopass:v1"
    private const val ARGON_T = 3
    private const val ARGON_M_POW = 16 // 2^16 KiB = 64 MiB
    private const val ARGON_P = 1
    private const val SITE_BYTES = 1024
    private const val WORD_COUNT = 5 // "words" means 5; other counts are "words<N>"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%&*?-_+="
    private val TWO_PART_TLDS = setOf(
        "com.pl", "net.pl", "org.pl", "edu.pl", "gov.pl", "info.pl", "biz.pl",
        "co.uk", "org.uk", "ac.uk", "com.au", "co.jp", "com.br",
    )

    const val MAX_MASTER_BYTES = 127
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 64
    const val MIN_WORDS = 5
    const val MAX_WORDS = 15

    /** Mode string for a number of words. 5 stays "words" so older entries keep their passwords. */
    fun wordsMode(n: Int) = if (n == WORD_COUNT) "words" else "words$n"

    /** Number of words for a words mode, or null for a characters mode. */
    fun wordCount(mode: String) = when {
        mode == "words" -> WORD_COUNT
        mode.startsWith("words") -> mode.removePrefix("words").toInt()
        else -> null
    }

    fun loadWords(bytes: ByteArray): List<String> {
        val sum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(sum == WORDLIST_SHA256) { "Word list differs from the EFF original" }
        return bytes.decodeToString().lines().filter { it.isNotEmpty() }.map { it.substringAfter('\t') }
    }

    private fun isAsciiSpace(c: Char) = c == ' ' || c in '\t'..'\r'
    private fun asciiLower(s: String) = String(CharArray(s.length) { s[it].let { c -> if (c in 'A'..'Z') c + 32 else c } })

    /** 'https://www.Allegro.pl/konto' -> 'allegro', 'Discord' -> 'discord', invalid -> null. */
    fun normalizeSite(raw: String): String? {
        var s = asciiLower(raw).trim(::isAsciiSpace)
        if ("://" in s) s = s.substringAfter("://")
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        s = s.substringAfterLast('@').substringBefore(':').removePrefix("www.").removeSuffix(".")
        if (s.isEmpty() || s.startsWith(".") || s.endsWith(".") || ".." in s) return null
        val l = s.split('.')
        val n = l.size
        s = when {
            n >= 3 && "${l[n - 2]}.${l[n - 1]}" in TWO_PART_TLDS -> l[n - 3]
            n >= 2 -> l[n - 2]
            else -> l[0]
        }
        return s.takeIf { it.isNotEmpty() && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }
    }

    /** Lowercase per code point, like the script's towlower(). */
    private fun lower(s: String): String {
        val sb = StringBuilder(s.length)
        s.codePoints().forEach { sb.appendCodePoint(Character.toLowerCase(it)) }
        return sb.toString()
    }

    fun normalizeUser(raw: String): String? {
        val u = lower(raw).trim(::isAsciiSpace)
        return u.takeIf { it.none { c -> c < ' ' || c == '\u007f' } }
    }

    /** Lowercase, and any run of spaces/tabs/newlines becomes a single space. */
    fun normalizeMaster(raw: String): String =
        lower(raw).split(' ', '\t', '\n').filter { it.isNotEmpty() }.joinToString(" ")

    const val MIN_MASTER_WORDS = 3
    const val MIN_MASTER_BYTES = 12

    enum class Strength(val label: String) {
        TOO_SHORT("Too short: at least 3 words, 12 characters"),
        MINIMUM("3 words: minimum"),
        RECOMMENDED("4 words: recommended"),
        STRONG("5 words: strong"),
        SAFEST("6+ words: safest"),
    }

    /** Same rule as the script. Only used when a vault is created. */
    fun masterStrength(master: String): Strength {
        val n = master.split(' ').count { it.isNotEmpty() }
        return when {
            n < MIN_MASTER_WORDS || master.toByteArray().size < MIN_MASTER_BYTES -> Strength.TOO_SHORT
            n == 3 -> Strength.MINIMUM
            n == 4 -> Strength.RECOMMENDED
            n == 5 -> Strength.STRONG
            else -> Strength.SAFEST
        }
    }

    /** Edit distance, a swap of two neighbouring letters counts as one. */
    fun distance(a: String, b: String): Int {
        val w = b.length + 1
        val d = IntArray((a.length + 1) * w)
        for (i in 0..a.length) d[i * w] = i
        for (j in 0..b.length) d[j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[(i - 1) * w + j] + 1, d[i * w + j - 1] + 1, d[(i - 1) * w + j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[(i - 2) * w + j - 2] + 1)
            d[i * w + j] = v
        }
        return d[a.length * w + b.length]
    }

    /** Sites close to [site]; empty when [site] itself is on the list. */
    fun suggestions(site: String, user: String, current: List<Rec>): List<Rec> {
        if (current.any { it.site == site && it.user == user }) return emptyList()
        val limit = if (site.length <= 4) 1 else 2
        return current.asSequence()
            .filter { kotlin.math.abs(it.site.length - site.length) <= limit }
            .map { it to distance(site, it.site) }
            .filter { it.second <= limit }
            .sortedBy { it.second }
            .map { it.first }
            .take(5)
            .toList()
    }

    fun derive(master: ByteArray, salt: String, bytes: Int) = argon2(master, salt, ARGON_T, ARGON_M_POW, bytes)

    /** Argon2id like the `argon2` command: salt as text, memory 2^[memPow] KiB, one lane. */
    fun argon2(password: ByteArray, salt: String, iterations: Int, memPow: Int, bytes: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryPowOfTwo(memPow)
            .withParallelism(ARGON_P)
            .withSalt(salt.toByteArray())
            .build()
        val out = ByteArray(bytes)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password, out)
        return out
    }

    /** Consumes 32-bit numbers from derived bytes, the same way the script does. */
    private class Picker(private val data: ByteArray) {
        private var pos = 0
        fun pick(n: Int): Int {
            val limit = (1L shl 32) - (1L shl 32) % n
            while (pos + 4 <= data.size) {
                var x = 0L
                for (k in 0 until 4) x = (x shl 8) or (data[pos + k].toLong() and 0xff)
                pos += 4
                if (x < limit) return (x % n).toInt()
            }
            error("Ran out of random data")
        }
    }

    fun password(master: ByteArray, words: List<String>, site: String, user: String, version: Int, mode: String): String {
        val p = Picker(derive(master, "$SALT_PREFIX:site:$site:$version:$mode:$user", SITE_BYTES))
        val count = wordCount(mode)
        if (count != null) {
            val chosen = List(count) { words[p.pick(words.size)].replaceFirstChar { it.uppercaseChar() } }
            return chosen.joinToString("-") + DIGITS[p.pick(DIGITS.length)]
        }
        val len = mode.removePrefix("chars").toInt()
        val all = UPPER + LOWER + DIGITS + SYMBOLS
        val c = ArrayList<Char>(len)
        for (group in listOf(UPPER, LOWER, DIGITS, SYMBOLS)) c += group[p.pick(group.length)]
        repeat(len - 4) { c += all[p.pick(all.length)] }
        for (i in len - 1 downTo 1) {
            val j = p.pick(i + 1)
            val t = c[i]; c[i] = c[j]; c[j] = t
        }
        return c.joinToString("")
    }

    /** Two words that are always the same for the same master, on every device. */
    fun checkWords(master: ByteArray, words: List<String>): String {
        val p = Picker(derive(master, "$SALT_PREFIX:check", 32))
        return words[p.pick(words.size)] + " " + words[p.pick(words.size)]
    }

    fun vaultKey(master: ByteArray) = derive(master, "$SALT_PREFIX:vault", 32)

    /** OpenPGP passphrase for export files, the same one the script uses. */
    fun exportPassphrase(master: ByteArray) = hex(derive(master, "$SALT_PREFIX:export", 32)).toCharArray()

    fun newMaster(words: List<String>, count: Int = 6): String {
        val rnd = SecureRandom()
        return List(count) { words[rnd.nextInt(words.size)] }.joinToString(" ")
    }
}

/** One version of one site. until == "" means it is the current version. */
data class Rec(
    val site: String,
    val user: String,
    val version: Int,
    val mode: String,
    val since: String,
    val until: String = "",
) {
    val current get() = until.isEmpty()
    val label get() = if (user.isEmpty()) "$site v$version" else "$site ($user) v$version"
    fun same(o: Rec) = site == o.site && user == o.user
}

/** How one account's site list is turned into bytes on disk. */
interface VaultCodec {
    /** File name inside the account folder. */
    val fileName: String
    fun encode(key: ByteArray, text: String): ByteArray
    /** null when the key is wrong or the data is damaged. */
    fun decode(key: ByteArray, data: ByteArray): String?
}

/** Phone format: AES-256-GCM, "NXP1" + IV + ciphertext. */
object AesVaultCodec : VaultCodec {
    private val MAGIC = "NXP1".toByteArray()
    override val fileName = "vault.bin"

    override fun encode(key: ByteArray, text: String): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return MAGIC + iv + cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(text.toByteArray())
    }

    override fun decode(key: ByteArray, data: ByteArray): String? {
        if (data.size < MAGIC.size + 12 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        return try {
            cipher(Cipher.DECRYPT_MODE, key, data.copyOfRange(MAGIC.size, MAGIC.size + 12))
                .doFinal(data, MAGIC.size + 12, data.size - MAGIC.size - 12).decodeToString()
        } catch (e: javax.crypto.AEADBadTagException) {
            null
        }
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        updateAAD(MAGIC)
    }
}

/** Script format: OpenPGP (AES-256), passphrase = the vault key as hex, like `gpg` in nexopass. */
object PgpVaultCodec : VaultCodec {
    override val fileName = "vault.gpg"
    override fun encode(key: ByteArray, text: String) = Export.encrypt(hex(key).toCharArray(), text.toByteArray())
    override fun decode(key: ByteArray, data: ByteArray) = Export.decrypt(hex(key).toCharArray(), data)?.decodeToString()
}

fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/**
 * Site list with version archive, encrypted with a key derived from the
 * master. Holds no passwords, only what is needed to derive them again.
 */
class Vault(private val file: File, private val key: ByteArray, private val codec: VaultCodec) {
    val records = mutableListOf<Rec>()

    fun exists() = file.exists()

    /** Returns false when the key is wrong (or the file is damaged). */
    fun load(): Boolean {
        records.clear()
        if (!file.exists()) return true
        val text = codec.decode(key, file.readBytes()) ?: return false
        records += parse(text)
        return true
    }

    fun save() {
        val out = codec.encode(key, text(records))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        if (file.exists()) file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true)
        check(tmp.renameTo(file)) { "Could not save the vault" }
    }

    fun current(site: String, user: String) = records.firstOrNull { it.site == site && it.user == user && it.current }

    fun currentList() = records.filter { it.current }.sortedWith(compareBy({ it.site }, { it.user }))

    fun history(site: String, user: String) =
        records.filter { it.site == site && it.user == user }.sortedBy { it.version }

    fun add(site: String, user: String, version: Int, mode: String): Rec {
        check(current(site, user) == null) { "Already on the list" }
        return Rec(site, user, version, mode, today()).also { records += it; save() }
    }

    fun rotate(old: Rec, newMode: String = old.mode): Rec {
        val i = records.indexOf(old)
        check(i >= 0 && old.current)
        records[i] = old.copy(until = today())
        return Rec(old.site, old.user, old.version + 1, newMode, today()).also { records += it; save() }
    }

    /** Deletes one archived version; the current one stays. */
    fun removeVersion(r: Rec) {
        check(!r.current)
        records.remove(r)
        save()
    }

    fun remove(r: Rec) {
        records.removeAll { it.same(r) }
        save()
    }

    /** Adds what [other] knows; returns how many site versions were new. */
    fun mergeIn(other: List<Rec>): Int {
        val before = records.map { Triple(it.site, it.user, it.version) }.toSet()
        val merged = merge(records, other)
        records.clear()
        records += merged
        save()
        return merged.count { Triple(it.site, it.user, it.version) !in before }
    }

    private fun today() = LocalDate.now().toString()

    companion object {
        private const val HEADER = "# nexopass vault v1"
        private const val SEP = '\u001f'

        /** Plain text form, shared by the vault, the script and export files. */
        fun text(records: List<Rec>) = buildString {
            append(HEADER).append('\n')
            for (r in records) {
                append(listOf(r.site, r.user, r.version, r.mode, r.since, r.until).joinToString(SEP.toString())).append('\n')
            }
        }

        fun parse(text: String): List<Rec> = text.lines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val f = line.split(SEP)
                val v = f.getOrNull(2)?.toIntOrNull()
                if (f.size < 5 || v == null || NexoPass.normalizeSite(f[0]) != f[0]) null
                else Rec(f[0], f[1], v, f[3], f[4], f.getOrElse(5) { "" })
            }

        /**
         * Union of both lists. Per site and account the highest version is the
         * current one, every lower version is archived.
         */
        fun merge(a: List<Rec>, b: List<Rec>): List<Rec> =
            (a + b).groupBy { it.site to it.user }.values.flatMap { group ->
                val byVersion = group.groupBy { it.version }.toSortedMap()
                    .mapValues { (_, same) -> same.firstOrNull { !it.current } ?: same.first() }
                val versions = byVersion.keys.toList()
                versions.mapIndexed { i, v ->
                    val r = byVersion.getValue(v)
                    when {
                        i == versions.lastIndex -> r.copy(until = "")
                        r.current -> r.copy(until = byVersion.getValue(versions[i + 1]).since)
                        else -> r
                    }
                }
            }.sortedWith(compareBy({ it.site }, { it.user }, { it.version }))
    }
}
