plugins {
    id("com.android.application") version "8.12.1"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "moe.qwreey.powerdirect"
    compileSdk = 36

    // AGP 기본값은 보통 아무도 설치하지 않은 버전이라 고정한다.
    ndkVersion = providers.gradleProperty("spd.ndkVersion").getOrElse("27.1.12297006")

    defaultConfig {
        applicationId = "moe.qwreey.powerdirect"
        // 30 (Android 11) 이 하한. 무선 디버깅이 그 전에는 없고,
        // 앱이 shell 권한을 빌리는 유일한 경로가 그것이다.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // 페어링 네이티브 코드는 arm64 만 낸다. 대상 기기가 전부 arm64 다.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                // 16 KB 페이지 크기: Android 15+ 기기가 그럴 수 있고,
                // 정렬 안 된 라이브러리는 거기서 로드되지 않는다.
                arguments("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    buildFeatures {
        compose = true
        // BoringSSL 이 헤더와 정적 라이브러리를 담은 AAR 로 온다.
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // RFC 5705 exported keying material 를 쓰는 TLS 1.3.
    // ADB 페어링 핸드셰이크가 그 값을 비밀번호에 섞는데,
    // 플랫폼 TLS 는 앱에 그걸 노출하지 않는다.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // SPAKE2 용 BoringSSL prefab AAR. Shizuku 가 쓰는 것과 같은 아티팩트.
    implementation("io.github.vvb2060.ndk:boringssl:20250114")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
