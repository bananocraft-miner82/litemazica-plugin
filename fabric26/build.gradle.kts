plugins {
    id("net.fabricmc.fabric-loom") version "1.19.3"
}

// Fabric from Minecraft 26.1 on. Minecraft now ships with its own names, so
// there are no Yarn mappings and nothing to remap: this module compiles against
// the same classes NeoForge does and shares its adapter (../vanilla). The older
// versions, with Yarn names, stay in the fabric module. Overridable so CI can
// build the version matrix.
//
// The fabric module reads the same property names, and Gradle configures both
// modules whichever one is built, so properties naming a 1.x version are that
// module's: this one then keeps its defaults.
val requested = findProperty("minecraftVersion") as String?
val forThis = requested != null && !requested.startsWith("1.")
fun ownProperty(name: String) = if (forThis) findProperty(name) as String? else null
val minecraftVersion = ownProperty("minecraftVersion") ?: "26.3"
val loaderVersion = ownProperty("loaderVersion") ?: "0.20.1"
val fabricApiVersion = ownProperty("fabricApiVersion") ?: "0.165.0+26.3"

sourceSets["main"].java.srcDir("../vanilla/src/mc26/java")

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(project(":core"))

    // Starts Minecraft's classes for the tests, so ConnectionCheckTest can ask
    // the game how it joins the web app's fences and walls.
    testImplementation("net.fabricmc:fabric-loader-junit:$loaderVersion")
}

// Minecraft 26.x runs on Java 25.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

base {
    // Mod jars are per-Minecraft-version, so the version belongs in the name.
    archivesName.set("Litemazica-fabric-mc$minecraftVersion")
}

tasks.jar {
    // core has no dependencies of its own, so bundling its classes is enough.
    from(project(":core").sourceSets["main"].output)
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    inputs.property("minecraft", minecraftVersion)

    // The jar loads only on the Minecraft it was built for (and its patches).
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion, "minecraft" to minecraftVersion)
    }
}
