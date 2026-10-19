plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python")
}

android {
    namespace = "com.example.phone_number_lookup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.phone_number_lookup"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        val pythonProp = project.findProperty("buildPython") as? String
        if (!pythonProp.isNullOrBlank()) {
            buildPython(pythonProp)
        } else if (System.getProperty("os.name").lowercase().contains("win")) {
            buildPython("py", "-3.12")
        } else {
            buildPython("python3.12")
        }
        pip {
            install("requests")
            install("certifi")
            install("colorama")
            install("PySocks")
            install("aiohttp")
            install("requests-futures")
            install("rich")
            install("chardet")
            install("tomli")
            install("httpx")
            install("trio")
            install("beautifulsoup4")
            install("termcolor")
            install("tqdm")
        }
    }
}

dependencies {
    implementation(libs.libphonenumber)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
