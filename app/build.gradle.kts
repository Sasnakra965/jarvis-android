plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version et signature fournies par GitHub Actions (voir .github/workflows/apk.yml).
val keystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "fr.jarvis.telephone"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.jarvis.telephone"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "dev"
    }

    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json n'existe qu'en « bouchon » dans android.jar : la vraie version pour les tests.
    testImplementation("org.json:json:20240303")
}
