package com.nexoniarz.nexopass

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** App settings. Nothing secret is stored here. Backed by Compose state so the UI follows changes. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nexopass", Context.MODE_PRIVATE)

    /** system, dark, light */
    var theme by StringPref("theme", "system")
    var particles by BoolPref("particles", true)
    /** Minutes in the background before the app locks; 0 = as soon as you leave. */
    var autoLockMinutes by IntPref("auto_lock", 5)
    var clipSeconds by IntPref("clip_seconds", 30)
    /** words, or chars<N> */
    var defaultMode by StringPref("default_mode", "words")
    var maxAttempts by IntPref("max_attempts", 5)
    var failedAttempts by IntPref("failed", 0)
    /** pin, password, pattern (only meaningful while quick.bin exists) */
    var quickMethod by StringPref("quick_method", "none")
    var currentAccount by StringPref("current_account", "main")
    /** off, strong (fingerprint/face), device (biometrics or phone screen lock) */
    var bioMode by StringPref("bio_mode", "off")

    private inner class IntPref(val key: String, def: Int) : ReadWriteProperty<Any?, Int> {
        private val state = mutableIntStateOf(sp.getInt(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.intValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            state.intValue = value
            sp.edit().putInt(key, value).apply()
        }
    }

    private inner class StringPref(val key: String, def: String) : ReadWriteProperty<Any?, String> {
        private val state = mutableStateOf(sp.getString(key, def) ?: def)
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            state.value = value
            sp.edit().putString(key, value).apply()
        }
    }

    private inner class BoolPref(val key: String, def: Boolean) : ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(sp.getBoolean(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            sp.edit().putBoolean(key, value).apply()
        }
    }
}
