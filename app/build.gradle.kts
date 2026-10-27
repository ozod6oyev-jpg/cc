plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.perfecttap"
    compileSdk = 34

    defaultConfig {
        applicationId = "uz.perfecttap"
        minSdk = 26
        targetSdk = 34
        // Har bosqich: versionCode = bosqich raqami, versionName = 0.<bosqich>.0
        versionCode = 10
        versionName = "0.6.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

/**
 * Copies every APK produced by a build into <project root>/apk, so the latest
 * installable file is always easy to find (e.g. ./apk/app-debug.apk).
 * A plain doLast copy is used instead of a Copy task: declaring a project
 * directory as a task output conflicts with AGP's own output metadata tasks.
 */
val apkOutputDir = layout.buildDirectory.dir("outputs/apk")
val apkDropDir = File(rootProject.layout.projectDirectory.asFile, "apk")

val copyApks by tasks.registering {
    group = "build"
    description = "Copies built APK files into the apk/ folder in the project root"
    doLast {
        val apkDir = apkOutputDir.get().asFile
        val apks = apkDir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        if (apks.isEmpty()) {
            logger.lifecycle("No APK found in ${apkDir.absolutePath}; nothing copied")
            return@doLast
        }
        apkDropDir.mkdirs()
        apks.forEach { apk ->
            val target = File(apkDropDir, apk.name)
            apk.copyTo(target, overwrite = true)
            logger.lifecycle("APK copied: ${target.absolutePath}")
        }
    }
}

afterEvaluate {
    listOf("assembleDebug", "assembleRelease").forEach { taskName ->
        tasks.findByName(taskName)?.finalizedBy(copyApks)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    // Rootsiz bosish yo'li: Shizuku (shell) orqali InputManager.injectInputEvent.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
