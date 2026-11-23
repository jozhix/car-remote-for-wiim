import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional and stays outside the repository.
val signingDirectory = file(System.getProperty("user.home") + "/.car-remote-wiim")
val signingFile = signingDirectory.resolve("signing.properties")
val signingProperties = Properties().apply {
    if (signingFile.exists()) signingFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.wiimauto.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wiimauto.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (signingFile.exists()) {
            create("release") {
                storeFile = signingDirectory.resolve("release.jks")
                storePassword = signingProperties.getProperty("password")
                keyAlias = signingProperties.getProperty("alias")
                keyPassword = signingProperties.getProperty("password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-livedata:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
