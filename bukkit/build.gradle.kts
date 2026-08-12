repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

// Overridable so CI can re-compile against newer APIs and prove the supported
// range still holds:  ./gradlew :bukkit:compileJava -PspigotApiVersion=1.21.4-R0.1-SNAPSHOT
val spigotApiVersion = (findProperty("spigotApiVersion") as String?) ?: "1.20.1-R0.1-SNAPSHOT"

dependencies {
    implementation(project(":core"))

    // Spigot API, not Paper: Paper and Purpur are supersets, so one jar runs on
    // all three. Compile against the OLDEST version supported, never the newest.
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion")
    testImplementation("org.spigotmc:spigot-api:$spigotApiVersion")
}

tasks.jar {
    // Stable name for the server's plugins/ folder; the release workflow renames
    // the asset with a version.
    archiveBaseName.set("Litemazica")
    archiveVersion.set("")

    // The server has no core module on its classpath, so bundle it. Core has no
    // dependencies of its own, which is why this needs no shading.
    from(project(":core").sourceSets["main"].output)
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)

    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}
