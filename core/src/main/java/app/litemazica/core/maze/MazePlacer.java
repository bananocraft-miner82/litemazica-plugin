package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.WorldAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds a {@link MazeSchematic} into a world and clears it again. Placement
 * anchors the maze entrance at a chosen block, rotates the whole thing to the
 * player's facing (block states included), and writes in per-tick batches so a
 * large paste never stalls the server.
 *
 * <p>This is the orchestrator; the pieces live alongside it:
 * <ul>
 *   <li>{@link PlacementGeometry} — where each voxel lands (rotation + anchor).</li>
 *   <li>{@link TrapArming} — the post-placement passes (trapped chests, loot,
 *   dispensers, spawners, pressure plates).</li>
 *   <li>{@link BuildContents} — custom builds' block-entity data and entities.</li>
 *   <li>{@link TerrainBlender} — blending an open-top maze into the terrain above.</li>
 * </ul>
 *
 * <p>Platform-neutral: blocks are written through {@link WorldAccess} and the
 * batching is driven by {@link Scheduler}.
 */
public final class MazePlacer
{
    /** Blocks written per server tick. Tune for the TPS/paste-speed trade-off. */
    private static final int BLOCKS_PER_TICK = 8192;

    /** Voxels looked at per tick by the fluid pass, which writes only a few of them. */
    private static final int FLUID_SCANS_PER_TICK = BLOCKS_PER_TICK * 8;

    /**
     * Blocks that flow, or live in what does. Placing one schedules the fluid to
     * spread a few ticks later, even with no block updates, so these go in last:
     * by then every block around them is in place, and a source can't run off
     * into a spot a later batch hadn't reached yet (or, on a rebuild, into what
     * the old maze left there).
     */
    private static final Set<String> FLUIDS = Set.of(
            "minecraft:water", "minecraft:lava", "minecraft:bubble_column",
            "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass", "minecraft:tall_seagrass");

    private MazePlacer()
    {
    }

    /**
     * Places {@code maze} at (ax,ay,az) using the maze's own {@link
     * MazeSchematic#clearAbove()} headroom. See the overload for the full
     * contract.
     */
    public static void place(Scheduler scheduler, WorldAccess world, MazeSchematic maze,
                             int ax, int ay, int az, float yaw,
                             Consumer<Region> onDone)
    {
        place(scheduler, world, maze, ax, ay, az, yaw, maze.clearAbove(), onDone);
    }

