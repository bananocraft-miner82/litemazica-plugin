package app.litemazica.fabric.platform;

import app.litemazica.core.maze.Region;
import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.ConfigSource;
import app.litemazica.core.platform.Platform;
import app.litemazica.core.platform.PlayerLookup;
import app.litemazica.core.platform.PlayerPose;
import app.litemazica.core.platform.PropertiesConfig;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.TickScheduler;
import app.litemazica.core.platform.WorldAccess;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Binds core to a running dedicated server. */
public final class FabricPlatform implements Platform
{
    private static final Logger LOGGER = Logger.getLogger("Litemazica");

    private final MinecraftServer server;
    private final File dataFolder;
    private final TickScheduler scheduler;
    private final PropertiesConfig config;
    private final PlayerLookup players = new FabricPlayers();

    public FabricPlatform(MinecraftServer server, File dataFolder)
    {
        this.server = server;
        this.dataFolder = dataFolder;
        this.scheduler = new TickScheduler(server::execute, LOGGER);
        this.config = new PropertiesConfig(new File(dataFolder, "config.properties"), PropertiesConfig.DEFAULTS);
    }

    /** Drives per-tick work; call once per server tick. */
    public TickScheduler schedulerImpl()
    {
        return scheduler;
    }

    public void reloadConfig()
    {
        config.reload();
    }

    @Override
    public Logger logger()
    {
        return LOGGER;
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

    /**
     * Fabric has no permission system, so "admin" is op level 2 — the same gate
     * the command tree puts its admin subcommands behind. Re-scan on every send:
     * an op may join between wiring and the announcement.
     */
    @Override
    public Audience admins()
    {
        return (style, text) ->
        {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())
            {
                if (player.hasPermissionLevel(2))
                {
                    new FabricAudience(player.getCommandSource()).send(style, text);
                }
            }
        };
    }

    /**
     * Worlds are addressed by dimension id ({@code "minecraft:overworld"}), which
     * is the closest thing Fabric has to a Bukkit world name.
     */
    @Override
    public WorldAccess world(String worldName)
    {
        ServerWorld world = resolve(worldName);
        return world == null ? null : new FabricWorldAccess(world, LOGGER);
    }

    private ServerWorld resolve(String worldName)
    {
        Identifier id = Identifier.tryParse(worldName);

        if (id == null)
        {
            return null;
        }

        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }

    private final class FabricPlayers implements PlayerLookup
    {
        @Override
        public List<String> namesInside(String worldName, Region region, String exemptName)
        {
            ServerWorld world = resolve(worldName);

            if (world == null)
            {
                return List.of();
            }

            List<String> names = new ArrayList<>();

            for (ServerPlayerEntity player : world.getPlayers())
            {
                String name = player.getGameProfile().getName();

                if (name.equals(exemptName))
                {
                    continue;
                }

                // Spectators pass through blocks and take no damage, so building
                // or resetting around one affects nothing — don't let it block.
                if (player.isSpectator())
                {
                    continue;
                }

                BlockPos pos = player.getBlockPos();

                if (region.contains(pos.getX(), pos.getY(), pos.getZ()))
                {
                    names.add(name);
                }
            }

            return names;
        }

        @Override
        public boolean teleport(String playerName, String worldName,
                                double x, double y, double z, float yaw, float pitch)
        {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
            ServerWorld world = resolve(worldName);

            if (player == null || world == null)
            {
                return false;
            }

            player.teleport(world, x, y, z, yaw, pitch);
            return true;
        }

        @Override
        public PlayerPose poseOf(String playerName)
        {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);

            if (player == null)
            {
                return null;
            }

            BlockPos pos = player.getBlockPos();
            return new PlayerPose(nameOf(player.getServerWorld()), pos.getX(), pos.getY(), pos.getZ(), player.getYaw());
        }
    }

    /** The dimension id string core should store for a world. */
    public static String nameOf(World world)
    {
        return world.getRegistryKey().getValue().toString();
    }
}
