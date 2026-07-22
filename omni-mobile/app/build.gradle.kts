import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.omni.assistant"
    compileSdk = 35
    val productionBackendUrl = "https://omni-backend-bq8e.onrender.com"

    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use(::load)
        }
    }
    fun escapedBuildConfigString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

    val localAptoidePublicKey = localProperties.getProperty("APTOIDE_PUBLIC_KEY")
        ?.takeIf { it.isNotBlank() }
        ?: "PASTE_YOUR_APTOIDE_PUBLIC_KEY_HERE"
    val aptoidePublicKey = providers.gradleProperty("APTOIDE_PUBLIC_KEY")
        .orElse(providers.environmentVariable("APTOIDE_PUBLIC_KEY"))
        .orElse(localAptoidePublicKey)
        .get()
        .let(::escapedBuildConfigString)
    val communityBackendUrl = providers.gradleProperty("OMNI_COMMUNITY_BACKEND_URL")
        .orElse(providers.environmentVariable("OMNI_COMMUNITY_BACKEND_URL"))
        .orElse(
            localProperties.getProperty("OMNI_DEBUG_BACKEND_URL")
                ?.takeIf { it.isNotBlank() }
                ?: "http://127.0.0.1:4000"
        )
        .get()
        .let(::escapedBuildConfigString)
    val localBackendUrl = localProperties.getProperty("OMNI_DEBUG_BACKEND_URL")
        ?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("OMNI_BACKEND_URL")
        ?.takeIf { it.isNotBlank() }
        ?: productionBackendUrl
    val debugBackendUrl = providers.gradleProperty("OMNI_DEBUG_BACKEND_URL")
        .orElse(providers.environmentVariable("OMNI_DEBUG_BACKEND_URL"))
        .orElse(providers.gradleProperty("OMNI_BACKEND_URL"))
        .orElse(providers.environmentVariable("OMNI_BACKEND_URL"))
        .orElse(localBackendUrl)
        .get()
    val releaseBackendUrl = providers.gradleProperty("OMNI_RELEASE_BACKEND_URL")
        .orElse(providers.environmentVariable("OMNI_RELEASE_BACKEND_URL"))
        .orElse(productionBackendUrl)
        .get()

    fun privateValue(name: String): String? =
        providers.gradleProperty(name)
            .orElse(providers.environmentVariable(name))
            .orNull
            ?.takeIf { it.isNotBlank() }

    val releaseStoreFile = privateValue("OMNI_RELEASE_STORE_FILE")
    val releaseStorePassword = privateValue("OMNI_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = privateValue("OMNI_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = privateValue("OMNI_RELEASE_KEY_PASSWORD")

    val privateReleaseSigningConfig =
        if (
            releaseStoreFile != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            signingConfigs.create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        } else {
            null
        }

    defaultConfig {
        applicationId = "com.omni.orb"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("googlePlay") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION_STORE", "\"google_play\"")
            buildConfigField("String", "APTOIDE_PUBLIC_KEY", "\"\"")
            buildConfigField("boolean", "COMMUNITY_BUILD", "false")
            buildConfigField("String", "COMMUNITY_BACKEND_URL", "\"\"")
        }
        create("aptoide") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION_STORE", "\"aptoide\"")
            buildConfigField("String", "APTOIDE_PUBLIC_KEY", "\"$aptoidePublicKey\"")
            buildConfigField("boolean", "COMMUNITY_BUILD", "false")
            buildConfigField("String", "COMMUNITY_BACKEND_URL", "\"\"")
        }
        create("community") {
            dimension = "distribution"
            applicationIdSuffix = ".community"
            versionNameSuffix = "-community"
            buildConfigField("String", "DISTRIBUTION_STORE", "\"community\"")
            buildConfigField("String", "APTOIDE_PUBLIC_KEY", "\"\"")
            buildConfigField("boolean", "COMMUNITY_BUILD", "true")
            buildConfigField("String", "COMMUNITY_BACKEND_URL", "\"$communityBackendUrl\"")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$debugBackendUrl\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = privateReleaseSigningConfig
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$releaseBackendUrl\"")
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
    add("googlePlayImplementation", libs.billing.ktx)
    add("aptoideImplementation", libs.aptoide.billing)
    implementation("com.google.android.material:material:1.12.0")
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}