    /**
     * Places {@code maze} so its entrance sits at (ax,ay,az) — the player's feet
     * — and its body extends in the direction of {@code yaw}. Runs across ticks;
     * {@code onDone} receives the occupied region on completion.
     *
     * <p>Two schematic conventions shape what gets written:
     * <ul>
     *   <li>{@code minecraft:structure_void} voxels are <em>skipped</em>, leaving
     *   the existing world block in place — this is how a basement filled with
     *   {@code structure_void} sinks its drop-trap shafts (real air, which still
     *   carves) into the surrounding ground without hollowing it out.</li>
     *   <li>With {@code clearAbove} &gt; 0 an open-top maze is <em>blended</em>
     *   into the terrain above it once the body is laid; see {@link TerrainBlender}.
     *   The region handed to {@code onDone} still describes the maze body only, so a
     *   terrain snapshot (which inflates it via {@link
     *   PlacementGeometry#snapshotRegionFor}) restores everything the blend touched.</li>
     * </ul>
     */
    public static void place(Scheduler scheduler, WorldAccess world, MazeSchematic maze,
                             int ax, int ay, int az, float yaw, int clearAbove,
                             Consumer<Region> onDone)
    {
        final int band = Math.max(0, clearAbove);
        final PlacementGeometry.Transform t = PlacementGeometry.computeTransform(maze, ax, ay, az, yaw, band);
        final PreparedPalette palette = world.preparePalette(maze.palette(), t.rot());
        final boolean[] keep = keepMask(maze.palette());
        final boolean[] trapPlate = trapPlateMask(maze.palette());
        final boolean[] fluid = fluidMask(maze.palette());
        final boolean anyFluid = holdsFluids(maze.palette());
        final int rot = t.rot();
        final int sizeX = maze.sizeX();
        final int sizeY = maze.sizeY();
        final int sizeZ = maze.sizeZ();
        final int offX = t.offX();
        final int offZ = t.offZ();
        final int baseY = t.baseY();
        final Region region = t.region();
        final int worldMin = world.minY();
        final int worldMax = world.maxY();
        final int rowXZ = sizeX * sizeZ;
        // Only the maze body here; the band above is handled by the blend pass.
        final long volume = (long) rowXZ * sizeY;
        final long[] cursor = {0};
        final long[] fluidCursor = {0};
        // Trap-plate positions gathered as they're placed, so they can be thinned
        // out afterwards (see TrapArming.armPressurePlates) without a second scan.
        final List<int[]> trapPlates = new ArrayList<>();
        // A rebuild lands in the same region: take away the entities the last
        // placement spawned before laying the new one.
        world.removeTaggedEntities(region, BuildContents.ENTITY_TAG);

        scheduler.eachTick(() ->
        {
            int placed = 0;

            while (cursor[0] < volume && placed < BLOCKS_PER_TICK)
            {
                int ly = (int) (cursor[0] / rowXZ);
                int rem = (int) (cursor[0] - (long) ly * rowXZ);
                int lz = rem / sizeX;
                int lx = rem % sizeX;
                cursor[0]++;
                placed++;

                int wy = baseY + ly;

                if (wy < worldMin || wy >= worldMax)
                {
                    continue;
                }

                int index = maze.paletteIndex(lx, ly, lz);

                if (keep[index] || fluid[index])
                {
                    continue; // structure_void leaves the existing ground alone; fluids come last
                }

                int wx = offX + PlacementGeometry.rotX(lx, lz, rot, sizeX, sizeZ);
                int wz = offZ + PlacementGeometry.rotZ(lx, lz, rot, sizeX, sizeZ);
                world.setBlock(wx, wy, wz, palette, index);

                if (trapPlate[index])
                {
                    trapPlates.add(new int[]{wx, wy, wz});
                }
            }

            if (placed > 0)
            {
                return cursor[0] >= volume && !anyFluid; // the fluids start next tick
            }

            // Then the fluids, now that everything around them is in place.
            int written = 0;
            int scanned = 0;

            while (anyFluid && fluidCursor[0] < volume && written < BLOCKS_PER_TICK && scanned < FLUID_SCANS_PER_TICK)
            {
                int ly = (int) (fluidCursor[0] / rowXZ);
                int rem = (int) (fluidCursor[0] - (long) ly * rowXZ);
                int lz = rem / sizeX;
                int lx = rem % sizeX;
                fluidCursor[0]++;
                scanned++;
                int wy = baseY + ly;
                int index = maze.paletteIndex(lx, ly, lz);

                if (!fluid[index] || wy < worldMin || wy >= worldMax)
                {
                    continue;
                }

                world.setBlock(offX + PlacementGeometry.rotX(lx, lz, rot, sizeX, sizeZ), wy,
                        offZ + PlacementGeometry.rotZ(lx, lz, rot, sizeX, sizeZ), palette, index);
                written++;
            }

            return !anyFluid || fluidCursor[0] >= volume;
        }, () ->
        {
            TrapArming.applyAll(world, maze, rot, offX, offZ, baseY, sizeX, sizeZ, trapPlates);
            // Custom builds' sign text, container contents and decorations.
            BuildContents.apply(world, maze, rot, offX, offZ, baseY, sizeX, sizeZ);

            if (band > 0)
            {
                boolean[] realColumn = TerrainBlender.realColumnMask(maze, keep, sizeX, sizeY, sizeZ);
                TerrainBlender.blend(scheduler, world, maze, rot, offX, offZ, baseY, sizeX, sizeY, sizeZ, band, region,
                        realColumn, () -> onDone.accept(region));
            }
            else
            {
                onDone.accept(region);
            }
        });
    }

    /**
     * What an operator should know before a maze is placed on this server: blocks
     * it doesn't have (placed as air), and sign text in a form it doesn't read
     * (a file made for another version, or an API too old to build for this one).
     */
    static List<String> versionNotes(WorldAccess world, MazeSchematic maze, int serverDataVersion)
    {
        List<String> notes = new ArrayList<>();
        String unknown = MazeText.unknownBlocksNote(world.preparePalette(maze.palette(), 0).unknownBlocks());
        String text = MazeText.textFormatNote(maze.dataVersion(), serverDataVersion, hasText(maze));

        if (unknown != null)
        {
            notes.add(unknown);
        }

        if (text != null)
        {
            notes.add(text);
        }

        return notes;
    }

