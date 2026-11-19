pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlin.version").get()
    val preprocessorVersion = providers.gradleProperty("preprocessor.version").get()

    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
        id("com.replaymod.preprocess-root") version preprocessorVersion
    }

    repositories {
        gradlePluginPortal()

        exclusiveContent {
            forRepository {
                maven("https://maven.fabricmc.net")
            }

            filter {
                includeGroupByRegex("net\\.fabricmc(\\..*)?")
            }
        }

        exclusiveContent {
            forRepository {
                maven("https://jitpack.io")
            }

            filter {
                includeGroupByRegex("com\\.github\\.replaymod(\\..*)?")
            }
        }
    }

    // Upstream preprocessor (no DGT): it is only published on JitPack, under a different module name.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.replaymod.preprocess-root") {
                useModule("com.github.replaymod:preprocessor:$preprocessorVersion")
            }
        }
    }
}

rootProject.name = "guilib"
rootProject.buildFileName = "root.gradle.kts"

listOf(
    "26.1.2-fabric",
    "26.2-fabric",
    "26.3-fabric"
).forEach { version ->
    include(":$version")
    project(":$version").apply {
        projectDir = file("versions/$version")
        buildFileName = "../../build.gradle.kts"
    }
}
