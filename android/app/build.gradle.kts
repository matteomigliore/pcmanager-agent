plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.matteomigliore.pcmanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.matteomigliore.pcmanager"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.7.0"
    }
    /*
     * Chiave di firma STABILE.
     *
     * Prima si usava la firma di debug generata al volo dalla macchina che compilava: cambiando
     * ad ogni build, Android rifiutava l'aggiornamento ("signatures do not match") e l'app andava
     * disinstallata e reinstallata ogni volta, perdendo il collegamento al cloud. Con una chiave
     * fissa (dai secret della pipeline) gli aggiornamenti si installano sopra, senza perdere nulla.
     * In locale, senza la chiave, si ripiega sulla firma di debug per poter comunque compilare.
     */
    signingConfigs {
        create("stabile") {
            val ks = System.getenv("ANDROID_KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("ANDROID_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: System.getenv("ANDROID_STORE_PASSWORD")
            }
        }
    }
    buildTypes {
        val conChiave = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it).exists() } ?: false
        debug {
            if (conChiave) signingConfig = signingConfigs.getByName("stabile")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (conChiave) signingConfig = signingConfigs.getByName("stabile")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    // scanner QR di sistema: nessun permesso fotocamera da chiedere, nessuna anteprima da gestire
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
