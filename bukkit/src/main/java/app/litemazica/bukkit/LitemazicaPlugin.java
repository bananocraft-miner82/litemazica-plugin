package app.litemazica.bukkit;

import app.litemazica.bukkit.command.LitemazicaCommand;
import app.litemazica.bukkit.platform.BukkitPlatform;
import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.maze.MazeRegistry;
import app.litemazica.core.maze.MazeService;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. Loads config, builds the API client, restores placed mazes and
 * starts the regeneration scheduler, and wires the {@code /litemazica} command.
 *
 * <p>All the actual behaviour lives in {@link MazeService} in the core module;
 * this class only supplies the Bukkit-shaped {@link BukkitPlatform} it runs on.
 */
public final class LitemazicaPlugin extends JavaPlugin implements Listener
{
    private BukkitPlatform platform;
    private MazeService service;

    @Override
    public void onEnable()
    {
        saveDefaultConfig();
        this.platform = new BukkitPlatform(this);
        this.service = new MazeService(platform, buildClient());
        service.load();
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("litemazica");

        if (command != null)
        {
            LitemazicaCommand handler = new LitemazicaCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        else
        {
            getLogger().severe("Command 'litemazica' is missing from plugin.yml — commands unavailable.");
        }

        service.startScheduler();
        getLogger().info("Litemazica enabled. API base URL: " + service.client().baseUrl());
    }

    @Override
    public void onDisable()
    {
        if (service != null)
        {
            service.stopScheduler();
            service.saveNow(); // shutdown: the async scheduler is gone, write inline
        }
    }

    /** A leaving player's open editor session can't be applied — stop polling it. */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        if (service != null)
        {
            service.stopEditor(event.getPlayer().getName());
        }
    }

    /** Re-reads config.yml and rebuilds the API client. */
    public void reloadPlugin()
    {
        reloadConfig();
        boolean wasScheduled = service != null;

        if (wasScheduled)
        {
            service.stopScheduler();
        }

        MazeService rebuilt = new MazeService(platform, buildClient());
        rebuilt.load();
        this.service = rebuilt;
        rebuilt.startScheduler();
    }

    public MazeService service()
    {
        return service;
    }

    public MazeRegistry registry()
    {
        return service.registry();
    }

    public LitemazicaClient client()
    {
        return service.client();
    }

    private LitemazicaClient buildClient()
    {
        return new LitemazicaClient(
                getConfig().getString("api-base-url", "https://litemazica.app"),
                getConfig().getInt("request-timeout-seconds", 20));
    }
}
