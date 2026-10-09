plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The script lives once, in core/. This task copies it into the app's assets at build time.
abstract class SyncCore : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.mkdirs()
        script.get().asFile.copyTo(File(out, "betteryt.js"), overwrite = true)
    }
}

val syncCore = tasks.register<SyncCore>("syncCore") {
    script.set(rootProject.layout.projectDirectory.file("../core/betteryt.user.js"))
}

android {
    namespace = "app.betteryt.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.betteryt.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncCore, SyncCore::outputDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
