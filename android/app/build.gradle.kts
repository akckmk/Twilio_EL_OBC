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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// The backend's code is bundled into the app, so the app can deploy it to your Twilio
// account during one-time setup. Single source of truth: ../backend.
val bundleBackend by tasks.registering(Copy::class) {
    from(rootProject.file("../backend/functions")) { include("*.js"); into("functions") }
    from(rootProject.file("../backend/package.json"))
    into(layout.buildDirectory.dir("generated/backend-assets/backend"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/backend-assets"))
tasks.named("preBuild") { dependsOn(bundleBackend) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303") // real org.json for tests on the computer
    // Runs the real screens on a simulated phone. The native add-on isn't needed
    // (no database, legacy graphics) and is often rate-limited by Maven Central.
    testImplementation("org.robolectric:robolectric:4.14.1") {
        exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
    }
    testImplementation("androidx.test:core:1.6.1")
}

// Print the full reason when a test fails (shown in the GitHub Actions log).
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}
