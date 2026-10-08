package com.nexoniarz.nexopass.android

import com.nexoniarz.nexopass.app.AppLock
import com.nexoniarz.nexopass.app.Prefs
import com.nexoniarz.nexopass.app.UnlockResult
import com.nexoniarz.nexopass.app.Unlocked
import com.nexoniarz.nexopass.core.NexoPass
import com.nexoniarz.nexopass.ui.methodName

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Every account's keys, encrypted under the app unlock. */
object Keyring {
    private const val SEP = '\u001f'

    fun encode(k: Map<String, Unlocked>) = k.entries.joinToString("\n") { (name, u) ->
        name + SEP + u.vaultKey.joinToString("") { "%02x".format(it) } + SEP + u.master.decodeToString()
    }.toByteArray()

    fun decode(b: ByteArray): MutableMap<String, Unlocked> = b.decodeToString().lines()
        .mapNotNull { line ->
            val f = line.split(SEP, limit = 3)
            if (f.size != 3 || f[1].length != 64) null
            else f[0] to Unlocked(f[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), f[2].toByteArray())
        }.toMap(LinkedHashMap())
}

/**
 * Phone app unlock (PIN, password or pattern, plus optional biometrics).
 *
 * quick.bin: salt, then the keyring encrypted twice. Inner layer: AES-GCM with
 * innerKey = Argon2id(secret, salt). Outer layer: AES-GCM with a key that lives
 * in the phone's secure hardware (Android Keystore) and never leaves it, so a
 * copied file is useless elsewhere. Wrong secrets are counted and wipe it.
 *
 * bio.bin: innerKey, encrypted with a Keystore key that only works right after
 * a fingerprint/face (or screen lock) check. New fingerprints invalidate it.
 */
class AndroidLock(private val activity: FragmentActivity, dir: File, private val prefs: Prefs) : AppLock {
    private val quickFile = File(dir, "quick.bin")
    private val bioFile = File(dir, "bio.bin")

    override val isSet get() = quickFile.exists()
    override val method: String get() = if (isSet) prefs.quickMethod else "none"
    override val bioMode: String get() = if (isSet && bioFile.exists()) prefs.bioMode else "off"
    override val methods = listOf("pin", "password", "pattern")

    override fun problem(method: String, secret: String) = when (method) {
        "pin" -> if (secret.length < 4) "Use at least 4 digits (6 is better)." else null
        "pattern" -> if (secret.length < 4) "Connect at least 4 dots." else null
        else -> if (secret.length < 6) "Use at least 6 characters." else null
    }

    /** New secret: new salt and key. Returns the inner key. Slow (Argon2). */
    override fun setup(method: String, secret: String, keyring: Map<String, Unlocked>): ByteArray {
        disableBio() // it holds the old inner key
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = innerKey(secret, salt)
        write(salt, key, keyring)
        prefs.quickMethod = method
        prefs.failedAttempts = 0
        return key
    }

    /** Re-encrypts the keyring after accounts changed. */
    override fun save(innerKey: ByteArray, keyring: Map<String, Unlocked>) {
        write(quickFile.readBytes().copyOfRange(0, 16), innerKey, keyring)
    }

    /** Slow (Argon2), run off the main thread. */
    override fun unlock(secret: String): UnlockResult {
        val data = quickFile.readBytes()
        val inner = outer(data) ?: run { wipe(); return UnlockResult.Wiped }
        val key = innerKey(secret, data.copyOfRange(0, 16))
        val plain = try {
            gcm(Cipher.DECRYPT_MODE, key, inner)
        } catch (e: AEADBadTagException) {
            prefs.failedAttempts += 1
            val left = prefs.maxAttempts - prefs.failedAttempts
            if (left <= 0) {
                wipe()
                return UnlockResult.Wiped
            }
            return UnlockResult.Wrong(left)
        }
        prefs.failedAttempts = 0
        return UnlockResult.Ok(key, Keyring.decode(plain))
    }

    private fun openWith(key: ByteArray): MutableMap<String, Unlocked>? {
        val inner = outer(quickFile.readBytes()) ?: return null
        return try { Keyring.decode(gcm(Cipher.DECRYPT_MODE, key, inner)) } catch (e: Exception) { null }
    }

    override fun wipe() {
        quickFile.delete()
        bioFile.delete()
        prefs.quickMethod = "none"
        prefs.bioMode = "off"
        prefs.failedAttempts = 0
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.run {
            for (a in listOf(ALIAS_QUICK, ALIAS_BIO)) if (containsAlias(a)) deleteEntry(a)
        }
    }

