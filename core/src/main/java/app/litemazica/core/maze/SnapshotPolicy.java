package app.litemazica.core.maze;

import app.litemazica.core.platform.ConfigSource;

import java.io.File;

/**
 * The config-driven policy around terrain snapshots — whether they're on, how
 * large a maze is worth capturing, and where a maze's snapshot folder lives —
 * split out from {@link MazeService} so the rules can be pinned without a server.
 * The capture/restore mechanics themselves live in {@link TerrainSnapshot}.
 */
final class SnapshotPolicy
{
    private final ConfigSource config;
    private final File dataFolder;

    SnapshotPolicy(ConfigSource config, File dataFolder)
    {
        this.config = config;
        this.dataFolder = dataFolder;
    }

    boolean enabled()
    {
        return config.getBoolean("snapshot", true);
    }

    /**
     * Largest maze (in blocks) still worth snapshotting. Defaults to the biggest
     * buildable maze so every maze is restorable unless the admin lowers it —
     * matching config.yml and the documented guarantee.
     */
    long maxVolume()
    {
        return config.getLong("snapshot-max-volume", 6_000_000L);
    }

    /** A maze's snapshot folder — a manifest plus one file per world chunk it covers. */
    File dir(String id)
    {
        return new File(new File(dataFolder, "snapshots"), id);
    }

    /** True when snapshots are on and one was actually written for {@code id}. */
    boolean has(String id)
    {
        return enabled() && TerrainSnapshot.exists(dir(id));
    }
}
