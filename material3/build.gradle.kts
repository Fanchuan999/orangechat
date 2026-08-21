plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val materialColorUtilitiesKotlinDir = layout.projectDirectory.dir("material-color-utilities/kotlin")
val dynamicSchemeSource = materialColorUtilitiesKotlinDir.file("dynamiccolor/DynamicScheme.kt")

check(dynamicSchemeSource.asFile.isFile) {
    "Missing material3/material-color-utilities sources. Run `git submodule update --init --checkout material3/material-color-utilities`."
}

android {
    namespace = "me.rerere.material3"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        named("main") {
            kotlin.srcDir(materialColorUtilitiesKotlinDir)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
}
