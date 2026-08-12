package app.litemazica.bukkit.platform;

import app.litemazica.core.maze.Region;
import app.litemazica.core.platform.BlockPos;
import app.litemazica.core.platform.DispenserItem;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.SpawnerConfig;
import app.litemazica.core.platform.WorldAccess;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/** {@link WorldAccess} over a Bukkit {@link World}. */
public final class BukkitWorldAccess implements WorldAccess
{
    private static final StructureRotation[] ROTATIONS =
    {
        StructureRotation.NONE,
        StructureRotation.CLOCKWISE_90,
        StructureRotation.CLOCKWISE_180,
        StructureRotation.COUNTERCLOCKWISE_90,
    };

    private final World world;
    private final Logger logger;

    public BukkitWorldAccess(World world, Logger logger)
    {
        this.world = world;
        this.logger = logger;
    }

    @Override
    public String name()
    {
        return world.getName();
    }

    @Override
    public int minY()
    {
        return world.getMinHeight();
    }

    @Override
    public int maxY()
    {
        return world.getMaxHeight();
    }

    @Override
    public PreparedPalette preparePalette(List<String> palette, int quarterTurnsClockwise)
    {
        StructureRotation rotation = ROTATIONS[Math.floorMod(quarterTurnsClockwise, 4)];
        BlockData[] data = new BlockData[palette.size()];

        for (int i = 0; i < data.length; i++)
        {
            BlockData parsed;

            try
            {
                parsed = Bukkit.createBlockData(palette.get(i));
            }
            catch (IllegalArgumentException e)
            {
                logger.warning("Unknown block '" + palette.get(i) + "' — using air.");
                parsed = Bukkit.createBlockData(Material.AIR);
            }

            if (rotation != StructureRotation.NONE)
            {
                parsed.rotate(rotation);
            }

            data[i] = parsed;
        }

        return new BukkitPalette(data);
    }

    @Override
    public void setBlock(int x, int y, int z, PreparedPalette palette, int index)
    {
        // Physics off: cascading updates would be ruinous mid-paste, and would
        // pop torches off walls that don't exist yet.
        world.getBlockAt(x, y, z).setBlockData(((BukkitPalette) palette).data[index], false);
    }

    @Override
    public void setBlockState(int x, int y, int z, String blockState)
    {
        BlockData data;

        try
        {
            data = Bukkit.createBlockData(blockState);
        }
        catch (IllegalArgumentException e)
        {
            logger.warning("Unknown block '" + blockState + "' — leaving the existing block.");
            return;
        }

        // Physics off, same as the palette body: a trap edit shouldn't cascade.
        world.getBlockAt(x, y, z).setBlockData(data, false);
    }

    @Override
    public void clearWithPhysics(int x, int y, int z)
    {
        // Physics on: breaking a log this way notifies the neighbouring leaves, so
        // any canopy we left just beyond the flood recomputes its distance and decays.
        world.getBlockAt(x, y, z).setType(Material.AIR, true);
    }

    @Override
    public void updateBlock(int x, int y, int z)
    {
        Block block = world.getBlockAt(x, y, z);
        BlockData data = block.getBlockData();
        // Re-applying identical data can be skipped, so toggle through air to
        // force a physics update: water resumes flowing, unstable blocks fall.
        block.setType(Material.AIR, false);
        block.setBlockData(data, true);
    }

    @Override
    public String blockStateAt(int x, int y, int z)
    {
        return world.getBlockAt(x, y, z).getBlockData().getAsString();
    }

