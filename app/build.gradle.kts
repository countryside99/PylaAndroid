plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val pylaRoot = layout.projectDirectory.dir("../PylaAI-OriginalPCVersion")

tasks.register<Copy>("copyPylaAssets") {
    description = "Copy cfg/, models/, images/, playstyles/ from the upstream PylaAI repository into app assets so they ship inside the APK."
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
        versionCode = 40001
        versionName = "4.0.1"
        buildConfigField("String", "DIAGNOSTIC_URL", "\"https://pyla-logs.fetzi-ai.de/v1/reports\"")
        val uploadCapability = providers.environmentVariable("PYLA_DIAGNOSTIC_UPLOAD_TOKEN").orElse("").get()
        require(uploadCapability.isEmpty() || uploadCapability.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        // Upload-only capability, never a Cloudflare or private read credential.
        buildConfigField("String", "DIAGNOSTIC_UPLOAD_TOKEN", "\"$uploadCapability\"")
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

    buildFeatures { compose = true; buildConfig = true }
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
    // Existing UI references icons outside the core set; use the existing BOM.
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.opencv)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
