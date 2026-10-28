plugins {
    id("net.neoforged.moddev") version "2.0.148"
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

// The adapter drifts between Minecraft series, so each has its own source
// directory ("1.21", "26"). From 26.1 Minecraft ships with its own names, which
// Fabric now sees too, so that series' adapter is shared with the fabric26 module
// and this one adds only its entry point.
val mcSeries = (findProperty("mcSeries") as String?)
    ?: if (minecraftVersion.startsWith("1.")) minecraftVersion.split(".").take(2).joinToString(".")
    else minecraftVersion.split(".").first()

sourceSets["main"].java.srcDir("src/mc$mcSeries/java")

if (mcSeries == "26") {
    sourceSets["main"].java.srcDir("../vanilla/src/mc26/java")
}

dependencies {
    implementation(project(":core"))
}

// Minecraft 1.21 runs on Java 21 and 26.x on Java 25, unlike the 1.20-era Bukkit
// target. NeoForge's tooling also needs that real JDK to build the Minecraft
// artifacts, so pin the toolchain rather than just the bytecode level.
val javaVersion = if (minecraftVersion.startsWith("1.")) 21 else 25

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
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
    // The jar loads only on the Minecraft it was built for and its patches
    // ("[1.21.1,1.22)", "[26.3,26.4)"), and on NeoForge from that line on.
    val parts = minecraftVersion.split(".")
    val minecraftRange = "[$minecraftVersion,${parts[0]}.${parts[1].toInt() + 1})"
    val neoForgeRange = "[" + neoForgeVersion.split(".").take(2).joinToString(".") + ",)"
    inputs.property("version", modVersion)
    inputs.property("minecraftRange", minecraftRange)
    inputs.property("neoForgeRange", neoForgeRange)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to modVersion, "minecraftRange" to minecraftRange, "neoForgeRange" to neoForgeRange)
    }
}
