plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.eloquick.debug"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eloquick.debug"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-debug"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Use prebuilt native libraries from build/android/<abi>/
    sourceSets {
        main {
            jniLibs.srcDirs = ["build/android/"]
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
}