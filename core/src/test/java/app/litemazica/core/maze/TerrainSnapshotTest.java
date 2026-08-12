package app.litemazica.core.maze;

import app.litemazica.core.platform.BlockPos;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.WorldAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunked snapshot: capture a region to a folder, restore it into a fresh
 * world, and check it comes back byte-for-byte — across a region that spans many
 * world chunks, so the per-chunk streaming is actually exercised.
 */
class TerrainSnapshotTest
{
    private static final Logger LOG = Logger.getLogger("test");
    // Spans chunks x=[0..2], z=[0..2] → 9 chunk files.
    private static final Region REGION = new Region(3, 60, 3, 40, 66, 40);

    /** A varied but deterministic block for a position, so a wrong index shows up. */
    private static String terrainAt(int x, int y, int z)
    {
        return switch (Math.floorMod(x * 7 + y * 13 + z, 4))
        {
            case 0 -> "minecraft:stone";
            case 1 -> "minecraft:dirt";
            case 2 -> "minecraft:water[level=0]";
            default -> "minecraft:air";
        };
    }

    @Test
    void roundTripsAMultiChunkRegion(@TempDir Path dir)
    {
        FakeWorld source = new FakeWorld();
        forEach(REGION, (x, y, z) -> source.blocks.put(FakeWorld.key(x, y, z), terrainAt(x, y, z)));

        File snapshot = dir.resolve("m1").toFile();
        TerrainSnapshot.capture(new ImmediateScheduler(), source, REGION, snapshot, LOG, () -> {});

        assertTrue(TerrainSnapshot.exists(snapshot), "manifest should be written");
        assertTrue(new File(snapshot, "0_0.dat").isFile(), "a chunk file should be written");

        FakeWorld target = new FakeWorld();
        TerrainSnapshot.restore(new ImmediateScheduler(), target, snapshot, LOG, () -> {}, failed());

        forEach(REGION, (x, y, z) ->
                assertEquals(terrainAt(x, y, z), target.blockAt(x, y, z),
                        "block at " + x + "," + y + "," + z));
    }

    @Test
    void restoresBlockEntities(@TempDir Path dir)
    {
        FakeWorld source = new FakeWorld();
        byte[] blob = {1, 2, 3, 0, -7};
        source.blocks.put(FakeWorld.key(10, 61, 12), "minecraft:chest[facing=north]");
        source.blockEntities.put(FakeWorld.key(10, 61, 12), blob);

        File snapshot = dir.resolve("m1").toFile();
        TerrainSnapshot.capture(new ImmediateScheduler(), source, REGION, snapshot, LOG, () -> {});

        FakeWorld target = new FakeWorld();
        TerrainSnapshot.restore(new ImmediateScheduler(), target, snapshot, LOG, () -> {}, failed());

        assertArrayEquals(blob, target.restoredEntities.get(FakeWorld.key(10, 61, 12)),
                "the chest's contents should be restored");
    }

    @Test
    void nudgesFluidsBackToLifeOnRestore(@TempDir Path dir)
    {
        FakeWorld source = new FakeWorld();
        source.blocks.put(FakeWorld.key(5, 62, 5), "minecraft:water[level=0]");
        source.blocks.put(FakeWorld.key(6, 62, 5), "minecraft:stone");

        File snapshot = dir.resolve("m1").toFile();
        TerrainSnapshot.capture(new ImmediateScheduler(), source, REGION, snapshot, LOG, () -> {});

        FakeWorld target = new FakeWorld();
        TerrainSnapshot.restore(new ImmediateScheduler(), target, snapshot, LOG, () -> {}, failed());

        assertTrue(target.updated.contains(FakeWorld.key(5, 62, 5)), "water should be re-triggered");
        assertFalse(target.updated.contains(FakeWorld.key(6, 62, 5)), "stone needs no update");
    }

    @Test
    void readsBackTheRegionAndExistsThenDeletes(@TempDir Path dir) throws Exception
    {
        FakeWorld source = new FakeWorld();
        File snapshot = dir.resolve("m1").toFile();
        TerrainSnapshot.capture(new ImmediateScheduler(), source, REGION, snapshot, LOG, () -> {});

        assertEquals(REGION, TerrainSnapshot.readRegion(snapshot));
        assertTrue(TerrainSnapshot.exists(snapshot));

        TerrainSnapshot.delete(snapshot);
        assertFalse(TerrainSnapshot.exists(snapshot));
        assertFalse(snapshot.exists(), "the whole folder should be gone");
    }

