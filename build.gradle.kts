// The mod toolchain of this workspace: applying cn.enaium.fabricpvz.loom supplies the game
// distribution (the game, the API modules, the real Fabric Loader and Mixin, ASM, SLF4J/Log4j and
// LWJGL, on the compile, test and launch class paths), Kotlin, the launcher class path and JUnit for
// the end-to-end test, the runClient task and the publication of the mod jar under mavenLocal
// coordinates. A mod never declares any of those artifacts by hand; see loom.
//
// The plugin resolves from the Gradle Plugin Portal, which serves the version this build states
// (1.0.1, the runClient-that-loads-the-mod-from-the-class-path release): no mavenLocal() line is left
// in pluginManagement, because the portal is now the online source of it.
//
// This mod is written in Kotlin - main sources under src/main/kotlin, the entry point a `class` and
// not an `object` (Fabric Loader instantiates the entry point named in fabric.mod.json through its
// no-argument constructor), the stdlib compile-only and carried at runtime by the language module the
// mod requires - and it names the API version it is built against instead of taking whatever the
// toolchain pins (see the dependency block).
import org.gradle.api.artifacts.ModuleDependency

plugins {
    id("cn.enaium.fabricpvz.loom") version "1.0.9"
}

// The workspace's API as this mod declares it: the aggregate jar of cn.enaium.fabricpvz:fabric-api,
// version 1.2.1 - the release the workspace publishes, resolved from the repositories the settings
// file lists (mavenLocal today; Maven Central once the release is published there, which needs no
// change here).
val fabricApi = "cn.enaium.fabricpvz:fabric-api:1.2.1"

dependencies {
    // Which game distribution this mod is built against: the version package of the game's release,
    // i.e. the asset pvz-portable-<version>-online.zip of FabricPvZ/pvz-portable. The toolchain
    // downloads it once into ~/.gradle/caches/pvz (records its SHA-256 next to it) and unpacks it into
    // build/pvz/dist: the game jar goes on the class paths as a file, and every library the manifest
    // next to it names (loader, Mixin, ASM, logging, LWJGL with this machine's natives) is a Maven
    // coordinate.
    pvz("cn.enaium.fabricpvz:pvz-portable:1.2.5")

    // Compilation - this project's sources and its tests' - against the aggregate and, through its own
    // POM, the five feature modules nested in it: javac and kotlinc read jar files, not jars nested
    // inside a jar, so a compile class path has to carry the modules themselves.
    for (configuration in listOf("compileOnly", "testCompileOnly")) {
        add(configuration, fabricApi)
    }

    // Runtime - the test fork's launcher class path and runClient - against the aggregate alone: its
    // nested jars are the modules, for Fabric Loader as much as for anything else walking that class
    // path. Not transitive, so no second copy of a module ends up on a class path the loader walks.
    for (configuration in listOf("testImplementation", "pvzRunClasspath")) {
        (add(configuration, fabricApi) as ModuleDependency).isTransitive = false
    }
}

group = "cn.enaium.fabricpvz"
version = "1.0.0"
description = "95 Edition mod: the Chinese hard-mode remix of PvZ, as a resource overlay plus its rebalance"

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "PvZ 95 Edition Mod",
            "Implementation-Version" to project.version,
            "Fabric-Mod-Id" to "pvz-95-edition",
        )
    }
}
