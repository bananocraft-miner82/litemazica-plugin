package app.litemazica.vanilla;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.maze.MazeService;
import app.litemazica.vanilla.platform.VanillaPlatform;

import net.minecraft.server.MinecraftServer;

import java.io.File;

/**
 * The running mod, whichever loader started it. From 26.1 Minecraft ships with
 * its own names, so Fabric and NeoForge see the same classes and share this
 * adapter; each loader only wires its events to these four calls.
 */
public final class LitemazicaRuntime
{
    private static VanillaPlatform platform;
    private static MazeService service;

    private LitemazicaRuntime()
    {
    }

    /** The live service, or null before the server has started. */
    public static MazeService service()
    {
        return service;
    }

    /** Once the server has started: load the placed mazes and start their schedules. */
    public static void start(MinecraftServer server)
    {
        File dataFolder = server.getServerDirectory().resolve("litemazica").toFile();
        platform = new VanillaPlatform(server, dataFolder);
        service = new MazeService(platform, buildClient());
        service.load();
        service.startScheduler();
        platform.logger().info("Litemazica ready. API base URL: " + service.client().baseUrl());
    }

    /** Once per server tick: neither loader has a scheduler, so core's work is pumped from here. */
    public static void tick()
    {
        if (platform != null)
        {
            platform.schedulerImpl().tick();
        }
    }

    /** As the server stops. */
    public static void stop()
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
