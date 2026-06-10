plugins {
    id("com.android.application")
}

android {
    namespace = "com.opencode.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.opencode.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ✅ تضمين proot وbusybox كـ native libraries قابلة للتنفيذ
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        // ✅ منع ضغط الـ .so حتى يُثبَّت قابلاً للتنفيذ مباشرة
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.activity:activity:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
}
