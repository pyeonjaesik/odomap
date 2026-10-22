plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.odomap.app"
    compileSdk = 35

    // AAOS car APIs (CarPropertyManager, CarUxRestrictionsManager). Not required at runtime: every use is guarded so the app also runs on
    // phones / tablets / plain emulators (manifest: <uses-library android:name="android.car" android:required="false"/>).
    useLibrary("android.car", false)

    defaultConfig {
        applicationId = "com.odomap.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // MapLibre ships native libs for 4 ABIs; Pleos head units are arm64, emulators x86_64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        // compress MapLibre's native libs in the APK (≈13 MB → ≈5 MB per ABI); extracted at install
        jniLibs.useLegacyPackaging = true
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric screenshot tests
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)
    // Pleos Playground SDKs
    implementation(libs.pleos.fused.location)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navi.helper)

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json on the JVM (android.jar's is a stub)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM screenshot tests of the Compose UI (Robolectric native graphics + Roborazzi); the map is drawn by CanvasMap there
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // ./gradlew :app:testDebugUnitTest -PodomapRenderDir=/some/dir → PNG renders of the demo net
    (project.findProperty("odomapRenderDir") as String?)?.let { systemProperty("odomap.render.dir", it) }
    systemProperty("roborazzi.test.record", "true")
    systemProperty("robolectric.graphicsMode", "NATIVE")
    maxHeapSize = "3g"
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
