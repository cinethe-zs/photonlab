import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.photonlab.MainKt"

        nativeDistributions {
            // On Windows: output to TEMP so Windows Defender/Search Indexer never hold a handle on the exe dir.
            // On Linux: output to dist/ inside the project.
            outputBaseDir.set(project.objects.directoryProperty().apply {
                set(if (System.getProperty("os.name").startsWith("Windows"))
                    File(System.getProperty("java.io.tmpdir"), "photonlab-build5")
                else
                    project.rootDir.resolve("dist"))
            })
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Exe)
            packageName = "photonlab"
            packageVersion = "2.0.1"
            description = "PhotonLab - Non-destructive photo editor"
            vendor = "PhotonLab"

            val appIcon = if (System.getProperty("os.name").startsWith("Windows"))
                project.file("src/main/resources/photonlab.ico")
            else
                project.file("src/main/resources/photonlab_icon.png")
            fileAssociation("image/jpeg",              "jpg",  "JPEG Image",  appIcon)
            fileAssociation("image/jpeg",              "jpeg", "JPEG Image",  appIcon)
            fileAssociation("image/png",               "png",  "PNG Image",   appIcon)
            fileAssociation("image/webp",              "webp", "WebP Image",  appIcon)
            fileAssociation("application/octet-stream","cube", "3D LUT File", appIcon)

            modules("jdk.unsupported", "java.management", "jdk.unsupported.desktop")

            windows {
                iconFile.set(project.file("src/main/resources/photonlab.ico"))
                dirChooser = true
                perUserInstall = true
                shortcut = true
                menuGroup = "PhotonLab"
                upgradeUuid = "C7E4A2B1-8D6F-4E3A-9B2C-1A2B3C4D5E6F"
            }

            linux {
                iconFile.set(project.file("src/main/resources/photonlab_icon.png"))
                menuGroup = "Graphics"
                shortcut = true
            }
        }
    }
}


// Redirect KMP metadata artifacts (only on blocked Google Maven) to their
// JVM-specific counterparts, which are available on Maven Central.
configurations.all {
    resolutionStrategy.eachDependency {
        val jvmOnlyGroups = setOf("androidx.annotation", "androidx.collection", "androidx.lifecycle")
        // "lifecycle-runtime-jvm" does not exist on any Maven repo; keep only artifacts
        // that actually have a published -jvm variant on Google Maven.
        val jvmSuffixNames = setOf("annotation", "collection", "lifecycle-common")
        if (requested.group in jvmOnlyGroups && requested.name in jvmSuffixNames) {
            useTarget("${requested.group}:${requested.name}-jvm:${requested.version}")
        }
    }
}

val lwjglVersion = libs.versions.lwjgl.get()
val lwjglNatives = when {
    System.getProperty("os.name").startsWith("Windows") -> "natives-windows"
    else -> "natives-linux"
}

dependencies {
    // Compose Multiplatform Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines (Swing dispatcher for EDT integration)
    implementation(libs.kotlinx.coroutines.swing)

    // JSON (preset persistence)
    implementation("org.json:json:20231013")

    // EXIF metadata reading (date imprint)
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    // LWJGL (OpenGL off-screen rendering)
    implementation(libs.lwjgl.core)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.opengl)
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglNatives")
}
