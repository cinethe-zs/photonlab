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
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "photonlab"
            packageVersion = "1.2.1"
            description = "PhotonLab — Non-destructive photo editor"
            vendor = "PhotonLab"

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}

val lwjglVersion = libs.versions.lwjgl.get()
val lwjglNatives = "natives-linux"

dependencies {
    // Compose Multiplatform Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines (Swing dispatcher for EDT integration)
    implementation(libs.kotlinx.coroutines.swing)

    // JSON (preset persistence)
    implementation("org.json:json:20231013")

    // LWJGL (OpenGL off-screen rendering)
    implementation(libs.lwjgl.core)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.opengl)
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglNatives")
}
