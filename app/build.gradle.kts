import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.secrets)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.aistudio.phoneagent.wakeel"
        minSdk = 24
        targetSdk = 36
        versionCode =  4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Do not remove or modify these signingConfigs. They are necessary for building, installing,
    // and updating Android apps in AI Studio.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
            storeFile = file(keystorePath)
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.6.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.firebase.appcheck.playintegrity)
    implementation(libs.firebase.appcheck.debug)
    implementation(libs.firebase.appcheck.ktx)
    implementation(libs.firebase.ai.ktx)
    implementation(libs.firebase.analytics.ktx)
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.storage.ktx)
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.firebase.inappmessaging.ktx)
    implementation(libs.firebase.crashlytics.ktx)
    implementation(libs.firebase.perf.ktx)
    implementation(libs.firebase.database.ktx)
    implementation(libs.firebase.functions.ktx)
    implementation(libs.firebase.config.ktx)
    implementation(libs.firebase.installations.ktx)
    implementation(libs.firebase.appindexing)
    implementation(libs.playservices.mlkit.barcode)
    implementation(libs.playservices.mlkit.text.recognition)
    implementation(libs.playservices.mlkit.text.recognition.arabic)
    implementation(libs.playservices.mlkit.text.recognition.latin)
    implementation(libs.playservices.mlkit.text.recognition.devanagari)
    implementation(libs.playservices.mlkit.text.recognition.japanese)
    implementation(libs.playservices.mlkit.text.recognition.korean)
    implementation(libs.playservices.mlkit.text.recognition.chinese)
    implementation(libs.playservices.auth)
    implementation(libs.playservices.identity)
    implementation(libs.playservices.location)
    implementation(libs.playservices.maps)
    implementation(libs.playservices.safetynet)
    implementation(libs.playservices.ads)
    implementation(libs.playservices.wallet)
    implementation(libs.playservices.auth.api.phone)
    implementation(libs.playservices.mlkit.barcode.scanning)
    implementation(libs.playservices.mlkit.document.scanner)
    implementation(libs.playservices.mlkit.face.detection)
    implementation(libs.playservices.mlkit.image.labeling)
    implementation(libs.playservices.mlkit.image.labeling.custom)
    implementation(libs.playservices.mlkit.language.id)
    implementation(libs.playservices.mlkit.translate)
    implementation(libs.playservices.mlkit.smart.reply)
    implementation(libs.playservices.mlkit.entity.extraction)
    implementation(libs.playservices.mlkit.digital.ink.recognition)
    implementation(libs.playservices.mlkit.pose.detection)
    implementation(libs.playservices.mlkit.pose.detection.accurate)
    implementation(libs.playservices.mlkit.segmentation.selfie)
    implementation(libs.playservices.mlkit.subject.segmentation)
    implementation(libs.playservices.mlkit.object.detection)
    implementation(libs.playservices.mlkit.object.detection.custom)
    implementation(libs.playservices.mlkit.vision.common)
    implementation(libs.playservices.mlkit.vision.interfaces)
    implementation(libs.playservices.mlkit.barcode.scanning.mlkit)
    implementation(libs.playservices.mlkit.text.recognition.mlkit)
    implementation(libs.playservices.mlkit.document.scanner.mlkit)
    implementation(libs.playservices.mlkit.face.detection.mlkit)
    implementation(libs.playservices.mlkit.image.labeling.mlkit)
    implementation(libs.playservices.mlkit.image.labeling.custom.mlkit)
    implementation(libs.playservices.mlkit.language.id.mlkit)
    implementation(libs.playservices.mlkit.translate.mlkit)
    implementation(libs.playservices.mlkit.smart.reply.mlkit)
    implementation(libs.playservices.mlkit.entity.extraction.mlkit)
    implementation(libs.playservices.mlkit.digital.ink.recognition.mlkit)
    implementation(libs.playservices.mlkit.pose.detection.mlkit)
    implementation(libs.playservices.mlkit.pose.detection.accurate.mlkit)
    implementation(libs.playservices.mlkit.segmentation.selfie.mlkit)
    implementation(libs.playservices.mlkit.subject.segmentation.mlkit)
    implementation(libs.playservices.mlkit.object.detection.mlkit)
    implementation(libs.playservices.mlkit.object.detection.custom.mlkit)
    implementation(libs.playservices.mlkit.vision.common.mlkit)
    implementation(libs.playservices.mlkit.vision.interfaces.mlkit)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Apply Google Services plugin only if google-services.json is present.
// The file exists in this repo; keep strict behavior when present.
val googleServicesJson = file("google-services.json")
if (googleServicesJson.exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("google-services.json not found; skipping Google Services plugin.")
}
