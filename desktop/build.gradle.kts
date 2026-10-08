import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val appVersion = rootProject.extra["appVersion"] as String

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        kotlin.srcDir("../shared/src")
        resources.srcDir("../shared/resources")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpg-jdk18on:1.79")
    testImplementation(kotlin("test"))
}

tasks.test { maxHeapSize = "1g" }

compose.desktop {
    application {
        mainClass = "com.nexoniarz.nexopass.desktop.MainKt"
        // Argon2 for the app password uses 256 MiB.
        jvmArgs += listOf("-Xmx1g")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "NexoPass"
            packageVersion = appVersion
            vendor = "Nexoniarz"
            description = "Passwords derived from a master, nothing stored in plain text"
            copyright = "Copyright 2026 Nexoniarz. Apache License 2.0."
            licenseFile.set(rootProject.file("LICENSE"))
            // Bundled Java runtime: nothing to install, nothing taken from the system.
            modules("java.instrument", "java.naming", "java.sql", "jdk.unsupported", "jdk.crypto.ec")

            linux {
                packageName = "nexopass-gui"
                iconFile.set(project.file("icons/nexopass.png"))
                debMaintainer = "nexoniarz@users.noreply.github.com"
                menuGroup = "Utility"
                appCategory = "Utility"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("icons/nexopass.ico"))
                menu = true
                menuGroup = "NexoPass"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                // Keeps updates installing over older versions.
                upgradeUuid = "5d3b8f0e-6a2c-4b8e-9f3a-2c7d1e4b6a90"
            }
        }

        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// Bouncy Castle jars are signed; merged into one jar those signatures no
// longer match and Java refuses to start. The single jar doesn't need them.
tasks.matching { it.name.startsWith("packageUberJar") || it.name.startsWith("packageReleaseUberJar") }.configureEach {
    (this as org.gradle.jvm.tasks.Jar).exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
}
