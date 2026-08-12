package app.litemazica.neoforge.platform;

import app.litemazica.core.maze.Region;
import app.litemazica.core.platform.ConfigSource;
import app.litemazica.core.platform.Platform;
import app.litemazica.core.platform.PlayerLookup;
import app.litemazica.core.platform.PlayerPose;
import app.litemazica.core.platform.PropertiesConfig;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.TickScheduler;
import app.litemazica.core.platform.WorldAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Binds core to a running NeoForge dedicated server. */
public final class NeoForgePlatform implements Platform
{
    private static final Logger LOGGER = Logger.getLogger("Litemazica");

    private final MinecraftServer server;
    private final File dataFolder;
    private final TickScheduler scheduler;
    private final PropertiesConfig config;
    private final PlayerLookup players = new NeoForgePlayers();

    public NeoForgePlatform(MinecraftServer server, File dataFolder)
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

    /** Worlds are addressed by dimension id, e.g. {@code "minecraft:overworld"}. */
    @Override
    public WorldAccess world(String worldName)
    {
        ServerLevel level = resolve(worldName);
        return level == null ? null : new NeoForgeWorldAccess(level, LOGGER);
    }

    private ServerLevel resolve(String worldName)
    {
        ResourceLocation id = ResourceLocation.tryParse(worldName);

        if (id == null)
        {
            return null;
        }

        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private final class NeoForgePlayers implements PlayerLookup
    {
        @Override
        public List<String> namesInside(String worldName, Region region, String exemptName)
        {
            ServerLevel level = resolve(worldName);

            if (level == null)
            {
                return List.of();
            }

            List<String> names = new ArrayList<>();

            for (ServerPlayer player : level.players())
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

                BlockPos pos = player.blockPosition();

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
            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
            ServerLevel level = resolve(worldName);

            if (player == null || level == null)
            {
                return false;
            }

            player.teleportTo(level, x, y, z, yaw, pitch);
            return true;
        }

        @Override
        public PlayerPose poseOf(String playerName)
        {
            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);

            if (player == null)
            {
                return null;
            }

            BlockPos pos = player.blockPosition();
            return new PlayerPose(nameOf(player.serverLevel()),
                    pos.getX(), pos.getY(), pos.getZ(), player.getYRot());
        }
    }

    /** The dimension id string core should store for a world. */
    public static String nameOf(Level level)
    {
        return level.dimension().location().toString();
    }
}
