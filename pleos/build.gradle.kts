// Pleos Connect 플랫폼 어댑터. 도메인의 인터페이스를 Pleos SDK(Vehicle·NaviHelper·SpeechToText)와 AAOS API로 구현한다.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.drivesplit.pleos"
    compileSdk = 35
    // 차속은 Pleos SDK에 없어서 AAOS 표준 CarPropertyManager로 읽는다.
    useLibrary("android.car")

    defaultConfig {
        minSdk = 30
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
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navi)
    implementation(libs.pleos.stt)
}
