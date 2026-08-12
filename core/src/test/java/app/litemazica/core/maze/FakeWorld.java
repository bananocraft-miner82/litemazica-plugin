package app.litemazica.core.maze;

import app.litemazica.core.platform.BlockPos;
import app.litemazica.core.platform.DispenserItem;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.SpawnerConfig;
import app.litemazica.core.platform.WorldAccess;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A world backed by a block map: reads see seeded terrain, writes overwrite it.
 * Shared by the placement and service tests.
 */
final class FakeWorld implements WorldAccess
{
    private final String name;
    private final Map<Long, String> blocks = new HashMap<>();
    private final Map<Long, Boolean> written = new HashMap<>();
    private final Set<Long> physicsCleared = new HashSet<>();
    private final Map<Long, List<DispenserItem>> dispensers = new HashMap<>();
    private final Map<Long, SpawnerConfig> spawners = new HashMap<>();

    FakeWorld()
    {
        this("test");
    }

    FakeWorld(String name)
    {
        this.name = name;
    }

    List<DispenserItem> dispenserAt(int x, int y, int z)
    {
        return dispensers.get(key(x, y, z));
    }

    SpawnerConfig spawnerAt(int x, int y, int z)
    {
        return spawners.get(key(x, y, z));
    }

    boolean wasPhysicsCleared(int x, int y, int z)
    {
        return physicsCleared.contains(key(x, y, z));
    }

    void seed(int x, int y, int z, String block)
    {
        blocks.put(key(x, y, z), block);
    }

    String blockAt(int x, int y, int z)
    {
        return blocks.getOrDefault(key(x, y, z), "minecraft:air");
    }

    boolean wasWritten(int x, int y, int z)
    {
        return written.containsKey(key(x, y, z));
    }

    private static long key(int x, int y, int z)
    {
        return (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12) | ((long) y & 0xFFF);
    }

    @Override
    public String name()
    {
        return name;
    }

    @Override
    public int minY()
    {
        return -64;
    }

    @Override
    public int maxY()
    {
        return 320;
    }

    @Override
    public PreparedPalette preparePalette(List<String> palette, int quarterTurnsClockwise)
    {
        return new ListPalette(palette);
    }

    @Override
    public void setBlock(int x, int y, int z, PreparedPalette palette, int index)
    {
        String block = ((ListPalette) palette).blocks.get(index);
        blocks.put(key(x, y, z), block);
        written.put(key(x, y, z), true);
    }

    @Override
    public void setBlockState(int x, int y, int z, String blockState)
    {
        blocks.put(key(x, y, z), blockState);
        written.put(key(x, y, z), true);
    }

    @Override
    public void clearWithPhysics(int x, int y, int z)
    {
        // No physics in the fake; treat it as a plain clear to air, but record
        // that a neighbour-notifying break happened here.
        blocks.put(key(x, y, z), "minecraft:air");
        written.put(key(x, y, z), true);
        physicsCleared.add(key(x, y, z));
    }

    @Override
    public void updateBlock(int x, int y, int z)
    {
        // No physics in the fake; the block stays as written.
    }

    @Override
    public String blockStateAt(int x, int y, int z)
    {
        return blockAt(x, y, z);
    }

    @Override
    public List<BlockPos> blockEntitiesIn(Region region)
    {
        return List.of();
    }

    @Override
    public byte[] captureBlockEntity(int x, int y, int z)
    {
        return null;
    }

    @Override
    public void restoreBlockEntity(int x, int y, int z, byte[] blob)
    {
    }

    @Override
    public void applyLootTable(int x, int y, int z, String lootTableId, long seed)
    {
    }

    @Override
    public void loadDispenser(int x, int y, int z, List<DispenserItem> items)
    {
        dispensers.put(key(x, y, z), items);
    }

    @Override
    public void configureSpawner(int x, int y, int z, SpawnerConfig config)
    {
        spawners.put(key(x, y, z), config);
    }

    /** A palette that resolves an index straight back to its block string. */
    private static final class ListPalette implements PreparedPalette
    {
        private final List<String> blocks;

        ListPalette(List<String> blocks)
        {
            this.blocks = blocks;
        }

        @Override
        public int size()
        {
            return blocks.size();
        }
    }
}