    @Override
    public List<BlockPos> blockEntitiesIn(Region region)
    {
        // Per-chunk rather than per-block: getState() on every voxel of a large
        // region would be unusably slow.
        List<BlockPos> out = new ArrayList<>();

        for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++)
        {
            for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++)
            {
                Chunk chunk = world.getChunkAt(cx, cz);

                for (BlockState state : chunk.getTileEntities())
                {
                    if (region.contains(state.getX(), state.getY(), state.getZ()))
                    {
                        out.add(new BlockPos(state.getX(), state.getY(), state.getZ()));
                    }
                }
            }
        }

        return out;
    }

    /**
     * Bukkit exposes no general NBT API, so this covers the cases that matter on
     * terrain a maze might sit on and encodes them as YAML. (The Fabric/NeoForge
     * implementations can hand over the block entity's real NBT instead, and so
     * restore every type faithfully.)
     */
    @Override
    public byte[] captureBlockEntity(int x, int y, int z)
    {
        BlockState state = world.getBlockAt(x, y, z).getState();
        YamlConfiguration yaml = new YamlConfiguration();
        boolean any = false;

        if (state instanceof Lootable lootable && lootable.getLootTable() != null)
        {
            yaml.set("loot", lootable.getLootTable().getKey().toString());
            yaml.set("lootSeed", lootable.getSeed());
            any = true;
        }

        if (state instanceof Container container)
        {
            yaml.set("inventory", Arrays.asList(container.getInventory().getContents()));
            any = true;
        }

        if (state instanceof Sign sign)
        {
            yaml.set("lines", Arrays.asList(sign.getLines()));
            any = true;
        }

        if (state instanceof CreatureSpawner spawner)
        {
            if (spawner.getSpawnedType() != null)
            {
                yaml.set("spawner", spawner.getSpawnedType().name());
            }

            yaml.set("spawnerDelay", spawner.getDelay());
            any = true;
        }

        return any ? yaml.saveToString().getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    public void restoreBlockEntity(int x, int y, int z, byte[] blob)
    {
        YamlConfiguration yaml = new YamlConfiguration();

        try
        {
            yaml.loadFromString(new String(blob, StandardCharsets.UTF_8));
        }
        catch (InvalidConfigurationException e)
        {
            return;
        }

        Block block = world.getBlockAt(x, y, z);
        BlockState state = block.getState();
        boolean changed = false;

        if (state instanceof Container container && yaml.isList("inventory"))
        {
            List<?> stored = yaml.getList("inventory", List.of());
            ItemStack[] contents = new ItemStack[container.getInventory().getSize()];

            for (int i = 0; i < contents.length && i < stored.size(); i++)
            {
                if (stored.get(i) instanceof ItemStack item)
                {
                    contents[i] = item;
                }
            }

            // Snapshot inventory, not getInventory(): the state.update() below
            // re-applies the snapshot, so a live-inventory write would be lost.
            container.getSnapshotInventory().setContents(contents);
            changed = true;
        }

        if (state instanceof Sign sign && yaml.isList("lines"))
        {
            List<String> lines = yaml.getStringList("lines");

            for (int i = 0; i < lines.size() && i < 4; i++)
            {
                sign.setLine(i, lines.get(i));
            }

            changed = true;
        }

        if (state instanceof CreatureSpawner spawner)
        {
            String type = yaml.getString("spawner");

            if (type != null)
            {
                try
                {
                    spawner.setSpawnedType(EntityType.valueOf(type));
                }
                catch (IllegalArgumentException ignored)
                {
                    // Entity type gone from this server version.
                }
            }

            spawner.setDelay(yaml.getInt("spawnerDelay", spawner.getDelay()));
            changed = true;
        }

        if (state instanceof Lootable lootable && yaml.getString("loot") != null)
        {
            NamespacedKey key = NamespacedKey.fromString(yaml.getString("loot", ""));
            LootTable table = key == null ? null : Bukkit.getLootTable(key);

            if (table != null)
            {
                lootable.setLootTable(table);
                lootable.setSeed(yaml.getLong("lootSeed"));
                changed = true;
            }
        }

        if (changed)
        {
            state.update(true, false);
        }
    }

    @Override
    public void applyLootTable(int x, int y, int z, String lootTableId, long seed)
    {
        BlockState state = world.getBlockAt(x, y, z).getState();

        if (!(state instanceof Lootable lootable))
        {
            return;
        }

        NamespacedKey key = NamespacedKey.fromString(lootTableId);
        LootTable table = key == null ? null : Bukkit.getLootTable(key);

        if (table == null)
        {
            logger.warning("Unknown loot table '" + lootTableId + "' — leaving chest empty.");
            return;
        }

        lootable.setLootTable(table);

        if (seed != 0L)
        {
            lootable.setSeed(seed);
        }

        state.update(true, false);
    }

    @Override
    public void loadDispenser(int x, int y, int z, List<DispenserItem> items)
    {
        Block block = world.getBlockAt(x, y, z);
        BlockState state = block.getState();

        if (!(state instanceof Container container))
        {
            // The dispenser trap's block was expected here but isn't (placement
            // skipped/overwrote it, or the coordinates are off). Say so, rather
            // than silently leaving the trap unarmed.
            logger.warning("No container at (" + x + "," + y + "," + z + ") to stock as a trap"
                    + " dispenser — found " + block.getType() + ". Leaving it unarmed.");
            return;
        }

        for (DispenserItem item : items)
        {
            ItemStack stack = trapItem(item);

            if (stack != null)
            {
                // Modify the SNAPSHOT inventory, not getInventory(): on a placed
                // block state getInventory() is the live block, and the update()
                // below would immediately overwrite it with this (empty) snapshot,
                // wiping every stack. Stocking the snapshot lets update() persist it.
                container.getSnapshotInventory().setItem(item.slot(), stack);
            }
        }

        state.update(true, false);
    }

    /** Builds a trap ItemStack, giving a tipped arrow or thrown potion its potion via the vanilla item parser. */
    private ItemStack trapItem(DispenserItem item)
    {
        if (item.potionId() == null)
        {
            Material material = Material.matchMaterial(item.itemId());
            return material == null ? null : new ItemStack(material, item.count());
        }

        // Potion-bearing item (tipped arrow, splash/lingering potion): let the server
        // parse the potion. The item-string form changed in 1.20.5 (NBT tag → data
        // component), so try the new form then the old one.
        for (String spec : new String[]{
                item.itemId() + "[minecraft:potion_contents={potion:\"" + item.potionId() + "\"}]",
                item.itemId() + "{Potion:\"" + item.potionId() + "\"}"})
        {
            try
            {
                ItemStack stack = Bukkit.getItemFactory().createItemStack(spec);
                stack.setAmount(item.count());
                return stack;
            }
            catch (IllegalArgumentException ignored)
            {
                // wrong form for this server version — try the next
            }
        }

        // Last resort: the bare item without its potion, rather than nothing at all.
        Material material = Material.matchMaterial(item.itemId());
        return material == null ? null : new ItemStack(material, item.count());
    }

    @Override
    public void configureSpawner(int x, int y, int z, SpawnerConfig config)
    {
        BlockState state = world.getBlockAt(x, y, z).getState();

        if (!(state instanceof CreatureSpawner spawner))
        {
            return;
        }

        EntityType type = entityType(config.entityId());

        if (type != null)
        {
            spawner.setSpawnedType(type);
        }

        spawner.setSpawnCount(config.spawnCount());
        spawner.setMaxNearbyEntities(config.maxNearbyEntities());
        spawner.setRequiredPlayerRange(config.requiredPlayerRange());
        spawner.setSpawnRange(config.spawnRange());
        // Set the max first: setMinSpawnDelay rejects a min above the current max,
        // and the block's default max (800) could be below our new min otherwise.
        spawner.setMaxSpawnDelay(config.maxSpawnDelay());
        spawner.setMinSpawnDelay(config.minSpawnDelay());
        spawner.setDelay(0);

        state.update(true, false);
    }

    /** Resolves a mob id to its Bukkit {@link EntityType}, or null (logged) if this server has no such mob. */
    private EntityType entityType(String entityId)
    {
        // "minecraft:cave_spider" → CAVE_SPIDER, matching the capture/restore path,
        // which stores and reads the enum name.
        String path = entityId.contains(":") ? entityId.substring(entityId.indexOf(':') + 1) : entityId;

        try
        {
            return EntityType.valueOf(path.toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            logger.warning("Unknown spawner mob '" + entityId + "' — leaving the spawner's default.");
            return null;
        }
    }

    /** Palette entries already parsed and rotated into Bukkit block data. */
    private record BukkitPalette(BlockData[] data) implements PreparedPalette
    {
        @Override
        public int size()
        {
            return data.length;
        }
    }
}
