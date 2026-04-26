import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.omni.assistant"
    compileSdk = 35

    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use(::load)
        }
    }
    val localBackendUrl = localProperties.getProperty("OMNI_BACKEND_URL")
        ?.takeIf { it.isNotBlank() }
        ?: "http://192.168.2.111:4000"

    val defaultBackendUrl = providers.gradleProperty("OMNI_BACKEND_URL")
        .orElse(providers.environmentVariable("OMNI_BACKEND_URL"))
        .orElse(localBackendUrl)
        .get()

    defaultConfig {
        applicationId = "com.omni.assistant"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$defaultBackendUrl\"")
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.billing.ktx)
    implementation("com.google.android.material:material:1.12.0")
    debugImplementation(libs.androidx.ui.tooling)
}
