plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.naudio.provider.ytmusic"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":provider:api"))

    // The YtMusicBackend abstraction. This module talks ONLY to this
    // dependency: it no longer declares Ktor or kotlinx-serialization, because
    // it no longer performs a network call or parses a response. The concrete
    // InnerTube implementation lives behind it.
    implementation(project(":provider:innertube"))

    // Only the tests need the network taxonomy, to assert that backend failures
    // reach the providers unchanged.
    testImplementation(project(":core:network"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // The integration tests drive the REAL InnerTube backend against a canned
    // MockEngine, so they exercise the actual client/request/parser stack.
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.core)
}