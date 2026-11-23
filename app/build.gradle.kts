plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.wiimauto.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wiimauto.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            // Live read-only test: gradlew testDebugUnitTest -Pwiim.host=<wiim-ip>
            it.systemProperty("wiim.host", project.findProperty("wiim.host") ?: "")
            it.outputs.upToDateWhen { false }
        }
    }
}

dependencies {
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-common:1.6.1")
    implementation("androidx.media3:media3-datasource:1.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
