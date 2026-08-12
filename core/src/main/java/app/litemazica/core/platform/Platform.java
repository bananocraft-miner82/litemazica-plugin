package app.litemazica.core.platform;

import java.io.File;
import java.util.logging.Logger;

/**
 * Everything core needs from its host. Implemented once per distribution
 * (Bukkit, Fabric, NeoForge); core depends on nothing else platform-shaped.
 */
public interface Platform
{
    Logger logger();

    /** Where mazes, snapshots and config live. */
    File dataFolder();

    ConfigSource config();

    Scheduler scheduler();

    PlayerLookup players();

    /** @return null when that world isn't loaded. */
    WorldAccess world(String worldName);
}
