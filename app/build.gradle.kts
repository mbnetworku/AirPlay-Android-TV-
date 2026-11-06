plugins {
    id("com.android.application")
}

// The app version: change only this line to release an update. Pushing it to main makes
// GitHub Actions publish a "v<version>" release, and installed apps then offer the update.
val appVersion = "1.20"

// versionCode must grow with every release; derive it from the version so it can't be forgotten
// (1.18 -> 1018, 2.0 -> 2000).
val appVersionCode = appVersion.split(".").let { it[0].toInt() * 1000 + it.getOrElse(1) { "0" }.toInt() }

android {
    namespace = "com.airmirror.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airmirror.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }

    // Updates only install over an app signed with the same key, so release builds use one fixed
    // private key. It is never stored in the repo: GitHub Actions decodes it from the
    // AIRMIRROR_KEYSTORE_BASE64 secret (create it with tools/create-signing-key.bat), and a local
    // build can use the same file through these environment variables.
    val keystorePath = System.getenv("AIRMIRROR_KEYSTORE")
    val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("AIRMIRROR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AIRMIRROR_KEY_ALIAS") ?: "airmirror"
                keyPassword = System.getenv("AIRMIRROR_KEY_PASSWORD")
                    ?: System.getenv("AIRMIRROR_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a configured key, fall back to the debug key so the APK still installs;
            // in-app updates then only work between builds signed on the same machine.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/native-image/**",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(project(":airplay"))
    implementation("uk.uuid.slf4j:slf4j-android:2.0.16-0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
