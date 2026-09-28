plugins {
    id("com.android.application")
}

android {
    namespace = "dev.vm.oneuifasthome"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.vm.oneuifasthome"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Xposed entry class is looked up by name from assets/xposed_init: no shrinking.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        resValues = true
    }
}

dependencies {
    // Provided by the framework (Vector/LSPosed) at runtime; must NOT be packaged into the APK.
    compileOnly("de.robv.android.xposed:api:82")
}
