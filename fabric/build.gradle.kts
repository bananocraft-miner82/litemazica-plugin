plugins {
    id("fabric-loom") version "1.17.17"
}

// Unlike the Bukkit plugin, a Fabric mod is bound to one Minecraft version:
// Loom compiles against Minecraft's own (remapped) classes, which change every
// release. These are overridable so CI can build the version matrix.
val minecraftVersion = (findProperty("minecraftVersion") as String?) ?: "1.20.1"
val yarnMappings = (findProperty("yarnMappings") as String?) ?: "1.20.1+build.10"
val loaderVersion = (findProperty("loaderVersion") as String?) ?: "0.15.11"
val fabricApiVersion = (findProperty("fabricApiVersion") as String?) ?: "0.92.2+1.20.1"

// Most of the adapter is stable across Minecraft versions; block-entity NBT and
// loot tables are not. Rather than reflection or a shim, the one class that
// drifts lives in a per-version source directory and the right one is compiled
// in. Adding a new series means adding src/mc<series>/java, not a new module.
val mcSeries = (findProperty("mcSeries") as String?)
    ?: minecraftVersion.split(".").take(2).joinToString(".")

sourceSets["main"].java.srcDir("src/mc$mcSeries/java")

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(project(":core"))
}

base {
    // Mod jars are per-Minecraft-version, so the version belongs in the name.
    archivesName.set("Litemazica-fabric-mc$minecraftVersion")
}

tasks.jar {
    // core has no dependencies of its own, so bundling its classes is enough —
    // no jar-in-jar or shading needed.
    from(project(":core").sourceSets["main"].output)
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)

    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}
