plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    namespace = "org.linox.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.linox.mobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "0.9.0"
    }

    // Keep Java and Kotlin on the same JVM target.
    // This fixes: Inconsistent JVM-target compatibility (Java 1.8 vs Kotlin 17).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