    override fun disableBio() {
        bioFile.delete()
        prefs.bioMode = "off"
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.run { if (containsAlias(ALIAS_BIO)) deleteEntry(ALIAS_BIO) }
    }

    /** Asks for a fingerprint/face (or screen lock) and stores the inner key under it. */
    override fun enableBio(mode: String, innerKey: ByteArray, done: (String?) -> Unit) {
        disableBio()
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, keystoreKey(ALIAS_BIO, mode)) }
        prompt(cipher, mode, "Turn on biometric unlock", "Cancel", onOk = { c ->
            bioFile.writeBytes(c.iv + c.doFinal(innerKey))
            prefs.bioMode = mode
            done(null)
        }, onError = done)
    }

    /** Fingerprint/face, then opens the keyring. Errors are "" when the user just cancelled. */
    override fun unlockBio(cancelText: String, onOk: (UnlockResult.Ok) -> Unit, onError: (String) -> Unit) {
        val data = bioFile.readBytes()
        val cipher = try {
            Cipher.getInstance(GCM).apply {
                init(Cipher.DECRYPT_MODE, keystoreKey(ALIAS_BIO, prefs.bioMode), GCMParameterSpec(128, data, 0, 12))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBio()
            onError("Biometrics changed on this phone, so biometric unlock was turned off. Turn it on again in Settings.")
            return
        }
        prompt(cipher, prefs.bioMode, "Unlock NexoPass", cancelText, onOk = { c ->
            val key = c.doFinal(data, 12, data.size - 12)
            val keyring = openWith(key)
            if (keyring == null) onError("Biometric unlock no longer matches. Use your ${methodName(method)}.")
            else onOk(UnlockResult.Ok(key, keyring))
        }, onError = onError)
    }

    private fun write(salt: ByteArray, key: ByteArray, keyring: Map<String, Unlocked>) {
        val inner = gcm(Cipher.ENCRYPT_MODE, key, Keyring.encode(keyring))
        val outer = Cipher.getInstance(GCM).run {
            init(Cipher.ENCRYPT_MODE, keystoreKey(ALIAS_QUICK, null))
            iv + doFinal(inner)
        }
        val tmp = File(quickFile.parentFile, "quick.tmp")
        tmp.writeBytes(salt + outer)
        check(tmp.renameTo(quickFile)) { "Could not save the app unlock" }
    }

    /** Removes the hardware layer; null if the hardware key is gone. */
    private fun outer(data: ByteArray): ByteArray? = try {
        Cipher.getInstance(GCM).run {
            init(Cipher.DECRYPT_MODE, keystoreKey(ALIAS_QUICK, null), GCMParameterSpec(128, data, 16, 12))
            doFinal(data, 28, data.size - 28)
        }
    } catch (e: Exception) {
        null
    }

    private fun prompt(
        cipher: Cipher,
        mode: String,
        title: String,
        cancelText: String,
        onOk: (Cipher) -> Unit,
        onError: (String) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try {
                    onOk(result.cryptoObject!!.cipher!!)
                } catch (e: Exception) {
                    onError("Biometric unlock failed: ${e.message}")
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelling is not an error worth showing.
                val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED
                onError(if (cancelled) "" else errString.toString())
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("NexoPass")
        if (mode == "device") {
            info.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        } else {
            info.setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText(cancelText)
        }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(info.build(), BiometricPrompt.CryptoObject(cipher))
    }

    private fun innerKey(secret: String, salt: ByteArray) =
        NexoPass.derive(secret.toByteArray(), "nexopass:quick:" + salt.joinToString("") { "%02x".format(it) }, 32)

    /** iv + ciphertext with a plain AES key. */
    private fun gcm(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance(GCM)
        return if (mode == Cipher.ENCRYPT_MODE) {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            iv + c.doFinal(data)
        } else {
            c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
            c.doFinal(data, 12, data.size - 12)
        }
    }

    private fun keystoreKey(alias: String, bioMode: String?): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (bioMode != null) {
            spec.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) {
                val types = if (bioMode == "device") {
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                } else {
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                }
                spec.setUserAuthenticationParameters(0, types)
            }
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec.build()) }
            .generateKey()
    }

    /** Which biometric options this phone supports. */
    override fun bioOptions(): List<String> {
        val bm = BiometricManager.from(activity)
        return buildList {
            if (bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) add("strong")
            if (Build.VERSION.SDK_INT >= 30 &&
                bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
            ) add("device")
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val GCM = "AES/GCM/NoPadding"
        private const val ALIAS_QUICK = "nexopass.quick"
        private const val ALIAS_BIO = "nexopass.bio"
    }
}
