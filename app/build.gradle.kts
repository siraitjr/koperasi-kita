plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("kotlin-kapt")
    // WAJIB untuk Supabase Kotlin SDK: seluruh model PostgREST/Realtime
    // di-serialisasi lewat kotlinx.serialization. Versi HARUS sama dengan
    // versi Kotlin (1.9.20, lihat gradle/libs.versions.toml).
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
}

android {
    namespace = "com.example.koperasikitagodangulu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.koperasikitagodangulu"
        minSdk = 21
        targetSdk = 34
        versionCode = 71
        versionName = "7.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // =================================================================
        // SUPABASE — konfigurasi endpoint.
        // -----------------------------------------------------------------
        // Nilainya dibaca dari gradle.properties / environment, BUKAN
        // di-hardcode di sini. Repo ini ada di GitHub; URL project dan anon
        // key yang ter-commit akan tetap ada di riwayat git selamanya.
        //
        // anon key memang dirancang untuk dipegang klien (ia hanya berguna
        // bersama RLS), tetapi tetap mengungkap identitas project — jadi
        // diperlakukan sebagai konfigurasi, bukan konstanta kode.
        //
        // Isi di ~/.gradle/gradle.properties (JANGAN di repo):
        //   SUPABASE_URL=https://xxx.supabase.co
        //   SUPABASE_ANON_KEY=eyJ...
        // Default kosong supaya build tetap jalan sebelum Supabase dipakai.
        // =================================================================
        buildConfigField(
            "String", "SUPABASE_URL",
            "\"${project.findProperty("SUPABASE_URL") ?: System.getenv("SUPABASE_URL") ?: ""}\""
        )
        buildConfigField(
            "String", "SUPABASE_ANON_KEY",
            "\"${project.findProperty("SUPABASE_ANON_KEY") ?: System.getenv("SUPABASE_ANON_KEY") ?: ""}\""
        )
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Diperlukan agar SUPABASE_URL / SUPABASE_ANON_KEY di bawah tersedia
        // sebagai BuildConfig. AGP 8 mematikan buildConfig secara default.
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core dependencies
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.runtime:runtime-livedata:1.5.4")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.work:work-runtime-ktx:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.6.4")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.firebase:firebase-bom:32.7.0")

    // CameraX
    val cameraXVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.camera:camera-extensions:$cameraXVersion")

    // ML Kit
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")

    // Accompanist
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.datastore:datastore-core:1.0.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")
    implementation("com.google.firebase:firebase-installations-ktx:17.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.android.gms:play-services-tasks:18.0.1")

    implementation("com.google.accompanist:accompanist-insets:0.28.0")
    implementation("com.google.firebase:firebase-functions:20.3.0")

    implementation("com.google.firebase:firebase-analytics-ktx:21.5.0")

    implementation("com.google.firebase:firebase-firestore-ktx:24.9.1")
    implementation("com.google.firebase:firebase-storage-ktx:20.3.0")
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation("com.github.skydoves:landscapist-coil:2.2.10")
    implementation("com.github.skydoves:landscapist-transformation:2.2.10")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Google Maps untuk Pengawas (tampilkan peta)
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // =====================================================================
    // SUPABASE KOTLIN SDK (Milestone 2 — side-by-side dengan Firebase)
    // ---------------------------------------------------------------------
    // Firebase SENGAJA dibiarkan di atas. Selama transisi, dua SDK hidup
    // berdampingan: Firebase masih melayani seluruh jalur produksi, Supabase
    // baru dipakai lapisan data source baru yang belum di-wire ke SyncManager
    // (itu Milestone 3).
    //
    // Dampak ukuran APK: menambah ±2-3 MB (Ktor + kotlinx-serialization).
    // Sementara saja — Firebase dicabut setelah cutover.
    //
    // ⚠ Versi di bawah BELUM PERNAH DIRESOLUSI. Environment tempat kode ini
    //   ditulis memblokir dl.google.com dan Maven (403 CONNECT), jadi Gradle
    //   tidak bisa mengunduh apa pun. Verifikasi di mesin Anda.
    // =====================================================================
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.0"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")

    // auth-kt TIDAK diminta eksplisit, tetapi WAJIB ada: seluruh tabel
    // memakai RLS, dan tanpa JWT pengguna, setiap query PostgREST dieksekusi
    // sebagai `anon` yang tidak punya policy apa pun (lihat 002 §0) — semua
    // request akan balik kosong/403. Lapisan data source ini tidak akan
    // berfungsi tanpanya.
    // Catatan: modul ini bernama `gotrue-kt` sebelum supabase-kt 2.6.0.
    implementation("io.github.jan-tennert.supabase:auth-kt")

    // Engine HTTP untuk Ktor. Supabase SDK tidak membawa engine sendiri;
    // tanpa baris ini SDK gagal saat runtime, bukan saat compile.
    // OkHttp dipilih karena sudah ada di dependency tree (Firebase/Coil).
    implementation("io.ktor:ktor-client-okhttp:2.3.12")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}