    /** Whether a maze has sign text or names, whose stored form depends on the version. */
    private static boolean hasText(MazeSchematic maze)
    {
        for (Map<String, Object> te : maze.tileEntities())
        {
            if (te.containsKey("front_text") || te.containsKey("back_text") || te.containsKey("Text1") || te.containsKey("CustomName"))
            {
                return true;
            }
        }

        for (Map<String, Object> e : maze.entities())
        {
            if (e.containsKey("CustomName"))
            {
                return true;
            }
        }

        return false;
    }

    /** Whether any of a palette's blocks flow, or live in what does (see {@link #FLUIDS}). */
    static boolean holdsFluids(List<String> palette)
    {
        for (boolean f : fluidMask(palette))
        {
            if (f)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Clears a placed maze that holds water (or lava) to air, then runs {@code
     * then}; runs it straight away for any other maze. Taking one away a part at
     * a time (terrain goes back a chunk at a time) would otherwise leave its water
     * beside the parts already done, free to run into them.
     */
    static void drainThen(Scheduler scheduler, WorldAccess world, PlacedMaze maze, Runnable then)
    {
        if (maze.holdsFluids())
        {
            clear(scheduler, world, maze.region(), then);
        }
        else
        {
            then.run();
        }
    }

    /**
     * Clears a placed maze's region back to air, batched across ticks. Top layer
     * first: water never rises, so a flooded maze is taken away from the top
     * down without any of it running into the layers already cleared.
     */
    public static void clear(Scheduler scheduler, WorldAccess world, Region region, Runnable onDone)
    {
        world.removeTaggedEntities(region, BuildContents.ENTITY_TAG);
        final PreparedPalette air = world.preparePalette(List.of("minecraft:air"), 0);
        final int minX = region.minX();
        final int minZ = region.minZ();
        final int minY = Math.max(region.minY(), world.minY());
        final int maxY = Math.min(region.maxY(), world.maxY() - 1);
        final int spanX = region.maxX() - minX + 1;
        final int spanZ = region.maxZ() - minZ + 1;
        final int rowXZ = spanX * spanZ;
        final long volume = (long) rowXZ * (maxY - minY + 1);
        final long[] cursor = {0};

        scheduler.eachTick(() ->
        {
            int placed = 0;

            while (cursor[0] < volume && placed < BLOCKS_PER_TICK)
            {
                int ly = (int) (cursor[0] / rowXZ);
                int rem = (int) (cursor[0] - (long) ly * rowXZ);
                int lz = rem / spanX;
                int lx = rem % spanX;
                cursor[0]++;
                placed++;
                world.setBlock(minX + lx, maxY - ly, minZ + lz, air, 0);
            }

            return cursor[0] >= volume;
        }, onDone);
    }

    /** Palette indices whose block is {@code structure_void} — skipped on placement. */
    private static boolean[] keepMask(List<String> palette)
    {
        boolean[] keep = new boolean[palette.size()];

        for (int i = 0; i < palette.size(); i++)
        {
            String block = palette.get(i);
            keep[i] = block.equals("minecraft:structure_void")
                    || block.startsWith("minecraft:structure_void[");
        }

        return keep;
    }

    /** Palette indices whose block flows, or lives in what does: placed after everything else. */
    private static boolean[] fluidMask(List<String> palette)
    {
        boolean[] fluid = new boolean[palette.size()];

        for (int i = 0; i < palette.size(); i++)
        {
            String block = palette.get(i);
            int bracket = block.indexOf('[');
            fluid[i] = FLUIDS.contains(bracket < 0 ? block : block.substring(0, bracket));
        }

        return fluid;
    }

    /** Palette indices whose block is the reserved trap-plate marker — thinned out after placement. */
    private static boolean[] trapPlateMask(List<String> palette)
    {
        boolean[] plate = new boolean[palette.size()];

        for (int i = 0; i < palette.size(); i++)
        {
            String block = palette.get(i);
            plate[i] = block.equals(TrapArming.TRAP_PLATE) || block.startsWith(TrapArming.TRAP_PLATE + "[");
        }

        return plate;
    }
}
