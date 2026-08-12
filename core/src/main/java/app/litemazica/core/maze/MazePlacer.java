package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.WorldAccess;

import java.util.ArrayList;
import java.util.List;
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
        // Trap-plate positions gathered as they're placed, so they can be thinned
        // out afterwards (see TrapArming.armPressurePlates) without a second scan.
        final List<int[]> trapPlates = new ArrayList<>();

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

                if (keep[index])
                {
                    continue; // structure_void — leave the existing ground alone
                }

                int wx = offX + PlacementGeometry.rotX(lx, lz, rot, sizeX, sizeZ);
                int wz = offZ + PlacementGeometry.rotZ(lx, lz, rot, sizeX, sizeZ);
                world.setBlock(wx, wy, wz, palette, index);

                if (trapPlate[index])
                {
                    trapPlates.add(new int[]{wx, wy, wz});
                }
            }

            return cursor[0] >= volume;
        }, () ->
        {
            TrapArming.applyAll(world, maze, rot, offX, offZ, baseY, sizeX, sizeZ, trapPlates);

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

    /** Clears a placed maze's region back to air, batched across ticks. */
    public static void clear(Scheduler scheduler, WorldAccess world, Region region, Runnable onDone)
    {
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
                world.setBlock(minX + lx, minY + ly, minZ + lz, air, 0);
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
