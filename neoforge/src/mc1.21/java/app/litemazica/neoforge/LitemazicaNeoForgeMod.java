package app.litemazica.neoforge;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.maze.MazeService;
import app.litemazica.neoforge.command.LitemazicaCommands;
import app.litemazica.neoforge.platform.NeoForgePlatform;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.File;

/**
 * Server-side entry point. All behaviour lives in {@link MazeService} in the
 * core module; this only supplies the NeoForge-shaped {@link NeoForgePlatform}
 * it runs on, and pumps the scheduler once per tick.
 */
@Mod("litemazica")
public final class LitemazicaNeoForgeMod
{
    private static NeoForgePlatform platform;
    private static MazeService service;

    public LitemazicaNeoForgeMod()
    {
        NeoForge.EVENT_BUS.register(this);
    }

    /** The live service, or null before the server has started. */
    public static MazeService service()
    {
        return service;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        LitemazicaCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event)
    {
        File dataFolder = event.getServer().getServerDirectory().resolve("litemazica").toFile();
        platform = new NeoForgePlatform(event.getServer(), dataFolder);
        service = new MazeService(platform, buildClient());
        service.load();
        service.startScheduler();
        platform.logger().info("Litemazica ready. API base URL: " + service.client().baseUrl());
    }

    /** NeoForge has no scheduler, so core's per-tick work is pumped from here. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event)
    {
        if (platform != null)
        {
            platform.schedulerImpl().tick();
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        if (service != null)
        {
            service.stopScheduler();
            service.saveNow(); // shutdown: async tasks will not run
            platform.schedulerImpl().shutdown();
            service = null;
            platform = null;
        }
    }

    /** Rebuilds the service against re-read config. */
    public static void reload()
    {
        if (platform == null || service == null)
        {
            return;
        }

        platform.reloadConfig();
        service.stopScheduler();
        MazeService rebuilt = new MazeService(platform, buildClient());
        rebuilt.load();
        service = rebuilt;
        rebuilt.startScheduler();
    }

    private static LitemazicaClient buildClient()
    {
        return new LitemazicaClient(
                platform.config().getString("api-base-url", "https://litemazica.app"),
                platform.config().getInt("request-timeout-seconds", 20));
    }
}
