package app.litemazica.core.maze;

import app.litemazica.core.platform.ConfigSource;
import app.litemazica.core.platform.Platform;
import app.litemazica.core.platform.PlayerLookup;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.WorldAccess;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A whole-server stand-in wiring the fake config, scheduler, players and worlds together. */
final class FakePlatform implements Platform
{
    final FakeConfig config = new FakeConfig();
    final TestScheduler scheduler = new TestScheduler();
    final FakePlayers players = new FakePlayers();
    private final Map<String, WorldAccess> worlds = new HashMap<>();
    private final File dataFolder;
    private final Logger logger;

    FakePlatform(File dataFolder)
    {
        this.dataFolder = dataFolder;
        this.logger = Logger.getAnonymousLogger();
        this.logger.setLevel(Level.OFF);
    }

    /** Registers (or replaces) a world, keyed by its own name. */
    FakePlatform withWorld(WorldAccess world)
    {
        worlds.put(world.name(), world);
        return this;
    }

    /** Simulates a world unloading. */
    void unload(String worldName)
    {
        worlds.remove(worldName);
    }

    @Override
    public Logger logger()
    {
        return logger;
    }

    @Override
    public File dataFolder()
    {
        return dataFolder;
    }

    @Override
    public ConfigSource config()
    {
        return config;
    }

    @Override
    public Scheduler scheduler()
    {
        return scheduler;
    }

    @Override
    public PlayerLookup players()
    {
        return players;
    }

    @Override
    public WorldAccess world(String worldName)
    {
        return worlds.get(worldName);
    }
}
