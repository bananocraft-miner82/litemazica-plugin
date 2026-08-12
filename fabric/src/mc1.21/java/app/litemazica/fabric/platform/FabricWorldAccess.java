package app.litemazica.fabric.platform;

import app.litemazica.core.maze.Region;
import app.litemazica.core.platform.BlockPos;
import app.litemazica.core.platform.DispenserItem;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.SpawnerConfig;
import app.litemazica.core.platform.WorldAccess;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.potion.Potion;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.world.chunk.WorldChunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@link WorldAccess} over a {@link ServerWorld}.
 *
 * <p>Block entities are captured as their real NBT, so unlike the Bukkit
 * implementation this restores <em>every</em> block entity type faithfully, not
 * just the handful Bukkit exposes typed accessors for.
 */
public final class FabricWorldAccess implements WorldAccess
{
    private static final BlockRotation[] ROTATIONS =
    {
        BlockRotation.NONE,
        BlockRotation.CLOCKWISE_90,
        BlockRotation.CLOCKWISE_180,
        BlockRotation.COUNTERCLOCKWISE_90,
    };

    /**
     * Update clients, but do not notify neighbours: cascading updates mid-paste
     * would be ruinous, and would pop torches off walls that don't exist yet.
     */
    private static final int PLACE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    private final ServerWorld world;
    private final Logger logger;

    public FabricWorldAccess(ServerWorld world, Logger logger)
    {
        this.world = world;
        this.logger = logger;
    }

    @Override
    public String name()
    {
        return FabricPlatform.nameOf(world);
    }

    @Override
    public int minY()
    {
        return world.getBottomY();
    }

    @Override
    public int maxY()
    {
        return world.getTopY();
    }

    @Override
    public PreparedPalette preparePalette(List<String> palette, int quarterTurnsClockwise)
    {
        BlockRotation rotation = ROTATIONS[Math.floorMod(quarterTurnsClockwise, 4)];
        BlockState[] states = new BlockState[palette.size()];

        for (int i = 0; i < states.length; i++)
        {
            BlockState state;

            try
            {
                state = BlockArgumentParser
                        .block(Registries.BLOCK.getReadOnlyWrapper(), palette.get(i), false)
                        .blockState();
            }
            catch (Exception e)
            {
                logger.warning("Unknown block '" + palette.get(i) + "' — using air.");
                state = Blocks.AIR.getDefaultState();
            }

            states[i] = rotation == BlockRotation.NONE ? state : state.rotate(rotation);
        }

        return new FabricPalette(states);
    }

    @Override
    public void setBlock(int x, int y, int z, PreparedPalette palette, int index)
    {
        world.setBlockState(new net.minecraft.util.math.BlockPos(x, y, z),
                ((FabricPalette) palette).states[index], PLACE_FLAGS);
    }

    @Override
    public void setBlockState(int x, int y, int z, String blockState)
    {
        BlockState state;

        try
        {
            state = BlockArgumentParser
                    .block(Registries.BLOCK.getReadOnlyWrapper(), blockState, false)
                    .blockState();
        }
        catch (Exception e)
        {
            logger.warning("Unknown block '" + blockState + "' — leaving the existing block.");
            return;
        }

        // Same update-free flags as the palette body: a trap edit shouldn't cascade.
        world.setBlockState(new net.minecraft.util.math.BlockPos(x, y, z), state, PLACE_FLAGS);
    }

