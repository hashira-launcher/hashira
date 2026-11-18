import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties (git-ignored) or HASHIRA_* environment
// variables. Nothing secret is ever stored in the repository. Without it, assembleRelease
// produces an unsigned APK.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(property)?.takeIf { it.isNotBlank() }

val storeFilePath = signingValue("storeFile", "HASHIRA_KEYSTORE_FILE")
val storePass = signingValue("storePassword", "HASHIRA_KEYSTORE_PASSWORD")
val keyAliasName = signingValue("keyAlias", "HASHIRA_KEY_ALIAS")
val keyPass = signingValue("keyPassword", "HASHIRA_KEY_PASSWORD")
val canSign = listOf(storeFilePath, storePass, keyAliasName, keyPass).all { it != null }

android {
    namespace = "com.hashira.launcher"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.hashira.launcher"
        minSdk = 33
        targetSdk = 36
        versionCode = providers.gradleProperty("hashira.versionCode").get().toInt()
        versionName = providers.gradleProperty("hashira.versionName").get()
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = rootProject.file(storeFilePath!!)
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
}
