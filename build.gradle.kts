plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}

// One version for every app; CI passes -PappVersion=X.Y.Z from the git tag.
val appVersion = (findProperty("appVersion") as String?) ?: "1.3.1"
extra["appVersion"] = appVersion
