plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "it.michelegiammarini.sleeppausetv"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.michelegiammarini.sleeppausetv"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.0.0-universal"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += "tflite" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

val yamnetModel = layout.projectDirectory.file("src/main/assets/yamnet.tflite")
val yamnetModelUrl = providers.gradleProperty("yamnetModelUrl").orElse(
    "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite"
)

val downloadYamnetModel by tasks.registering {
    description = "Download the metadata-enabled YAMNet TFLite model into the app assets"
    group = "build setup"
    outputs.file(yamnetModel)
    doLast {
        val target = yamnetModel.asFile
        if (!target.exists() || target.length() < 1_000_000L) {
            target.parentFile.mkdirs()
            val temporary = target.resolveSibling("${target.name}.part")
            logger.lifecycle("Download YAMNet from ${yamnetModelUrl.get()}")
            uri(yamnetModelUrl.get()).toURL().openStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() > 1_000_000L) { "The downloaded YAMNet file is invalid" }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not install ${target.absolutePath}" }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(downloadYamnetModel) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
