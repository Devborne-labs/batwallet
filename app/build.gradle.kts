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
        versionCode = 67
        versionName = "20.8"
    }

    // Release key: kept outside the project (path and password in ~/.gradle/gradle.properties, never in git).
    // Without it (someone building from the source) releases fall back to the debug key.
    val releaseKey = providers.gradleProperty("BATWALLET_STORE_FILE").orNull?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = releaseKey
            storePassword = providers.gradleProperty("BATWALLET_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("BATWALLET_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("BATWALLET_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            // Shrunk and optimised. proguard-rules.pro keeps the WorkManager/Room classes that are created by
            // reflection (removing them is what crashed 9.0/9.1 on launch).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The screen tests (Robolectric) draw with the app's own resources: fonts, coin logos, translations.
    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    testImplementation("junit:junit:4.13.2")
    // Screen tests: the main pieces of the screens drawn without a phone, checked, and saved as pictures.
    testImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
}

// The screen tests save their pictures to app/build/outputs/roborazzi (the Build workflow keeps them with each run).
tasks.withType<Test>().configureEach { systemProperty("roborazzi.test.record", "true") }

// The logic checks (app/src/test) run before every release build, so a broken one stops the release.
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("testDebugUnitTest") }
