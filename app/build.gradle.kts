plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "uk.noammm.aamods"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.noammm.aamods"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1"
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
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
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
