plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.robotx30.controlcameraptz"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.robotx30.controlcameraptz"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)

    // LibVLC para streaming RTSP de baja latencia
    implementation("org.videolan.android:libvlc-all:3.6.0-eap5")

    // OkHttp y Digest Auth para comandos HTTP CGI de la PTZ
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.rburgst:okhttp-digest:3.1.0")

    // Corrutinas para peticiones de red asíncronas
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
