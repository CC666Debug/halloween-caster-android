plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.cc666debug.halloweencaster"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.cc666debug.halloweencaster"
        minSdk = 24
        targetSdk = 35
        versionCode = 18
        versionName = "1.17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
}
