import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Use the matching sherpa-onnx 2.0.1 JNI and Kotlin bindings from a pinned
// Android prebuild. No React Native code or model weights are included.
val kokoroRuntime = layout.buildDirectory.dir("generated/kokoroRuntime")
val kokoroPrebuild = "https://registry.npmjs.org/@siteed/sherpa-onnx.rn/-/sherpa-onnx.rn-2.0.1.tgz"
val kokoroPrebuildSha = "ff4f84512559173e6c61bce1c5723028774ba5040a0fa0a2ab5d9909b834693f"
val prepareKokoroRuntime by tasks.registering {
    inputs.property("url", kokoroPrebuild)
    inputs.property("sha256", kokoroPrebuildSha)
    inputs.property("bindings", "tts-and-online-2026-11")
    outputs.dir(kokoroRuntime)
    doLast {
        val bundle = File(temporaryDir, "sherpa.tgz")
        val connection = URI(kokoroPrebuild).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(connection.getInputStream(), digest).use { input ->
                bundle.outputStream().use { input.copyTo(it) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == kokoroPrebuildSha) {
                "Kokoro runtime checksum mismatch"
            }
            val prefix = "package/android/src/main/"
            val bindings = listOf("Tts", "OnlineRecognizer", "OnlineStream", "FeatureConfig",
                "HomophoneReplacerConfig", "QnnConfig").map { "kotlin/com/k2fsa/sherpa/onnx/$it.kt" }
            project.sync {
                from(project.tarTree(project.resources.gzip(bundle))) {
                    include(bindings.map { "$prefix$it" } + listOf("${prefix}jniLibs/*/libonnxruntime.so",
                        "${prefix}jniLibs/*/libsherpa-onnx-jni.so"))
                    eachFile { path = path.removePrefix(prefix) }
                    includeEmptyDirs = false
                }
                into(kokoroRuntime)
            }
            val output = kokoroRuntime.get().asFile
            check(bindings.all { output.resolve(it).isFile })
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
                check(output.resolve("jniLibs/$abi/libsherpa-onnx-jni.so").isFile)
                check(output.resolve("jniLibs/$abi/libonnxruntime.so").isFile)
            }
        } finally {
            bundle.delete()
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareKokoroRuntime) }

android {
    namespace = "com.phoneassistant"
    compileSdk = 34

    sourceSets.getByName("main") {
        java.srcDir(kokoroRuntime.map { it.dir("kotlin") })
        jniLibs.srcDir(kokoroRuntime.map { it.dir("jniLibs") })
    }
    packaging.jniLibs.useLegacyPackaging = true

    defaultConfig {
        applicationId = "com.phoneassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 179
        versionName = "0.179"
    }

    // One committed, self-signed key signs every build, debug and release alike, so the
    // signature never changes and updates install straight over the top. It is public:
    // ideal for a personal sideloaded app, and never acceptable for Play Store
    // distribution. See the README if you ever decide to share it.
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("signing/phone-assistant.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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

dependencies {
    implementation("org.apache.ant:ant:1.10.15") { isTransitive = false }
}
