plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "com.marinov.boletosfei"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.marinov.boletosfei"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.webkit)
    implementation(libs.core.ktx)
    implementation (libs.jsoup)
    implementation (libs.gson)
    implementation (libs.work.runtime.ktx)
    implementation (libs.glide)
    annotationProcessor (libs.compiler)
    implementation (libs.swiperefreshlayout)
    implementation(libs.play.services.ads)
}
