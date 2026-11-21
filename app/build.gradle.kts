plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.fakepod.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fakepod.remote"
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        targetSdk = 35
        versionCode = 59
        versionName = "0.5.46-cover-art-speed"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += setOf("META-INF/LICENSE.txt", "META-INF/NOTICE.txt")
            excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        // Package source directly: no matching desktop Python installation is required.
        pyc { src = false }
    }
}

dependencies {
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