    @Test
    void restoringAMissingSnapshotFails(@TempDir Path dir)
    {
        boolean[] failed = {false};
        TerrainSnapshot.restore(new ImmediateScheduler(), new FakeWorld(), dir.resolve("nope").toFile(),
                LOG, () -> {}, () -> failed[0] = true);

        assertTrue(failed[0], "a missing manifest should route to onFail");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private interface Voxel
    {
        void at(int x, int y, int z);
    }

    private static void forEach(Region r, Voxel voxel)
    {
        for (int y = r.minY(); y <= r.maxY(); y++)
        {
            for (int z = r.minZ(); z <= r.maxZ(); z++)
            {
                for (int x = r.minX(); x <= r.maxX(); x++)
                {
                    voxel.at(x, y, z);
                }
            }
        }
    }

    private static Runnable failed()
    {
        return () -> org.junit.jupiter.api.Assertions.fail("restore should not fail");
    }

    private static final class ImmediateScheduler implements Scheduler
    {
        @Override
        public void eachTick(BooleanSupplier slice, Runnable onDone)
        {
            while (!slice.getAsBoolean())
            {
                // keep slicing until done
            }

            onDone.run();
        }

        @Override
        public void async(Runnable work)
        {
            work.run();
        }

        @Override
        public void onMain(Runnable work)
        {
            work.run();
        }

        @Override
        public Cancellable everySeconds(long seconds, Runnable work)
        {
            return () -> { };
        }
    }

    /** A world backed by maps: seed terrain + block entities, then inspect writes. */
    private static final class FakeWorld implements WorldAccess
    {
        final Map<Long, String> blocks = new HashMap<>();
        final Map<Long, byte[]> blockEntities = new HashMap<>();
        final Map<Long, byte[]> restoredEntities = new HashMap<>();
        final java.util.Set<Long> updated = new java.util.HashSet<>();

        static long key(int x, int y, int z)
        {
            return (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12) | ((long) y & 0xFFF);
        }

        String blockAt(int x, int y, int z)
        {
            return blocks.getOrDefault(key(x, y, z), "minecraft:air");
        }

        @Override
        public String name()
        {
            return "test";
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
            blocks.put(key(x, y, z), ((ListPalette) palette).blocks.get(index));
        }

        @Override
        public void setBlockState(int x, int y, int z, String blockState)
        {
            blocks.put(key(x, y, z), blockState);
        }

        @Override
        public void clearWithPhysics(int x, int y, int z)
        {
            blocks.put(key(x, y, z), "minecraft:air");
        }

        @Override
        public void loadDispenser(int x, int y, int z, List<app.litemazica.core.platform.DispenserItem> items)
        {
        }

        @Override
        public void configureSpawner(int x, int y, int z, app.litemazica.core.platform.SpawnerConfig config)
        {
        }

        @Override
        public void updateBlock(int x, int y, int z)
        {
            updated.add(key(x, y, z));
        }

        @Override
        public String blockStateAt(int x, int y, int z)
        {
            return blockAt(x, y, z);
        }

        @Override
        public List<BlockPos> blockEntitiesIn(Region region)
        {
            List<BlockPos> out = new ArrayList<>();

            for (Map.Entry<Long, byte[]> e : blockEntities.entrySet())
            {
                long k = e.getKey();
                int y = (int) (k & 0xFFF);
                int z = (int) ((k >> 12) & 0x3FFFFFF);
                int x = (int) ((k >> 38) & 0x3FFFFFF);

                if (region.contains(x, y, z))
                {
                    out.add(new BlockPos(x, y, z));
                }
            }

            return out;
        }

        @Override
        public byte[] captureBlockEntity(int x, int y, int z)
        {
            return blockEntities.get(key(x, y, z));
        }

        @Override
        public void restoreBlockEntity(int x, int y, int z, byte[] blob)
        {
            restoredEntities.put(key(x, y, z), blob);
        }

        @Override
        public void applyLootTable(int x, int y, int z, String lootTableId, long seed)
        {
        }
    }

    private static final class ListPalette implements PreparedPalette
    {
        final List<String> blocks;

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
