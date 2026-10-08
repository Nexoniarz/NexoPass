package com.nexoniarz.nexopass.app

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Where settings live: SharedPreferences on Android, the script's config files on the PC. */
interface KeyValue {
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, def: String): String
    fun putString(key: String, value: String)
    fun getBool(key: String, def: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
}

/** App settings. Nothing secret is stored here. Backed by Compose state so the UI follows changes. */
class Prefs(private val kv: KeyValue) {
    /** system, dark, light */
    var theme by StringPref("theme", "system")
    var particles by BoolPref("particles", true)
    /** Minutes in the background before the app locks; 0 = as soon as you leave. */
    var autoLockMinutes by IntPref("auto_lock", 5)
    var clipSeconds by IntPref("clip_seconds", 30)
    /** words, words<N> or chars<N> */
    var defaultMode by StringPref("default_mode", "words")
    var maxAttempts by IntPref("max_attempts", 5)
    var failedAttempts by IntPref("failed", 0)
    /** pin, password, pattern (Android; only meaningful while the unlock is set) */
    var quickMethod by StringPref("quick_method", "none")
    var currentAccount by StringPref("current_account", "main")
    /** off, strong (fingerprint/face), device (biometrics or phone screen lock) */
    var bioMode by StringPref("bio_mode", "off")

    private inner class IntPref(val key: String, def: Int) : ReadWriteProperty<Any?, Int> {
        private val state = mutableIntStateOf(kv.getInt(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.intValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            state.intValue = value
            kv.putInt(key, value)
        }
    }

    private inner class StringPref(val key: String, def: String) : ReadWriteProperty<Any?, String> {
        private val state = mutableStateOf(kv.getString(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            state.value = value
            kv.putString(key, value)
        }
    }

    private inner class BoolPref(val key: String, def: Boolean) : ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(kv.getBool(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            kv.putBool(key, value)
        }
    }
}
