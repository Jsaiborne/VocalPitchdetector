import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // This handles your Compose compiler version automatically
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

val keystorePropertiesFile = rootProject.file("local.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// ktlint configuration
ktlint {
    android.set(true)
    verbose.set(true)
    enableExperimentalRules.set(false)

    filter {
        exclude("**/build/**")
    }

    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

// detekt configuration
detekt {
    toolVersion = "1.23.0"
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
    parallel = true
    ignoreFailures = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

android {
    namespace = "com.jsaiborne.vocalpitchdetector"
    compileSdk = 36 // FIXED: Simple assignment

    defaultConfig {
        applicationId = "com.jsaiborne.vocalpitchdetector"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // FIXED: Moved signingConfigs outside of buildTypes and before it
    signingConfigs {
        // Check if the file exists AND if it actually contains the required keys
        if (keystorePropertiesFile.exists()) {
            val sFile = keystoreProperties["storeFile"] as? String
            val sPassword = keystoreProperties["storePassword"] as? String
            val kAlias = keystoreProperties["keyAlias"] as? String
            val kPassword = keystoreProperties["keyPassword"] as? String

            // Only create the config if none of the values are null
            if (sFile != null && sPassword != null && kAlias != null && kPassword != null) {
                create("releaseSigning") {
                    storeFile = file(sFile)
                    storePassword = sPassword
                    keyAlias = kAlias
                    keyPassword = kPassword
                }
            }
        }
    }

    buildTypes {

        getByName("debug") {
            manifestPlaceholders["adMobAppId"] = "ca-app-pub-3940256099942544~3347511713"

            // Existing Portrait Ad Unit (Test ID)
            buildConfigField(
                "String",
                "BANNER_AD_UNIT_ID",
                "\"ca-app-pub-3940256099942544/9214589741\""
            )

            // NEW: Landscape Top Ad Unit (Using the second official test ID)
            buildConfigField(
                "String",
                "BANNER_AD_UNIT_LANDSCAPE_ID",
                "\"ca-app-pub-3940256099942544/2014213617\""
            )
        }

        getByName("release") {
            isMinifyEnabled = true // Set to true for production optimization

            // Read from local.properties instead of hardcoding
            val realAppId = keystoreProperties.getProperty("ADMOB_APP_ID") ?: ""
            val realBannerId = keystoreProperties.getProperty("ADMOB_BANNER_ID") ?: ""
            val realLandscapeId = keystoreProperties.getProperty("ADMOB_BANNER_LANDSCAPE_ID") ?: ""

            manifestPlaceholders["adMobAppId"] = realAppId

            buildConfigField(
                "String",
                "BANNER_AD_UNIT_ID",
                "\"$realBannerId\""
            )

            buildConfigField(
                "String",
                "BANNER_AD_UNIT_LANDSCAPE_ID",
                "\"$realLandscapeId\""
            )

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Safely attach signing config ONLY if it was successfully created earlier
            val releaseSignConfig = signingConfigs.findByName("releaseSigning")
            if (releaseSignConfig != null) {
                signingConfig = releaseSignConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // FIXED: Removed composeOptions block as it's no longer needed with the Compose plugin
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.foundation.layout)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation("com.github.st-h:TarsosDSP:2.4.1")
    implementation("androidx.navigation:navigation-compose:2.8.7")
    implementation("com.google.android.gms:play-services-ads:25.0.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
}
