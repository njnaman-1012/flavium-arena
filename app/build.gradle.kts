import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing (Google Play upload key). The key and its passwords are never in the
// repository: they are read from keystore.properties in the project folder, which git
// ignores. Without that file the release build is produced unsigned.
val keystoreProperties = rootProject.file("keystore.properties")

// The Firebase configuration file is not in the repository (it belongs to one Firebase
// project). With the file present the app connects to that project; without it the app
// still builds and works on its local database only.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "in.flavium.arena"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "in.flavium.arena"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.exists()) {
            create("release") {
                val p = Properties()
                keystoreProperties.inputStream().use { p.load(it) }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystoreProperties.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}