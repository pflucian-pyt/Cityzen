plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.baw.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "ro.baw.monitor"
        // Unitatea din BAW are Android 11 (API 30). minSdk 26 lasa loc si
        // pentru unitati mai vechi, daca vor aparea.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    /*
     * Cheie proprie, tinuta in depozit. Fara ea, fiecare compilare pe GitHub
     * ar folosi alta cheie de test, Android ar refuza sa puna versiunea noua
     * peste cea veche, iar dezinstalarea ar sterge localStorage — adica exact
     * inventarul si istoricul adunate in masina.
     */
    signingConfigs {
        create("aMea") {
            storeFile = file("cheia-mea.jks")
            storePassword = "avatr123"
            keyAlias = "avatr"
            keyPassword = "avatr123"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            signingConfig = signingConfigs.getByName("aMea")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aMea")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // serveste assets de pe o origine https falsa, ca Chromium sa nu refuze
    // tacut geolocatia si stocarea
    implementation("androidx.webkit:webkit:1.11.0")
}
