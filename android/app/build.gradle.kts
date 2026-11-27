plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version number = minutes since 1 Jan 2024. It always goes up, whether the APK is built
// on GitHub or anywhere else, so Android always accepts a newer build as an update.
val buildMinute = ((System.currentTimeMillis() / 60_000L) - 28_401_120L).toInt()

// The one permanent signing key. Every build signed with it installs over the previous
// one and KEEPS your saved keys. The file is locked with a password that only lives in
// the GitHub secret ANDROID_SIGNING_PASSWORD (and your password manager).
val signingPassword: String? = System.getenv("ANDROID_SIGNING_PASSWORD")

android {
    namespace = "com.agentcontrol.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.agentcontrol.app"
        minSdk = 29 // Android 10+: has the system fix for task-hijacking (StrandHogg 2.0)
        targetSdk = 35
        // GitHub Actions passes the run number, so each build counts up.
        versionCode = buildMinute
        versionName = "1.1.$buildMinute"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/agent-control-release.p12")
            storeType = "pkcs12"
            storePassword = signingPassword
            keyAlias = "agentcontrol"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        release {
            // Not debuggable: a USB-connected computer can't read the app's data or memory.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the password the APK is left unsigned and can't be installed,
            // so a build can never quietly switch keys (which would force a reinstall).
            signingConfig = if (signingPassword.isNullOrEmpty()) null else signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303") // real org.json for tests on the computer
}
