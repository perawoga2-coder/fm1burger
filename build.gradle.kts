plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.example.fm1burger"
    compileSdk = 34

    defaultConfig {
        // Burger Studio is its own app, so it installs next to the old FM1 Burger (1.0) instead of over it
        applicationId = "com.example.burgerstudio.lab"   // 3.0 Lab: its own app, next to Burger Studio 2.x
        minSdk = 23
        targetSdk = 34
        versionCode = 33
        versionName = "3.3-lab"
    }

    // Same signing key for every build, so a new APK can be installed over the old one
    val fixedKey = file("debug.keystore")
    if (fixedKey.exists()) {
        signingConfigs {
            create("fixedDebug") {
                storeFile = fixedKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// the APK file carries the version: burgerstudio-v2.0-debug.apk
base {
    archivesName.set("burgerstudio-lab-v" + android.defaultConfig.versionName!!.removeSuffix("-lab"))
}
