package app.litemazica.core.maze;

import app.litemazica.core.platform.BlockPos;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.WorldAccess;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The terrain a maze was built over, so removing the maze puts the world back
 * rather than leaving a box of air.
 *
 * <p>Stored as a <em>folder</em> per maze — a {@code region.dat} manifest plus
 * one gzipped file per 16×16 world chunk the region touches. Capture and restore
 * stream one chunk at a time: only a single chunk's worth of blocks is ever held
 * in memory, so the snapshot's size is bounded by disk, not RAM, however large
 * the maze. A chunk holds a palette of block-state strings plus one index per
 * voxel (terrain is overwhelmingly a handful of blocks, so it gzips hard), and
 * any block entities within it as opaque platform blobs.
 *
 * <p>Not captured: entities (item frames, armour stands, paintings).
 */
public final class TerrainSnapshot
{
    private static final int MAGIC = 0x4C4D5A53; // "LMZS"
    private static final int VERSION = 3;
    /** Blocks read/written per server tick, matching the placer's budget. */
    private static final int BLOCKS_PER_TICK = 8192;
    /** Chunk files allowed to be mid-write at once, so capture can't out-run the disk. */
    private static final int MAX_IN_FLIGHT_WRITES = 8;

    /** A block entity's restorable contents, as an opaque platform blob. */
    public record BlockEntityRecord(int x, int y, int z, byte[] blob)
    {
    }

    private TerrainSnapshot()
    {
    }

    // ── layout ─────────────────────────────────────────────────────────────────

    private static File manifest(File dir)
    {
        return new File(dir, "region.dat");
    }

    private static File chunkFile(File dir, int cx, int cz)
    {
        return new File(dir, cx + "_" + cz + ".dat");
    }

    /** True when {@code dir} holds a snapshot (its manifest is present). */
    public static boolean exists(File dir)
    {
        return manifest(dir).isFile();
    }

    /** Deletes a snapshot folder and everything in it. Cheap disk work — off the main thread. */
    public static void delete(File dir)
    {
        File[] files = dir.listFiles();

        if (files != null)
        {
            for (File file : files)
            {
                file.delete();
            }
        }

        dir.delete();
    }

