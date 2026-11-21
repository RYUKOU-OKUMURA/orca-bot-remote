import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val revision = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.let { git ->
        if (git.result.get().exitValue == 0) git.standardOutput.asText.get().trim().toIntOrNull()
        else null
    }
}.getOrNull() ?: 1

val signingFile = rootProject.file(providers.gradleProperty("orcaRemoteSigning").getOrElse(
    "${System.getProperty("user.home")}/.config/orca-bot-remote/release/signing.properties"
))

android {
    if (signingFile.isFile) {
        val signing = Properties().apply { signingFile.inputStream().use { load(it) } }
        val releaseSigning = signingConfigs.create("release") {
            storeFile = signingFile.parentFile.resolve(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
        buildTypes.named("release") { signingConfig = releaseSigning }
    }

    namespace = "dev.okumura.orcaremote"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.okumura.orcaremote"
        minSdk = 29
        targetSdk = 37
        versionCode = revision
        versionName = "0.2.$revision"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.work.runtime.ktx)
    implementation(libs.commonmark.core)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
}
