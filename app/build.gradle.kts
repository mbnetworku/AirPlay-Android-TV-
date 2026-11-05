plugins {
    id("com.android.application")
}

android {
    namespace = "com.airmirror.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airmirror.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 18
        versionName = "1.17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly on a TV box.
            signingConfig = signingConfigs.getByName("debug")
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
