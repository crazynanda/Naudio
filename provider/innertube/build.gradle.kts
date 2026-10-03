plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.naudio.provider.innertube"
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
    // The backend boundary speaks only domain vocabulary: :core:model (Track,
    // AudioSource) and :provider:api (Page, PageToken).
    api(project(":core:model"))
    api(project(":provider:api"))
    implementation(project(":core:network"))

    // The constructor takes the shared HttpClient; :core:network does not leak
    // its Ktor dependencies, so the client API is declared here too (same as
    // the iTunes and YouTube Music providers).
    implementation(libs.ktor.client.core)
    // InnerTube responses are deeply nested JsonObject graphs with no useful
    // DTO mapping, so the parsers traverse kotlinx.serialization's JSON tree
    // directly (same reason the YouTube Music provider re-declared it).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
