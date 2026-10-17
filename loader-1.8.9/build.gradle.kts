// Thin Forge 1.8.9 mod for Kairos.
// Adapted from lineargraph/Forge1.8.9Template (The Unlicense) -- see CREDITS.md.
//
// Removed from the template: mixins (AutoDiscoveryMixinPlugin, MixinGuiMainMenu, mixin
// config, mixin deps, annotation processor) and DevAuth. This skeleton needs neither.
// Kept: essential Loom, architectury-pack200, shadow, MCP stable_22, Forge 1.8.9.

plugins {
    java
    id("gg.essential.loom") version "0.10.0.5"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val mcVersion: String by project
val version: String by project
val modid: String by project

// Java 8 toolchain: Minecraft 1.8.9 runs on a Java 8 JVM, so our classes must be Java 8.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

loom {
    log4jConfigs.from(file("log4j2.xml"))
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
    }
    runConfigs {
        // This project only runs a client.
        remove(getByName("server"))
    }
}

sourceSets.main {
    // Keep resources on the classpath of the dev client.
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}

repositories {
    mavenCentral()
}

// `core` is built by the SEPARATE root build (ADR 0002). Build it first:
//     ..\gradlew.bat :core:build
// then the jar below is shaded into the mod jar. Forge 1.8.9 has no Jar-in-Jar.
val coreJar = file("../core/build/libs/core-0.1.0-SNAPSHOT.jar")

val shadowImpl: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")

    // Bundle the pure-Java `core` library into the mod jar.
    // `add(name, files(...))` because Configuration.invoke(...) wants a String notation here.
    add(shadowImpl.name, files(coreJar))
}

tasks.withType(JavaCompile::class) {
    options.encoding = "UTF-8"
}

tasks.withType(org.gradle.jvm.tasks.Jar::class) {
    archiveBaseName.set(modid)
    manifest.attributes.run {
        // Tells Forge to load this jar as a mod in the dev environment.
        this["ForceLoadAsMod"] = "true"
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    inputs.property("modid", modid)
    filesMatching(listOf("mcmod.info")) {
        expand(inputs.properties)
    }
}

val remapJar by tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveClassifier.set("")
    from(tasks.shadowJar)
    input.set(tasks.shadowJar.get().archiveFile)
}

tasks.jar {
    archiveClassifier.set("without-deps")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
}

tasks.shadowJar {
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    archiveClassifier.set("non-obfuscated-with-deps")
    configurations = listOf(shadowImpl)
}

tasks.assemble.get().dependsOn(tasks.remapJar)

// --- Launch helper -----------------------------------------------------------------
// Loom 0.10.0.5's `RunGameTask extends JavaExec` exposes an unannotated `getMain()`,
// which Gradle 8.8 rejects during task validation, so `./gradlew runClient` fails with:
//   "property 'main' is missing an input or output annotation".
// This task prints the exact launch command Loom builds, without running the game, so a
// fixed runner around it can launch the client. (See docs/JOURNAL.md.)
tasks.register("printRunClientCommand") {
    val runClientTask = tasks.named<net.fabricmc.loom.task.RunGameTask>("runClient")
    doLast {
        val t = runClientTask.get()
        println("KAIROS_MAIN=" + t.main)
        println("KAIROS_WORKDIR=" + t.workingDir.absolutePath)
        println("KAIROS_ARGS=" + (t.args?.joinToString("\u0001") ?: ""))
        println("KAIROS_JVMARGS=" + (t.jvmArgs?.joinToString("\u0001") ?: ""))
        println("KAIROS_CP=" + t.classpath.asPath)
    }
}
