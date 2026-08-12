plugins {
    id("net.neoforged.moddev") version "2.0.142"
}

// NeoForge exists only for 1.20.2+, and like Fabric it compiles against
// Minecraft's own classes — so this is per-Minecraft-version too. NeoForge uses
// Mojang mappings, which is why the adapter classes here read differently from
// the Fabric ones despite doing the same job.
val neoForgeVersion = (findProperty("neoForgeVersion") as String?) ?: "21.1.243"
val minecraftVersion = (findProperty("neoMinecraftVersion") as String?) ?: "1.21.1"

neoForge {
    version = neoForgeVersion
}

dependencies {
    implementation(project(":core"))
}

// Minecraft 1.21.1 runs on Java 21, unlike the 1.20-era Bukkit target. NeoForge's
// tooling also needs a real JDK 21 to build the Minecraft artifacts, so pin the
// toolchain rather than just the bytecode level.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

base {
    archivesName.set("Litemazica-neoforge-mc$minecraftVersion")
}

tasks.jar {
    // core has no dependencies of its own, so bundling its classes is enough.
    from(project(":core").sourceSets["main"].output)
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to modVersion)
    }
}
