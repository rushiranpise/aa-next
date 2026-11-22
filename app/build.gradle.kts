plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "io.github.rushiranpise.aanext"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.rushiranpise.aanext"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.0.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        // DexKit reads Android Auto's dex files to find classes again after an update. It loads
        // libdexkit.so from the module's lib directory, which Xposed only puts on the search path when
        // the installer extracts the libraries (Dex.kt falls back to the module's APK without this)
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation("org.luckypray:dexkit:2.3.0")
}

// The Android Auto protocol messages of the phone screen head unit, from Headunit Revived's .proto files
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
        }
    }
}
