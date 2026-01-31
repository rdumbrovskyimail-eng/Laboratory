@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.opuside.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opuside.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // ═══════════════════════════════════════════════════════════════════
        // API KEYS (из local.properties - НЕ коммитить!)
        // ═══════════════════════════════════════════════════════════════════
        buildConfigField(
            "String",
            "ANTHROPIC_API_KEY",
            "\"${project.findProperty("ANTHROPIC_API_KEY") ?: ""}\""
        )
        buildConfigField(
            "String",
            "GITHUB_TOKEN",
            "\"${project.findProperty("GITHUB_TOKEN") ?: ""}\""
        )
        buildConfigField(
            "String",
            "GITHUB_OWNER",
            "\"${project.findProperty("GITHUB_OWNER") ?: ""}\""
        )
        buildConfigField(
            "String",
            "GITHUB_REPO",
            "\"${project.findProperty("GITHUB_REPO") ?: ""}\""
        )

        // ═══════════════════════════════════════════════════════════════════
        // API ENDPOINTS
        // ═══════════════════════════════════════════════════════════════════
        buildConfigField(
            "String",
            "ANTHROPIC_API_URL",
            "\"https://api.anthropic.com/v1/messages\""
        )
        buildConfigField(
            "String",
            "GITHUB_API_URL",
            "\"https://api.github.com\""
        )
        buildConfigField(
            "String",
            "GITHUB_GRAPHQL_URL",
            "\"https://api.github.com/graphql\""
        )

        // ═══════════════════════════════════════════════════════════════════
        // APP CONSTANTS
        // ═══════════════════════════════════════════════════════════════════
        buildConfigField("Long", "CACHE_TIMEOUT_MS", "300000L") // 5 минут
        buildConfigField("Int", "MAX_CACHE_FILES", "20")
    }

    // ✅ ИСПРАВЛЕНО: CRITICAL #3 - CLAUDE_MODEL доступен во всех buildTypes
    buildTypes.configureEach {
        buildConfigField("String", "CLAUDE_MODEL", "\"claude-opus-4-5-20251101\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug") // TODO: Настроить release signing
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/previous-compilation-data.bin"
        }
    }

    // KSP для Room
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
        arg("room.generateKotlin", "true")
    }
}

dependencies {
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE ANDROID
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // ═══════════════════════════════════════════════════════════════════════════
    // JETPACK COMPOSE
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ═══════════════════════════════════════════════════════════════════════════
    // NAVIGATION
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(libs.androidx.navigation.compose)

    // ═══════════════════════════════════════════════════════════════════════════
    // KTOR 3.x (SSE Streaming для Claude API)
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ═══════════════════════════════════════════════════════════════════════════
    // KOTLINX
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // ═══════════════════════════════════════════════════════════════════════════
    // HILT (Dependency Injection)
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ═══════════════════════════════════════════════════════════════════════════
    // HILT WORK (✅ ДОБАВЛЕНО: Проблема №2 - Поддержка Workers в Hilt)
    // ═══════════════════════════════════════════════════════════════════════════
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // ═══════════════════════════════════════════════════════════════════════════
    // WORKMANAGER (✅ ДОБАВЛЕНО: Требуется для фонового таймера)
    // ═══════════════════════════════════════════════════════════════════════════
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ═══════════════════════════════════════════════════════════════════════════
    // ENCRYPTION (✅ НОВОЕ: Шифрование кеша)
    // ═══════════════════════════════════════════════════════════════════════════
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ═══════════════════════════════════════════════════════════════════════════
    // ROOM (Database для кеша и истории чата)
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ═══════════════════════════════════════════════════════════════════════════
    // DATASTORE (Preferences)
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(libs.androidx.datastore.preferences)

    // ═══════════════════════════════════════════════════════════════════════════
    // BIOMETRIC (Опциональная защита)
    // ═══════════════════════════════════════════════════════════════════════════
    implementation(libs.androidx.biometric)

    // ═══════════════════════════════════════════════════════════════════════════
    // TESTING
    // ═══════════════════════════════════════════════════════════════════════════
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}