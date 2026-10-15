plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.fliplauncher"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.fliplauncher"
        minSdk = 33
        targetSdk = 36
        versionCode = 11
        versionName = "0.7.0"
    }

    // Release signing comes from the environment (CI secrets); without it release APKs stay unsigned.
    val releaseKeystore = System.getenv("FLIPLAUNCHER_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("FLIPLAUNCHER_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FLIPLAUNCHER_KEY_ALIAS")
            keyPassword = System.getenv("FLIPLAUNCHER_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
