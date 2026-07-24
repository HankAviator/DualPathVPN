plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.hankaviator.dualpathvpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hankaviator.dualpathvpn"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    base {
        archivesName.set("DualPathVPN-${defaultConfig.versionName}")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
