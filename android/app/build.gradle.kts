import java.util.Properties

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
    namespace = "app.playhead.browser"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "app.playhead.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        // Where the app looks for updates. Files: manifest.json, betteryt.user.js, playhead.apk.
        buildConfigField("String", "UPDATE_BASE", "\"https://github.com/NFT1001/playhead/releases/latest/download/\"")
    }

    // The release key lives outside the repo. Point PLAYHEAD_SIGNING at its signing.properties file.
    val signingFile = System.getenv("PLAYHEAD_SIGNING")?.let { File(it) }
    if (signingFile != null && signingFile.isFile) {
        val sp = Properties().apply { signingFile.inputStream().use { s -> load(s) } }
        signingConfigs {
            create("release") {
                storeFile = File(sp.getProperty("storeFile"))
                storePassword = sp.getProperty("storePassword")
                keyAlias = sp.getProperty("keyAlias")
                keyPassword = sp.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
