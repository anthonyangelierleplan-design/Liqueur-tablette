plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Numéro de version basé sur la date de compilation (en minutes) : il augmente toujours,
// quel que soit le dépôt qui compile (téléphone ou tablette), donc l'appli s'installe toujours par-dessus.
val buildCode = (System.currentTimeMillis() / 60000L).toInt()

android {
    namespace = "com.foudreaudio.liqueurs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foudreaudio.liqueurs"
        minSdk = 28
        targetSdk = 35
        versionCode = buildCode
        versionName = "1.0.$runNumber"
    }

    // Clé fixe : chaque nouvel APK s'installe par-dessus le précédent sans perdre le stock ni les ventes.
    signingConfigs {
        create("liqueurs") {
            storeFile = file("liqueurs.keystore")
            storePassword = "liqueurs"
            keyAlias = "liqueurs"
            keyPassword = "liqueurs"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("liqueurs") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("liqueurs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Nearby Connections : échange direct entre téléphones, sans Internet.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
}
