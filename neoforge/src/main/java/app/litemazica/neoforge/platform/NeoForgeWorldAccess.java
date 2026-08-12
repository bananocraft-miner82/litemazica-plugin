package app.litemazica.neoforge.platform;

import app.litemazica.core.maze.Region;
import app.litemazica.core.platform.BlockPos;
import app.litemazica.core.platform.DispenserItem;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.SpawnerConfig;
import app.litemazica.core.platform.WorldAccess;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@link WorldAccess} over a {@link ServerLevel}.
 *
 * <p>Same job as the Fabric adapter, written against Mojang mappings instead of
 * Yarn. Block entities are captured as real NBT, so every type restores
 * faithfully — unlike the Bukkit build, which has no NBT API to reach for.
 */
public final class NeoForgeWorldAccess implements WorldAccess
{
    private static final Rotation[] ROTATIONS =
    {
        Rotation.NONE,
        Rotation.CLOCKWISE_90,
        Rotation.CLOCKWISE_180,
        Rotation.COUNTERCLOCKWISE_90,
    };

    /**
     * Update clients, but do not notify neighbours: cascading updates mid-paste
     * would be ruinous, and would pop torches off walls that don't exist yet.
     */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ServerLevel level;
    private final Logger logger;

    public NeoForgeWorldAccess(ServerLevel level, Logger logger)
    {
        this.level = level;
        this.logger = logger;
    }

    @Override
    public String name()
    {
        return NeoForgePlatform.nameOf(level);
    }

    @Override
    public int minY()
    {
        return level.getMinBuildHeight();
    }

    @Override
    public int maxY()
    {
        return level.getMaxBuildHeight();
    }

    @Override
    public PreparedPalette preparePalette(List<String> palette, int quarterTurnsClockwise)
    {
        Rotation rotation = ROTATIONS[Math.floorMod(quarterTurnsClockwise, 4)];
        BlockState[] states = new BlockState[palette.size()];

        for (int i = 0; i < states.length; i++)
        {
            BlockState state;

            try
            {
                state = BlockStateParser
                        .parseForBlock(BuiltInRegistries.BLOCK.asLookup(), palette.get(i), false)
                        .blockState();
            }
            catch (Exception e)
            {
                logger.warning("Unknown block '" + palette.get(i) + "' — using air.");
                state = Blocks.AIR.defaultBlockState();
            }

            states[i] = rotation == Rotation.NONE ? state : state.rotate(rotation);
        }

        return new NeoForgePalette(states);
    }

    @Override
    public void setBlock(int x, int y, int z, PreparedPalette palette, int index)
    {
        level.setBlock(new net.minecraft.core.BlockPos(x, y, z),
                ((NeoForgePalette) palette).states[index], PLACE_FLAGS);
    }

    @Override
    public void setBlockState(int x, int y, int z, String blockState)
    {
        BlockState state;

        try
        {
            state = BlockStateParser
                    .parseForBlock(BuiltInRegistries.BLOCK.asLookup(), blockState, false)
                    .blockState();
        }
        catch (Exception e)
        {
            logger.warning("Unknown block '" + blockState + "' — leaving the existing block.");
            return;
        }

        // Same update-free flags as the palette body: a trap edit shouldn't cascade.
        level.setBlock(new net.minecraft.core.BlockPos(x, y, z), state, PLACE_FLAGS);
    }

    @Override
    public void clearWithPhysics(int x, int y, int z)
    {
        // UPDATE_ALL breaks the block like a player would, notifying its neighbours
        // so any canopy left just beyond the flood recomputes its distance and decays.
        level.setBlock(new net.minecraft.core.BlockPos(x, y, z),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Override
    public void updateBlock(int x, int y, int z)
    {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        // Re-setting the same state is skipped, so toggle through air to force a
        // physics update: fluids resume flowing, unstable blocks fall.
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    @Override
    public String blockStateAt(int x, int y, int z)
    {
        return BlockStateParser.serialize(level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)));
    }

    @Override
    public List<BlockPos> blockEntitiesIn(Region region)
    {
        List<BlockPos> out = new ArrayList<>();

        for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++)
        {
            for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++)
            {
                LevelChunk chunk = level.getChunk(cx, cz);

                for (net.minecraft.core.BlockPos pos : chunk.getBlockEntitiesPos())
                {
                    if (region.contains(pos.getX(), pos.getY(), pos.getZ()))
                    {
                        out.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
                    }
                }
            }
        }

