plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phoneassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    // Debug builds use a committed, public key so local installs update over the top
    // with no ceremony. It is a sideloading convenience, never a distribution key.
    //
    // Release builds use your own private keystore, supplied through environment
    // variables — locally, or from GitHub Actions secrets. That keystore is not in
    // this repo, so anyone else cloning it still gets a buildable project.
    val privateKeystorePath = System.getenv("RELEASE_KEYSTORE_FILE")
    val hasPrivateKey =
        !privateKeystorePath.isNullOrBlank() && rootProject.file(privateKeystorePath).exists()

    if (!hasPrivateKey && gradle.startParameter.taskNames.any { it.contains("Release") }) {
        logger.lifecycle(
            "Release signing: RELEASE_KEYSTORE_FILE is not set, falling back to the public " +
                "debug key. This APK is for testing only and cannot update a real release."
        )
    }

    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("signing/phone-assistant.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (hasPrivateKey) {
            create("privateRelease") {
                storeFile = rootProject.file(privateKeystorePath!!)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "phoneassistant"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(
                if (hasPrivateKey) "privateRelease" else "stable"
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

// Deliberately no runtime dependencies: the overlay, chat UI and HTTP client all
// use platform APIs so the app stays small and builds without a dependency graph.
dependencies {
}
