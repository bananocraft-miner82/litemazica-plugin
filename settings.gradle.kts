pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle download the JDK a module asks for (the mod loaders need 21)
// rather than requiring every machine to have it installed.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "litemazica"

// Platform-neutral logic, shared by every distribution.
include("core")
// Bukkit/Spigot/Paper/Purpur plugin.
include("bukkit")
// Fabric server-side mod.
include("fabric")
// Fabric from Minecraft 26.1 on (no Yarn names to remap; shares ../vanilla with NeoForge).
include("fabric26")
// NeoForge server-side mod.
include("neoforge")
