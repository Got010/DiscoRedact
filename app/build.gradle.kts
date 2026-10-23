import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// VERSION at the repo root is the one source of the app's version. A change to it is a release: the next merge to
// main publishes it (.github/workflows/build.yml). versionCode = major * 1_000_000 + minor * 1_000 + patch, so
// minor and patch stay below 1000 and the code rises with every version (0.1.0 is 1000).
val versionFile = rootProject.file("VERSION")
if (!versionFile.isFile) {
    throw GradleException("VERSION is missing (expected ${versionFile.path}); it must hold one line, X.Y.Z, such as 0.1.0")
}
val versionText = versionFile.readText().trim()
val versionParts = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(versionText)
    ?.destructured?.toList()?.map { it.toIntOrNull() ?: Int.MAX_VALUE }
    ?: throw GradleException("VERSION must be X.Y.Z, digits only; found '$versionText'")
val (versionMajor, versionMinor, versionPatch) = versionParts
if (versionMajor > 2000 || versionMinor > 999 || versionPatch > 999) {
    throw GradleException("VERSION $versionText is out of range: major at most 2000, minor and patch at most 999")
}

android {
    namespace = "app.discoredact"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.discoredact"
        minSdk = 35
        targetSdk = 36
        versionCode = versionMajor * 1_000_000 + versionMinor * 1_000 + versionPatch
        versionName = versionText
    }

    buildTypes {
        release {
            // No signingConfig: the release APK is built unsigned on every event (gate-baseline 5.1).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.version", "META-INF/AL2.0", "META-INF/LGPL2.1", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        // No Google-encrypted dependency block in the APK.
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":net"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}
