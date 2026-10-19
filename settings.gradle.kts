pluginManagement {
    repositories {
        // The mod toolchain plugin (cn.enaium.fabricpvz.loom) comes from the Gradle Plugin Portal,
        // which serves the version this build states (1.0.1). No mavenLocal() line: the workspace
        // published the plugin there while 1.0.1 was unreleased, and the portal is the online source
        // of it now.
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Online first: what this build resolves comes from the repositories that publish it.
        mavenCentral()
        // The API's own modules resolve net.fabricmc:fabric-loader and net.fabricmc:sponge-mixin
        // from Fabric's maven (neither is on Maven Central); the API's event bus is in-tree.
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        // The workspace's own publication: cn.enaium.fabricpvz:fabric-api:1.2.1, the API this mod
        // declares (build.gradle.kts), lives here - `(cd ../fabric-api && ./gradlew
        // publishToMavenLocal)` publishes it - until Maven Central serves the release; the
        // declaration names the version either way, so nothing changes here when it does.
    }
}

rootProject.name = "pvz-mod-95-edition"