    /** The region a snapshot covers, read from its manifest. */
    public static Region readRegion(File dir) throws IOException
    {
        try (FileInputStream fileIn = new FileInputStream(manifest(dir));
             DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(fileIn))))
        {
            if (in.readInt() != MAGIC)
            {
                throw new IOException("Not a Litemazica snapshot: " + dir.getName());
            }

            int version = in.readInt();

            if (version != VERSION)
            {
                throw new IOException("Unsupported snapshot version " + version + " in " + dir.getName());
            }

            return new Region(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        }
    }

    // ── capture ──────────────────────────────────────────────────────────────

    /**
     * Reads {@code region} across ticks and streams it to per-chunk files under
     * {@code dir}. Call before the maze is placed — whatever is here now is what a
     * later restore puts back. {@code onDone} runs on the main thread once every
     * chunk is on disk.
     */
    public static void capture(Scheduler scheduler, WorldAccess world, Region region, File dir,
                               Logger logger, Runnable onDone)
    {
        if (!dir.isDirectory() && !dir.mkdirs())
        {
            logger.warning("Could not create snapshot folder " + dir.getAbsolutePath() + ".");
            onDone.run();
            return;
        }

        try
        {
            writeManifest(dir, region);
        }
        catch (IOException e)
        {
            logger.warning("Could not write snapshot manifest: " + e.getMessage());
        }

        final int cxMin = region.minX() >> 4;
        final int czMin = region.minZ() >> 4;
        final int gridW = (region.maxX() >> 4) - cxMin + 1;
        final int chunkCount = gridW * ((region.maxZ() >> 4) - czMin + 1);
        final int worldMin = world.minY();
        final int worldMax = world.maxY();

        // pending counts the reading pass (1) plus every not-yet-written chunk;
        // whichever decrement hits zero fires onDone. inFlight is separate, just
        // for back-pressure so a slow disk can't let the buffers pile up.
        final AtomicInteger pending = new AtomicInteger(1);
        final AtomicInteger inFlight = new AtomicInteger(0);
        final int[] chunkIdx = {0};
        final ChunkBuf[] cur = {null};

        scheduler.eachTick(() ->
        {
            int done = 0;

            while (done < BLOCKS_PER_TICK)
            {
                if (cur[0] == null)
                {
                    if (chunkIdx[0] >= chunkCount || inFlight.get() >= MAX_IN_FLIGHT_WRITES)
                    {
                        break; // finished, or waiting for writes to drain
                    }

                    int idx = chunkIdx[0];
                    cur[0] = new ChunkBuf(region, cxMin + (idx % gridW), czMin + (idx / gridW));
                }

                ChunkBuf c = cur[0];

                while (c.cursor < c.volume && done < BLOCKS_PER_TICK)
                {
                    int dx = c.cursor % c.width;
                    int rem = c.cursor / c.width;
                    int dz = rem % c.depth;
                    int wy = c.y0 + rem / c.depth;

                    String key = wy < worldMin || wy >= worldMax
                            ? "minecraft:air"
                            : world.blockStateAt(c.x0 + dx, wy, c.z0 + dz);

                    c.indices[c.cursor++] = c.intern(key);
                    done++;
                }

                if (c.cursor >= c.volume)
                {
                    c.blockEntities = captureBlockEntities(world, c.chunkRegion());
                    cur[0] = null;
                    chunkIdx[0]++;
                    pending.incrementAndGet();
                    inFlight.incrementAndGet();
                    final ChunkBuf w = c;

                    scheduler.async(() ->
                    {
                        try
                        {
                            writeChunk(chunkFile(dir, w.cx, w.cz), w);
                        }
                        catch (IOException e)
                        {
                            logger.warning("Could not write snapshot chunk " + w.cx + "," + w.cz + ": " + e.getMessage());
                        }

                        inFlight.decrementAndGet();

                        if (pending.decrementAndGet() == 0)
                        {
                            scheduler.onMain(onDone);
                        }
                    });
                }
            }

            boolean readingDone = chunkIdx[0] >= chunkCount && cur[0] == null;

            if (readingDone && pending.decrementAndGet() == 0)
            {
                onDone.run(); // already on the main thread; all writes already landed
            }

            return readingDone;
        }, () ->
        {
            // eachTick's own completion is a no-op: onDone is fired by the pending
            // counter once the last chunk write lands.
        });
    }

    private static List<BlockEntityRecord> captureBlockEntities(WorldAccess world, Region region)
    {
        List<BlockEntityRecord> out = new ArrayList<>();

        for (BlockPos pos : world.blockEntitiesIn(region))
        {
            byte[] blob = world.captureBlockEntity(pos.x(), pos.y(), pos.z());

            if (blob != null && blob.length > 0)
            {
                out.add(new BlockEntityRecord(pos.x(), pos.y(), pos.z(), blob));
            }
        }

        return out;
    }

    // ── restore ──────────────────────────────────────────────────────────────

    /**
     * Writes the captured terrain back, one chunk at a time. Reads each chunk off
     * the main thread and places it on it, so only a chunk is ever in memory.
     * {@code onFail} runs (on the main thread) if the manifest can't be read.
     */
    public static void restore(Scheduler scheduler, WorldAccess world, File dir,
                               Logger logger, Runnable onDone, Runnable onFail)
    {
        scheduler.async(() ->
        {
            Region region;

            try
            {
                region = readRegion(dir);
            }
            catch (IOException e)
            {
                logger.warning("Could not read snapshot manifest for " + dir.getName() + ": " + e.getMessage());
                scheduler.onMain(onFail);
                return;
            }

            Region r = region;
            int cxMin = r.minX() >> 4;
            int czMin = r.minZ() >> 4;
            int gridW = (r.maxX() >> 4) - cxMin + 1;
            int count = gridW * ((r.maxZ() >> 4) - czMin + 1);
            scheduler.onMain(() -> restoreChunk(scheduler, world, dir, r, cxMin, czMin, gridW, count, 0, logger, onDone));
        });
    }

    private static void restoreChunk(Scheduler scheduler, WorldAccess world, File dir, Region region,
                                     int cxMin, int czMin, int gridW, int count, int i,
                                     Logger logger, Runnable onDone)
    {
        if (i >= count)
        {
            onDone.run();
            return;
        }

        int cx = cxMin + (i % gridW);
        int cz = czMin + (i / gridW);
        Runnable next = () -> restoreChunk(scheduler, world, dir, region, cxMin, czMin, gridW, count, i + 1, logger, onDone);

        scheduler.async(() ->
        {
            ChunkData data;

            try
            {
                data = readChunk(chunkFile(dir, cx, cz));
            }
            catch (FileNotFoundException e)
            {
                data = null; // a chunk file that was never written — nothing to put back here
            }
            catch (IOException e)
            {
                logger.warning("Could not read snapshot chunk " + cx + "," + cz + ": " + e.getMessage());
                data = null;
            }

            ChunkData d = data;
            scheduler.onMain(() ->
            {
                if (d == null)
                {
                    next.run();
                }
                else
                {
                    placeChunk(scheduler, world, region, cx, cz, d, next);
                }
            });
        });
    }

    private static void placeChunk(Scheduler scheduler, WorldAccess world, Region region, int cx, int cz,
                                   ChunkData data, Runnable onChunkDone)
    {
        final int x0 = Math.max(region.minX(), cx << 4);
        final int z0 = Math.max(region.minZ(), cz << 4);
        final int y0 = region.minY();
        final int width = Math.min(region.maxX(), (cx << 4) + 15) - x0 + 1;
        final int depth = Math.min(region.maxZ(), (cz << 4) + 15) - z0 + 1;
        final int rowXZ = width * depth;
        final long volume = data.indices.length;
        final int worldMin = world.minY();
        final int worldMax = world.maxY();
        final PreparedPalette prepared = world.preparePalette(data.palette, 0);
        final long[] cursor = {0};

        scheduler.eachTick(() ->
        {
            int done = 0;

            while (cursor[0] < volume && done < BLOCKS_PER_TICK)
            {
                int index = data.indices[(int) cursor[0]];
                int ly = (int) (cursor[0] / rowXZ);
                int rem = (int) (cursor[0] - (long) ly * rowXZ);
                int wy = y0 + ly;
                cursor[0]++;
                done++;

                if (wy >= worldMin && wy < worldMax)
                {
                    world.setBlock(x0 + rem % width, wy, z0 + rem / width, prepared, index);
                }
            }

            return cursor[0] >= volume;
        }, () ->
        {
            for (BlockEntityRecord record : data.blockEntities)
            {
                world.restoreBlockEntity(record.x(), record.y(), record.z(), record.blob());
            }

            reactivate(scheduler, world, data, x0, y0, z0, width, depth, worldMin, worldMax, onChunkDone);
        });
    }

    /**
     * After a chunk's blocks are written (no updates, so no cascades mid-write),
     * nudge the ones that need physics — water/lava resume flowing, sand/gravel
     * fall. Skipped outright when the chunk holds none.
     */
    private static void reactivate(Scheduler scheduler, WorldAccess world, ChunkData data,
                                   int x0, int y0, int z0, int width, int depth,
                                   int worldMin, int worldMax, Runnable onDone)
    {
        final boolean[] needs = new boolean[data.palette.size()];
        boolean any = false;

        for (int i = 0; i < data.palette.size(); i++)
        {
            needs[i] = TerrainCategory.needsRestoreUpdate(data.palette.get(i));
            any |= needs[i];
        }

        if (!any)
        {
            onDone.run();
            return;
        }

        final int rowXZ = width * depth;
        final long volume = data.indices.length;
        final long[] cursor = {0};

        scheduler.eachTick(() ->
        {
            int done = 0;

            while (cursor[0] < volume && done < BLOCKS_PER_TICK)
            {
                if (needs[data.indices[(int) cursor[0]]])
                {
                    int ly = (int) (cursor[0] / rowXZ);
                    int rem = (int) (cursor[0] - (long) ly * rowXZ);
                    int wy = y0 + ly;

                    if (wy >= worldMin && wy < worldMax)
                    {
                        world.updateBlock(x0 + rem % width, wy, z0 + rem / width);
                    }
                }

                cursor[0]++;
                done++;
            }

            return cursor[0] >= volume;
        }, onDone);
    }

    // ── one chunk, in memory ───────────────────────────────────────────────────

    /** A chunk being captured: its bounds, an interning palette, and one index per voxel. */
    private static final class ChunkBuf
    {
        final int cx;
        final int cz;
        final int x0;
        final int y0;
        final int z0;
        final int width;
        final int depth;
        final int height;
        final int volume;
        final int[] indices;
        private final Map<String, Integer> seen = new LinkedHashMap<>();
        final List<String> palette = new ArrayList<>();
        List<BlockEntityRecord> blockEntities = List.of();
        int cursor;

        ChunkBuf(Region region, int cx, int cz)
        {
            this.cx = cx;
            this.cz = cz;
            this.x0 = Math.max(region.minX(), cx << 4);
            this.z0 = Math.max(region.minZ(), cz << 4);
            this.y0 = region.minY();
            this.width = Math.min(region.maxX(), (cx << 4) + 15) - x0 + 1;
            this.depth = Math.min(region.maxZ(), (cz << 4) + 15) - z0 + 1;
            this.height = region.maxY() - y0 + 1;
            this.volume = width * depth * height;
            this.indices = new int[volume];
        }

        int intern(String key)
        {
            Integer index = seen.get(key);

            if (index == null)
            {
                index = palette.size();
                seen.put(key, index);
                palette.add(key);
            }

            return index;
        }

        Region chunkRegion()
        {
            return new Region(x0, y0, z0, x0 + width - 1, y0 + height - 1, z0 + depth - 1);
        }
    }

    /** A chunk read back from disk: its palette, indices, and block entities. */
    private record ChunkData(List<String> palette, int[] indices, List<BlockEntityRecord> blockEntities)
    {
    }

    // ── persistence ──────────────────────────────────────────────────────────

    private static void writeManifest(File dir, Region region) throws IOException
    {
        try (FileOutputStream fileOut = new FileOutputStream(manifest(dir));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(fileOut))))
        {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(region.minX());
            out.writeInt(region.minY());
            out.writeInt(region.minZ());
            out.writeInt(region.maxX());
            out.writeInt(region.maxY());
            out.writeInt(region.maxZ());
        }
    }

    private static void writeChunk(File file, ChunkBuf buf) throws IOException
    {
        try (FileOutputStream fileOut = new FileOutputStream(file);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(fileOut))))
        {
            out.writeInt(buf.palette.size());

            for (String entry : buf.palette)
            {
                writeString(out, entry);
            }

            out.writeInt(buf.volume);

            for (int index : buf.indices)
            {
                writeVarInt(out, index);
            }

            out.writeInt(buf.blockEntities.size());

            for (BlockEntityRecord record : buf.blockEntities)
            {
                out.writeInt(record.x());
                out.writeInt(record.y());
                out.writeInt(record.z());
                out.writeInt(record.blob().length);
                out.write(record.blob());
            }
        }
    }

    private static ChunkData readChunk(File file) throws IOException
    {
        try (FileInputStream fileIn = new FileInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(fileIn))))
        {
            int paletteSize = in.readInt();
            List<String> palette = new ArrayList<>(paletteSize);

            for (int i = 0; i < paletteSize; i++)
            {
                palette.add(readString(in));
            }

            int volume = in.readInt();
            int[] indices = new int[volume];

            for (int i = 0; i < volume; i++)
            {
                indices[i] = readVarInt(in);
            }

            int count = in.readInt();
            List<BlockEntityRecord> blockEntities = new ArrayList<>(count);

            for (int i = 0; i < count; i++)
            {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                byte[] blob = new byte[in.readInt()];
                in.readFully(blob);
                blockEntities.add(new BlockEntityRecord(x, y, z, blob));
            }

            return new ChunkData(palette, indices, blockEntities);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException
    {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException
    {
        while ((value & ~0x7F) != 0)
        {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }

        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException
    {
        int result = 0;

        for (int shift = 0; shift <= 35; shift += 7)
        {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;

            if ((b & 0x80) == 0)
            {
                return result;
            }
        }

        throw new IOException("Malformed varint in snapshot");
    }
}
