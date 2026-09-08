plugins {
    alias(libs.plugins.android.application)
}

android {
    /*
     * Deliberately still the desktop launcher's package name.
     *
     * `namespace` names the Kotlin/R package, not the app, and every one of the ~200
     * source files declares `package rocks.gorjan.gokixp`. `applicationId` below is what
     * the phone actually installs under, and it is independent of this - which is what
     * lets the two launchers sit side by side without a 200-file rename. Relative names
     * in the manifest (`.MainActivity`) are expanded against this namespace before the
     * applicationId is stamped in, so they keep resolving correctly.
     */
    namespace = "rocks.gorjan.gokixp"
    compileSdk = 36

    defaultConfig {
        // The Windows Phone launcher is its own app: a different id from the desktop
        // launcher, so both can be installed at once. That is not a nicety - the
        // migration handoff reads the old app's saved Start screen while it is still
        // there, so they have to coexist.
        applicationId = "rocks.gorjan.gokiwp"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /*
         * Which processors the bundled native code is carried for.
         *
         * Only relevant since Vosk arrived - nothing else in this project has a native part.
         * Its AAR ships seven architectures totalling forty megabytes, three of which
         * (mips, mips64, armeabi) have not been supported by Android for years and one of
         * which (x86) is a 32-bit emulator nobody runs any more.
         *
         * What is left is the two every real phone uses. The emulator's x86_64 is gone too,
         * which saves a further ten megabytes at the cost of not being able to test the
         * keyboard's dictation on an emulator - this is a launcher for phones, and it is
         * tested on one.
         */
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Kotlin jvmTarget follows compileOptions.targetCompatibility (AGP built-in Kotlin)
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.code.gson:gson:2.10.1")
    // Glide is gone with Clippy - the desktop agent was its only user. ExifInterface came
    // in behind it, and a picked photo still has to be turned the right way up, so it is
    // asked for directly now rather than arriving as somebody else's transitive.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.29")

    // Android Auto: template host + the navigation surface the car screen is drawn on.
    // See car/GokiCarAppService.kt. app-projected is the phone-projection artifact;
    // app-automotive would be the one for cars running Android Automotive OS.
    // app-projected declares the core artifact as runtime-only, so it has to be asked for
    // by name or none of the API resolves at compile time.
    // MediaBrowserService, so the car's media list can browse the Zune library.
    implementation("androidx.media:media:1.8.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    /*
     * Offline speech recognition, for the keyboard's dictation.
     *
     * Vosk runs entirely on the phone: no account, no network, nothing sent anywhere. That is
     * the whole reason it is here rather than the platform's own recogniser, which on most
     * Android phones is Google's and transcribes in the cloud - and which on this one cannot
     * be used at all, because GrapheneOS ships no speech-to-text engine and deliberately
     * leaves no recogniser selected.
     *
     * JNA is not optional: vosk-android is a thin Kotlin layer over a native library and
     * reaches it through JNA rather than hand-written JNI, so leaving it out compiles and
     * then fails at the first call.
     *
     * This is the first native code in the project. Nothing here is written in C++, but the
     * AAR carries prebuilt .so files, which is why the APK grows by more than the Java in it.
     */
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // WindowManager for foldable device detection
    implementation("androidx.window:window:1.3.0")

    // OSMDroid for OpenStreetMap

    // No PdfBox: it was only ever the desktop photo viewer's, for opening a PDF in a window.

    // No Google Drive or Play auth either. Those five artifacts existed for the Registry
    // Editor's settings sync, which is a desktop program. Settings here travel by the
    // export/import file instead.

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}