    @Override
    public void clearWithPhysics(int x, int y, int z)
    {
        // NOTIFY_ALL breaks the block like a player would, notifying its neighbours
        // so any canopy left just beyond the flood recomputes its distance and decays.
        world.setBlockState(new net.minecraft.util.math.BlockPos(x, y, z),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
    }

    @Override
    public void updateBlock(int x, int y, int z)
    {
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
        BlockState state = world.getBlockState(pos);
        // Re-setting the same state is skipped, so toggle through air to force a
        // physics update: fluids resume flowing, unstable blocks fall.
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        world.setBlockState(pos, state, Block.NOTIFY_ALL);
    }

    @Override
    public String blockStateAt(int x, int y, int z)
    {
        BlockState state = world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
        return BlockArgumentParser.stringifyBlockState(state);
    }

    @Override
    public List<BlockPos> blockEntitiesIn(Region region)
    {
        List<BlockPos> out = new ArrayList<>();

        for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++)
        {
            for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++)
            {
                WorldChunk chunk = world.getChunk(cx, cz);

                for (net.minecraft.util.math.BlockPos pos : chunk.getBlockEntityPositions())
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
        BlockEntity entity = world.getBlockEntity(new net.minecraft.util.math.BlockPos(x, y, z));

        if (entity == null)
        {
            return null;
        }

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream())
        {
            NbtIo.writeCompressed(entity.createNbtWithIdentifyingData(world.getRegistryManager()), bytes);
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
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
        BlockEntity entity = world.getBlockEntity(pos);

        if (entity == null)
        {
            return; // the block that owned it is no longer here
        }

        try (ByteArrayInputStream bytes = new ByteArrayInputStream(blob))
        {
            NbtCompound nbt = NbtIo.readCompressed(bytes, NbtSizeTracker.ofUnlimitedBytes());
            entity.read(nbt, world.getRegistryManager());
            entity.markDirty();
        }
        catch (IOException e)
        {
            logger.warning("Could not restore block entity at " + x + "," + y + "," + z + ": " + e.getMessage());
        }
    }

    @Override
    public void applyLootTable(int x, int y, int z, String lootTableId, long seed)
    {
        BlockEntity entity = world.getBlockEntity(new net.minecraft.util.math.BlockPos(x, y, z));

        if (!(entity instanceof LootableContainerBlockEntity container))
        {
            return;
        }

        Identifier id = Identifier.tryParse(lootTableId);

        if (id == null)
        {
            logger.warning("Unknown loot table '" + lootTableId + "' — leaving chest empty.");
            return;
        }

        container.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE, id), seed);
        container.markDirty();
    }

    @Override
    public void loadDispenser(int x, int y, int z, List<DispenserItem> items)
    {
        BlockEntity entity = world.getBlockEntity(new net.minecraft.util.math.BlockPos(x, y, z));

        if (!(entity instanceof DispenserBlockEntity dispenser))
        {
            return;
        }

        for (DispenserItem item : items)
        {
            dispenser.setStack(item.slot(), trapStack(item));
        }

        dispenser.markDirty();
    }

    /** Builds a trap stack, adding its potion contents when present (1.21 stores them in a data component). */
    private ItemStack trapStack(DispenserItem item)
    {
        ItemStack stack = new ItemStack(Registries.ITEM.get(Identifier.tryParse(item.itemId())), item.count());

        if (item.potionId() != null)
        {
            RegistryKey<Potion> key = RegistryKey.of(RegistryKeys.POTION, Identifier.tryParse(item.potionId()));
            Registries.POTION.getEntry(key).ifPresent(potion ->
                    stack.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(potion)));
        }

        return stack;
    }

    @Override
    public void configureSpawner(int x, int y, int z, SpawnerConfig config)
    {
        BlockEntity entity = world.getBlockEntity(new net.minecraft.util.math.BlockPos(x, y, z));

        if (!(entity instanceof MobSpawnerBlockEntity))
        {
            return;
        }

        // No typed spawner API survives across versions, so drive it through the
        // block entity's own NBT — the same read path the terrain restore uses.
        entity.read(spawnerNbt(config), world.getRegistryManager());
        entity.markDirty();
    }

    /** The spawner's block-entity NBT (same key layout on 1.20 and 1.21). */
    private static NbtCompound spawnerNbt(SpawnerConfig config)
    {
        NbtCompound entityTag = new NbtCompound();
        entityTag.putString("id", config.entityId());
        NbtCompound spawnData = new NbtCompound();
        spawnData.put("entity", entityTag);

        NbtCompound nbt = new NbtCompound();
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
    private record FabricPalette(BlockState[] states) implements PreparedPalette
    {
        @Override
        public int size()
        {
            return states.length;
        }
    }
}
