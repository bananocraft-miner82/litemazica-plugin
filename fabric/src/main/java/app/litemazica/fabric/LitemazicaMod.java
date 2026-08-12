package app.litemazica.fabric;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.maze.MazeService;
import app.litemazica.fabric.command.LitemazicaCommands;
import app.litemazica.fabric.platform.FabricPlatform;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;

/**
 * Server-side entry point. All behaviour lives in {@link MazeService} in the
 * core module; this only supplies the Fabric-shaped {@link FabricPlatform} it
 * runs on, and pumps the scheduler once per tick.
 */
public final class LitemazicaMod implements DedicatedServerModInitializer
{
    private static FabricPlatform platform;
    private static MazeService service;

    /** The live service, or null before the server has started. */
    public static MazeService service()
    {
        return service;
    }

    public static FabricPlatform platform()
    {
        return platform;
    }

    @Override
    public void onInitializeServer()
    {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> LitemazicaCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
        {
            File dataFolder = FabricLoader.getInstance().getGameDir().resolve("litemazica").toFile();
            platform = new FabricPlatform(server, dataFolder);
            service = new MazeService(platform, buildClient());
            service.load();
            service.startScheduler();
            platform.logger().info("Litemazica ready. API base URL: " + service.client().baseUrl());
        });

        // Fabric has no scheduler, so core's per-tick work is pumped from here.
        ServerTickEvents.END_SERVER_TICK.register(server ->
        {
            if (platform != null)
            {
                platform.schedulerImpl().tick();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
        {
            if (service != null)
            {
                service.stopScheduler();
                service.saveNow(); // shutdown: async tasks will not run
                platform.schedulerImpl().shutdown();
                service = null;
                platform = null;
            }
        });
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
