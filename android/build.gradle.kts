plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nexoniarz.nexopass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexoniarz.nexopass"
        minSdk = 26
        targetSdk = 35
        val v = rootProject.extra["appVersion"] as String
        versionName = v
        // 1.3.0 -> 10300
        versionCode = v.split(".").map { it.toInt() }.let { it[0] * 10000 + it[1] * 100 + it[2] }
    }

    sourceSets["main"].apply {
        java.srcDirs("src/main/java", "../shared/src")
        assets.srcDirs("../shared/resources")
    }

    signingConfigs {
        // CI signs with the same key as local builds (from secrets), so the
        // app updates in place. Without it, the debug key is used.
        create("release") {
            val ks = System.getenv("NEXOPASS_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("NEXOPASS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NEXOPASS_KEY_ALIAS")
                keyPassword = System.getenv("NEXOPASS_KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // Bouncy Castle jars ship the same OSGi/versioned manifests.
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric pulls an old fragment that breaks file pickers (requestCode crash)
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpg-jdk18on:1.79")
}