        return out;
    }

    @Override
    public byte[] captureBlockEntity(int x, int y, int z)
    {
        BlockEntity entity = level.getBlockEntity(new net.minecraft.core.BlockPos(x, y, z));

        if (entity == null)
        {
            return null;
        }

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream())
        {
            NbtIo.writeCompressed(entity.saveWithFullMetadata(level.registryAccess()), bytes);
            return bytes.toByteArray();
        }
        catch (IOException e)
        {
            logger.warning("Could not capture block entity at " + x + "," + y + "," + z + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public void restoreBlockEntity(int x, int y, int z, byte[] blob)
    {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
        BlockEntity entity = level.getBlockEntity(pos);

        if (entity == null)
        {
            return; // the block that owned it is no longer here
        }

        try (ByteArrayInputStream bytes = new ByteArrayInputStream(blob))
        {
            CompoundTag tag = NbtIo.readCompressed(bytes, NbtAccounter.unlimitedHeap());
            entity.loadWithComponents(tag, level.registryAccess());
            entity.setChanged();
        }
        catch (IOException e)
        {
            logger.warning("Could not restore block entity at " + x + "," + y + "," + z + ": " + e.getMessage());
        }
    }

    @Override
    public void applyLootTable(int x, int y, int z, String lootTableId, long seed)
    {
        BlockEntity entity = level.getBlockEntity(new net.minecraft.core.BlockPos(x, y, z));

        if (!(entity instanceof RandomizableContainerBlockEntity container))
        {
            return;
        }

        ResourceLocation id = ResourceLocation.tryParse(lootTableId);

        if (id == null)
        {
            logger.warning("Unknown loot table '" + lootTableId + "' — leaving chest empty.");
            return;
        }

        container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, id), seed);
        container.setChanged();
    }

    @Override
    public void loadDispenser(int x, int y, int z, List<DispenserItem> items)
    {
        BlockEntity entity = level.getBlockEntity(new net.minecraft.core.BlockPos(x, y, z));

        if (!(entity instanceof DispenserBlockEntity dispenser))
        {
            return;
        }

        for (DispenserItem item : items)
        {
            dispenser.setItem(item.slot(), trapStack(item));
        }

        dispenser.setChanged();
    }

    /** Builds a trap stack, adding its potion contents when present (1.21 stores them in a data component). */
    private ItemStack trapStack(DispenserItem item)
    {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item.itemId())), item.count());

        if (item.potionId() != null)
        {
            ResourceKey<Potion> key = ResourceKey.create(Registries.POTION, ResourceLocation.parse(item.potionId()));
            BuiltInRegistries.POTION.getHolder(key).ifPresent(potion ->
                    stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion)));
        }

        return stack;
    }

    @Override
    public void configureSpawner(int x, int y, int z, SpawnerConfig config)
    {
        BlockEntity entity = level.getBlockEntity(new net.minecraft.core.BlockPos(x, y, z));

        if (!(entity instanceof SpawnerBlockEntity))
        {
            return;
        }

        // No typed spawner API survives across versions, so drive it through the
        // block entity's own NBT — the same load path the terrain restore uses.
        entity.loadWithComponents(spawnerNbt(config), level.registryAccess());
        entity.setChanged();
    }

    /** The spawner's block-entity NBT (same key layout across the supported versions). */
    private static CompoundTag spawnerNbt(SpawnerConfig config)
    {
        CompoundTag entityTag = new CompoundTag();
        entityTag.putString("id", config.entityId());
        CompoundTag spawnData = new CompoundTag();
        spawnData.put("entity", entityTag);

        CompoundTag nbt = new CompoundTag();
        nbt.put("SpawnData", spawnData);
        nbt.putShort("Delay", (short) 0);
        nbt.putShort("MinSpawnDelay", (short) config.minSpawnDelay());
        nbt.putShort("MaxSpawnDelay", (short) config.maxSpawnDelay());
        nbt.putShort("SpawnCount", (short) config.spawnCount());
        nbt.putShort("MaxNearbyEntities", (short) config.maxNearbyEntities());
        nbt.putShort("RequiredPlayerRange", (short) config.requiredPlayerRange());
        nbt.putShort("SpawnRange", (short) config.spawnRange());
        return nbt;
    }

    /** Palette entries already parsed and rotated into block states. */
    private record NeoForgePalette(BlockState[] states) implements PreparedPalette
    {
        @Override
        public int size()
        {
            return states.length;
        }
    }
}
