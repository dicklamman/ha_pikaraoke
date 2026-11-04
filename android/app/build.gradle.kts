plugins {
    id("com.android.application")
}

android {
    namespace = "com.dicklamman.pikaraoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dicklamman.pikaraoke"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.23.0"
    }

    signingConfigs {
        create("release") {
            // The keystore and its passwords live in the repository so that every
            // build of the add-on is signed with the same key: Android only
            // lets an update install over an existing app when the signature
            // matches, so a throwaway key would strand anyone who installed an
            // earlier build.
            storeFile = file("../keystore/pikaraoke.jks")
            storePassword = "pikaraoke"
            keyAlias = "pikaraoke"
            keyPassword = "pikaraoke"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
}
