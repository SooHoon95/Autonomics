import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 오피넷 키 같은 개인 값은 커밋하지 않는 local.properties에서 읽는다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.drivesplit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drivesplit.app"
        minSdk = 30
        targetSdk = 34 // Pleos Connect 에뮬레이터 API 레벨
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "KAKAO_REST_KEY", "\"${localProps.getProperty("kakao.rest.key", "")}\"")
        // 정산 웹·단가 함수가 있는 Netlify 사이트. 오피넷 키는 앱이 아니라 그 사이트 환경 변수(OPINET_KEY)에 둔다.
        buildConfigField("String", "SITE_URL", "\"https://drivesplit.netlify.app/\"")
        buildConfigField("String", "SHARE_BASE_URL", "\"https://drivesplit.netlify.app/s\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 제출용 release APK가 바로 설치되도록 debug 키로 서명한다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":data"))
    implementation(project(":pleos"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
