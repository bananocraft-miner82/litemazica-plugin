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

    /**
     * Every online admin, as one {@link Audience}. This is who work nobody asked
     * for interactively — a scheduled regeneration — announces itself to; a
     * command still replies to whoever ran it. Sends to no one when no admin is
     * online, so the message is simply dropped (the console log stays the durable
     * record). "Admin" means whoever the platform gates maze management behind:
     * the {@code litemazica.regen} permission on Bukkit, op level on the mods.
     */
    Audience admins();

    /** @return null when that world isn't loaded. */
    WorldAccess world(String worldName);
}
