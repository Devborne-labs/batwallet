plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.walletlookup"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.malluwayne.batwallet"
        minSdk = 28
        targetSdk = 36
        versionCode = 47
        versionName = "18.0"
    }

    buildTypes {
        release {
            // Shrunk and optimised. proguard-rules.pro keeps the WorkManager/Room classes that are created by
            // reflection (removing them is what crashed 9.0/9.1 on launch).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // Installs the speed profiles that come with Compose, so a sideloaded APK starts and scrolls like a Play Store one.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
