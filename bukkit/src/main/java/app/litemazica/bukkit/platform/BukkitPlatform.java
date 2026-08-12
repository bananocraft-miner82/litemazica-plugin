package app.litemazica.bukkit.platform;

import app.litemazica.core.maze.Region;
import app.litemazica.core.platform.ConfigSource;
import app.litemazica.core.platform.Platform;
import app.litemazica.core.platform.PlayerLookup;
import app.litemazica.core.platform.PlayerPose;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.WorldAccess;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/** Binds core to a running Bukkit server. */
public final class BukkitPlatform implements Platform
{
    private final Plugin plugin;
    private final Scheduler scheduler;
    private final PlayerLookup players;

    public BukkitPlatform(Plugin plugin)
    {
        this.plugin = plugin;
        this.scheduler = new BukkitScheduler(plugin);
        this.players = new BukkitPlayers();
    }

    @Override
    public Logger logger()
    {
        return plugin.getLogger();
    }

    @Override
    public File dataFolder()
    {
        return plugin.getDataFolder();
    }

    @Override
    public ConfigSource config()
    {
        // Read through on every call so /litemazica reload takes effect without
        // re-wiring anything.
        return new ConfigSource()
        {
            @Override
            public String getString(String key, String fallback)
            {
                return plugin.getConfig().getString(key, fallback);
            }

            @Override
            public int getInt(String key, int fallback)
            {
                return plugin.getConfig().getInt(key, fallback);
            }

            @Override
            public long getLong(String key, long fallback)
            {
                return plugin.getConfig().getLong(key, fallback);
            }

            @Override
            public boolean getBoolean(String key, boolean fallback)
            {
                return plugin.getConfig().getBoolean(key, fallback);
            }
        };
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
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new BukkitWorldAccess(world, plugin.getLogger());
    }

    /** Bukkit's scheduler, shaped to the core's slice-per-tick contract. */
    private record BukkitScheduler(Plugin plugin) implements Scheduler
    {
        @Override
        public void eachTick(BooleanSupplier slice, Runnable onDone)
        {
            new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    if (slice.getAsBoolean())
                    {
                        cancel();
                        onDone.run();
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }

        @Override
        public void async(Runnable work)
        {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, work);
        }

        @Override
        public void onMain(Runnable work)
        {
            plugin.getServer().getScheduler().runTask(plugin, work);
        }

        @Override
        public Cancellable everySeconds(long seconds, Runnable work)
        {
            long ticks = seconds * 20L;
            BukkitTask task = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, work, ticks, ticks);
            return task::cancel;
        }
    }

    private static final class BukkitPlayers implements PlayerLookup
    {
        @Override
        public List<String> namesInside(String worldName, Region region, String exemptName)
        {
            World world = Bukkit.getWorld(worldName);

            if (world == null)
            {
                return List.of();
            }

            List<String> names = new ArrayList<>();

            for (Player player : world.getPlayers())
            {
                if (player.getName().equals(exemptName))
                {
                    continue;
                }

                // Spectators pass through blocks and take no damage, so building
                // or resetting around one affects nothing — don't let it block.
                if (player.getGameMode() == GameMode.SPECTATOR)
                {
                    continue;
                }

                Location loc = player.getLocation();

                if (region.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()))
                {
                    names.add(player.getName());
                }
            }

            return names;
        }

        @Override
        public boolean teleport(String playerName, String worldName,
                                double x, double y, double z, float yaw, float pitch)
        {
            Player player = Bukkit.getPlayerExact(playerName);
            World world = Bukkit.getWorld(worldName);

            if (player == null || world == null)
            {
                return false;
            }

            return player.teleport(new Location(world, x, y, z, yaw, pitch));
        }

        @Override
        public PlayerPose poseOf(String playerName)
        {
            Player player = Bukkit.getPlayerExact(playerName);

            if (player == null)
            {
                return null;
            }

            Location loc = player.getLocation();
            return new PlayerPose(player.getWorld().getName(),
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getYaw());
        }
    }
}
