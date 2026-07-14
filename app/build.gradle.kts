plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val pylaRoot = layout.projectDirectory.dir("../PylaAI-OriginalPCVersion")

tasks.register<Copy>("copyPylaAssets") {
    description = "Copy cfg/, models/, images/, playstyles/ from the Python project into app assets so they ship inside the APK."
    group = "pyla"
    val dest = layout.projectDirectory.dir("src/main/assets/pyla")
    from(pylaRoot.dir("cfg")) { into("cfg") }
    from(pylaRoot.dir("models")) { into("models"); exclude("easyocr/**") }
    from(pylaRoot.dir("images")) { into("images") }
    from(pylaRoot.dir("playstyles")) { into("playstyles") }
    into(dest)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("copyPylaAssets")
}

android {
    namespace = "com.pyla.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pyla.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.opencv)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
