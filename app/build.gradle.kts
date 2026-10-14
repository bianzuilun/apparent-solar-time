import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息来自根目录 keystore.properties（私有仓库随库保存，保证各机器构建签名一致；
// 若仓库转公开，请移出该文件与 keystore/ 并更换签名）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.apparentsolar.time"
    compileSdk = 36
    testBuildType = "release"

    defaultConfig {
        applicationId = "com.apparentsolar.time"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.5"
        testInstrumentationRunner = "com.apparentsolar.time.WidgetClockInstrumentation"
    }

    if (keystoreProps.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":core-solar"))
}
