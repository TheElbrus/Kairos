// Separate Gradle build for the 1.8.9 Forge loader (ADR 0002).
// Adapted from lineargraph/Forge1.8.9Template (The Unlicense) -- see CREDITS.md.
//
// NOTE: this is a SEPARATE build from the repo root. The root build (Gradle 9.8.1) must
// stay fast and Minecraft-free so `:core:test` never pulls in Loom or Minecraft.

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net")
        maven("https://maven.minecraftforge.net/")
        maven("https://repo.spongepowered.org/maven/")
        maven("https://repo.essential.gg/repository/maven-releases/")
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                // `gg.essential.loom` is the Essential fork of Architectury Loom used for legacy (1.8.9) Forge.
                "gg.essential.loom" -> useModule("gg.essential:architectury-loom:${requested.version}")
            }
        }
    }
}

plugins {
    // Lets Gradle auto-provision a Java 8 toolchain if none is installed locally.
    // WARNING: this DOWNLOADS a JDK if no Java 8 is found (see docs/JOURNAL.md).
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.6.0")
}

rootProject.name = "kairos-loader"
