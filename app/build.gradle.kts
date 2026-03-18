import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // This handles your Compose compiler version automatically
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
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
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // FIXED: Moved signingConfigs outside of buildTypes and before it
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("releaseSigning") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
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

        // FIXED: Merged both release blocks into one
        getByName("release") {
            isMinifyEnabled = true // Set to true for production optimization
            manifestPlaceholders["adMobAppId"] = "ca-app-pub-XXXXXXXXXXXXXXXX~XXXXXXXXXX"
            buildConfigField(
                "String",
                "BANNER_AD_UNIT_ID",
                "\"ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX\""
            )

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Attach signing config if the file was found
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("releaseSigning")
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
