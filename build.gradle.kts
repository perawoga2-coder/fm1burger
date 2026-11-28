plugins {
    id("com.android.application") version "8.10.1"    // (3.67: for Android 16, API 36)
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.example.fm1burger"
    compileSdk = 36

    defaultConfig {
        // Burger Studio is its own app, so it installs next to the old FM1 Burger (1.0) instead of over it
        // 3.66: the Maker Hip release key and a name of Maker Hip's own (registered with Google's developer verification);
        // it installs beside the Lab of before (com.example.burgerstudio.lab, the old key), whose work comes over by RESTORE
        applicationId = "com.makerhip.burgerstudio.lab"
        minSdk = 23
        targetSdk = 36                                // 3.67: Google Play asks new apps for Android 16
        versionCode = 97
        versionName = "3.67-lab"
    }

    // 3.66: every build is signed with the Maker Hip release key (below), the debug build too. (The old fixed debug
    // key, until 3.65, only for the FM1 Burger 1.0 rebuild in CI)
    val fixedKey = file("debug.keystore")
    if (fixedKey.exists() && file("release.jks").exists().not()) {
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
            getByName("debug") {
                signingConfig = signingConfigs.getByName("makerhip")
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
