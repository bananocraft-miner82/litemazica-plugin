// litemazica-core: pure Java, no Minecraft platform on the classpath at all.
//
// That constraint is the point — everything here (API client, NBT reading,
// schematic decoding, placement geometry, naming, scheduling policy) is shared
// by the Bukkit plugin and the Fabric/NeoForge mods, and is unit-testable
// without a server. Platforms are reached only through the interfaces in
// app.litemazica.core.platform.
//
// Do not add a Minecraft dependency to this module.
