plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.naudio.core.database"
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

    ksp {
        // Schema export for this database (version 1) — tracked in source control.
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    sourceSets {
        // Expose exported schemas to androidTest so MigrationTestHelper can
        // create a real v1 database and migrate it to the current version.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Room runtime is exposed via api so that modules depending on
    // :core:database (e.g. :app) can access RoomDatabase/NaudioDatabase types.
    api("androidx.room:room-runtime:2.8.4")
    // M13: room-ktx (the suspend transaction extension) is exposed via api so
    // :data's TransactionRunner — which runs repository-level transactions on
    // the database — can compile against the same extension. No version bump;
    // same artifact the app already ships.
    api("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Android instrumented test dependencies (for Room in-memory tests).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
