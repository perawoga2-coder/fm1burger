plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.example.fm1burger"
    compileSdk = 34

    defaultConfig {
        // Burger Studio is its own app, so it installs next to the old FM1 Burger (1.0) instead of over it
        applicationId = "com.example.burgerstudio.lab"   // 3.0 Lab: its own app, next to Burger Studio 2.x (3.43: back on it; the release app waits)
        minSdk = 23
        targetSdk = 34
        versionCode = 84
        versionName = "3.54-lab"
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

    // 3.41: the Maker Hip release key (the same for Burger Studio and 16 Track, so INBOX / → MIX works between them).
    // CI writes release.jks from the RELEASE_KEYSTORE_B64 secret and passes the password in RELEASE_PASSWORD;
    // the key itself is never in the repository.
    val relKey = file("release.jks")
    val relPw = System.getenv("RELEASE_PASSWORD")
    if (relKey.exists() && !relPw.isNullOrEmpty()) {
        signingConfigs {
            create("makerhip") {
                storeFile = relKey
                storePassword = relPw
                keyAlias = "makerhip"
                keyPassword = relPw
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("makerhip")
                isMinifyEnabled = false
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

// the APK file carries the version: burgerstudio-lab-v3.43-debug.apk
base {
    archivesName.set("burgerstudio-lab-v" + android.defaultConfig.versionName!!.removeSuffix("-lab"))
}
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "burgerstudio-lab-v" + versionName.removeSuffix("-lab") + (if (buildType.name == "release") "" else "-" + buildType.name) + ".apk"
    }
}
