plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 버전 번호 = 커밋 개수. 새로 빌드할 때마다 자동으로 올라가서 앱 안 업데이트가 새 버전을 알아본다
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

// 업데이트는 같은 키로 서명돼야 설치된다. 키 파일 위치·비밀번호는 환경변수로 바꿀 수 있다
val memoKeystore = System.getenv("MEMO_KEYSTORE")?.let { file(it) } ?: rootProject.file("signing/memo.keystore")

android {
    namespace = "com.lockmemo.app"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("memo") {
            storeFile = memoKeystore
            storePassword = System.getenv("MEMO_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("MEMO_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("MEMO_KEY_PASSWORD") ?: "android"
        }
    }

    defaultConfig {
        applicationId = "com.lockmemo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = commitCount
        versionName = "1.0.$commitCount"

        // 앱 안 업데이트 확인: 이 주소의 update.json 을 읽는다
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"https://raw.githubusercontent.com/akskekekdk/lockedscreenmemoapp/memo-releases/update.json\"",
        )
    }

    buildTypes {
        debug {
            if (memoKeystore.exists()) signingConfig = signingConfigs.getByName("memo")
        }
        release {
            isMinifyEnabled = false
            if (memoKeystore.exists()) signingConfig = signingConfigs.getByName("memo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
