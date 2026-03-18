pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Local stubs for androidx.* packages that require Google Maven (blocked in CI).
        // These are annotation/interface-only artifacts with no runtime behaviour on JVM Desktop.
        maven {
            url = uri("${rootDir}/libs")
            content {
                includeGroup("androidx.annotation")
                includeGroup("androidx.collection")
                includeGroup("androidx.lifecycle")
                includeGroup("androidx.arch.core")
            }
        }
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "photonlab-desktop"
