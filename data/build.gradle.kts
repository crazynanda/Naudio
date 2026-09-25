plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.naudio.data"
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
    api(project(":provider:api"))
    implementation(project(":provider:default"))

    implementation(libs.kotlinx.coroutines.core)

    // Persistence layer: Room database + entities + DAOs.
    api(project(":core:database"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

