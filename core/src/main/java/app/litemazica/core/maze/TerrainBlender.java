package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.PreparedPalette;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.WorldAccess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static app.litemazica.core.maze.PlacementGeometry.rotX;
import static app.litemazica.core.maze.PlacementGeometry.rotZ;

/**
 * Blends an open-top maze into the terrain above it once its body is placed —
 * split out from {@link MazePlacer} because it is a self-contained algorithm: a
 * per-column cap/clear decision plus a whole-tree flood-fill.
 *
 * <ul>
 *   <li><b>Open / thinly covered:</b> clears all vegetation over the maze
 *   footprint (grass, water, snow, loose leaves) <em>and</em> any lone floating
 *   solid layer — a one-block dirt/grass shelf that would otherwise hang over a
 *   corridor. Any tree <em>whose wood stands over the maze</em> comes off whole —
 *   trunk <em>and</em> its entire connected canopy, chased out past the wall into
 *   the margin (up to {@link PlacementGeometry#CANOPY_MARGIN}) so no floating leaves
 *   or half-trees are left. A tree rooted beside the maze, whose wood never crosses
 *   the footprint, is not seeded and stays standing.</li>
 *   <li><b>Under rock/dirt</b> (a solid slab ≥2 thick within {@link #CAP_WITHIN}):
 *   caps every buried corridor uniformly with the ceiling block and leaves the
 *   natural ground above — and anything growing on it — completely untouched.</li>
 * </ul>
 *
 * <p>Everything it changes lies inside the footprint plus the {@link
 * PlacementGeometry#CANOPY_MARGIN} skirt and the {@code clearAbove} band above (see
 * {@link PlacementGeometry#snapshotRegionFor}), so the terrain snapshot covers it
 * and a remove restores it exactly — stripped trees back, ceiling caps gone.
 */
final class TerrainBlender
{
    /** Column/flood ops charged per server tick, matching the placer's budget. */
    private static final int BLOCKS_PER_TICK = 8192;
    /** Below this many blocks, solid terrain overhead means "buried" → cap the maze. */
    private static final int CAP_WITHIN = 20;
    /** Safety ceiling on a single flood-fill, so a dense forest can't run away. */
    private static final int FLOOD_CAP = 24576;

    private TerrainBlender()
    {
    }

    /**
     * Which footprint columns (indexed {@code lz * sizeX + lx}) the maze actually
     * builds something in — a real block, not {@code structure_void} filler or air.
     * The blend only acts over these, leaving the {@code structure_void} margin ring
     * the schematic reserves around the maze untouched (no cap, no clearing there).
     */
    static boolean[] realColumnMask(MazeSchematic maze, boolean[] keep, int sizeX, int sizeY, int sizeZ)
    {
        boolean[] real = new boolean[sizeX * sizeZ];

        for (int ly = 0; ly < sizeY; ly++)
        {
            for (int lz = 0; lz < sizeZ; lz++)
            {
                for (int lx = 0; lx < sizeX; lx++)
                {
                    int col = lz * sizeX + lx;

                    if (real[col])
                    {
                        continue; // already known to be a real maze column
                    }

                    int index = maze.paletteIndex(lx, ly, lz);

                    if (!keep[index] && TerrainCategory.of(maze.palette().get(index)) != TerrainCategory.AIR)
                    {
                        real[col] = true;
                    }
                }
            }
        }

        return real;
    }

    /** Drives the blend across ticks; {@code onDone} runs on the main thread when finished. */
    static void blend(Scheduler scheduler, WorldAccess world, MazeSchematic maze,
                      int rot, int offX, int offZ, int baseY, int sizeX, int sizeY, int sizeZ,
                      int band, Region region, boolean[] realColumn, Runnable onDone)
    {
        final Blender b = new Blender(world, maze, baseY + sizeY, band, Math.min(CAP_WITHIN, band), region);
        final int columns = sizeX * sizeZ;
        // A flood step reads its 26 neighbours, so it costs more than one block —
        // charge it against the tick budget accordingly.
        final int floodCost = 27;
        final int[] colCursor = {0};
        final int[] extendCursor = {0};

        scheduler.eachTick(() ->
        {
            int ops = 0;

            while (ops < BLOCKS_PER_TICK)
            {
                if (colCursor[0] < columns)
                {
                    int c = colCursor[0]++;

                    // Skip the structure_void margin ring the schematic reserves
                    // around the maze — the blend must only touch terrain over the
                    // maze proper, never cap or clear out in the margin.
                    if (!realColumn[c])
                    {
                        continue;
                    }

                    int lz = c / sizeX;
                    int lx = c % sizeX;
                    b.column(offX + rotX(lx, lz, rot, sizeX, sizeZ), offZ + rotZ(lx, lz, rot, sizeX, sizeZ));
                    ops += band;
                }
                else if (extendCursor[0] < b.buriedCols.size())
                {
                    // Column scan is complete, so buriedCols is final; tuck the
                    // ceiling one block further under the surrounding terrain.
                    b.extendStep(extendCursor[0]++);
                    ops += 8;
                }
                else if (!b.queue.isEmpty())
                {
                    b.floodStep();
                    ops += floodCost;
                }
                else
                {
                    break;
                }
            }

            return colCursor[0] >= columns
                    && extendCursor[0] >= b.buriedCols.size()
                    && b.queue.isEmpty();
        }, onDone);
    }

    /**
     * The mutable state of one blend: the footprint column scan and the tree
     * flood-fill queue. Kept together so {@link #blend}'s tick loop can drive both
     * phases without a long parameter list.
     */
    private static final class Blender
    {
        private final WorldAccess world;
        private final PreparedPalette air;
        private final PreparedPalette ceiling;
        private final int capY;
        private final int band;
        private final int cap;
        private final int worldMin;
        private final int worldMax;
        // The flood may chase a canopy this far past the footprint (and the
        // snapshot is inflated to match), so a tree standing over the maze comes
        // off whole while a tree rooted beyond the skirt is never reached.
        private final int fMinX;
        private final int fMaxX;
        private final int fMinZ;
        private final int fMaxZ;
        private final TerrainCategory[] catAt;
        private final String[] blockAt;

        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        private final HashSet<Long> seen = new HashSet<>();
        private int removed;

        // Buried columns we capped, every world column now carrying ceiling, and
        // every real maze column scanned — so the ceiling can lip one block out over
        // an open corridor at the buried edge without ever leaving the footprint or
        // double-capping a column.
        final ArrayList<int[]> buriedCols = new ArrayList<>();
        private final HashSet<Long> capped = new HashSet<>();
        private final HashSet<Long> mazeCols = new HashSet<>();

        Blender(WorldAccess world, MazeSchematic maze, int capY, int band, int cap, Region region)
        {
            this.world = world;
            this.air = world.preparePalette(List.of("minecraft:air"), 0);
            this.ceiling = world.preparePalette(List.of(maze.ceilingBlock()), 0);
            this.capY = capY;
            this.band = band;
            this.cap = cap;
            this.worldMin = world.minY();
            this.worldMax = world.maxY();
            this.fMinX = region.minX() - PlacementGeometry.CANOPY_MARGIN;
            this.fMaxX = region.maxX() + PlacementGeometry.CANOPY_MARGIN;
            this.fMinZ = region.minZ() - PlacementGeometry.CANOPY_MARGIN;
            this.fMaxZ = region.maxZ() + PlacementGeometry.CANOPY_MARGIN;
            this.catAt = new TerrainCategory[band];
            this.blockAt = new String[band];
        }

        /** Scans one footprint column and applies the cap/clear/seed decisions. */
        void column(int wx, int wz)
        {
            mazeCols.add(colKey(wx, wz));
            int firstSolid = -1;

            for (int d = 0; d < band; d++)
            {
                int wy = capY + d;

                if (wy < worldMin || wy >= worldMax)
                {
                    catAt[d] = TerrainCategory.AIR;
                    blockAt[d] = null;
                    continue;
                }

                String block = world.blockStateAt(wx, wy, wz);
                blockAt[d] = block;
                catAt[d] = TerrainCategory.of(block);

                if (catAt[d] == TerrainCategory.SOLID && firstSolid < 0)
                {
                    firstSolid = d;
                }
            }

            // "Buried" means genuine terrain overhead — a solid slab at least two
            // blocks thick, within reach: real ground the maze is tucked under,
            // worth a ceiling. A single floating layer (one block that, if capped,
            // would just be an isolated patch of ceiling) is treated as clutter over
            // the open top and cleared away in openClear() instead — so no dirt
            // shelves or grass platforms are left hanging over the corridors.
            if (firstSolid >= 0 && firstSolid < cap && slabThickness(firstSolid) >= 2)
            {
                buried(wx, wz);
            }
            else
            {
                openClear(wx, wz);
            }
        }

        /** Consecutive {@link TerrainCategory#SOLID} blocks starting at scan depth {@code d}. */
        private int slabThickness(int d)
        {
            int n = 0;

            while (d + n < band && catAt[d + n] == TerrainCategory.SOLID)
            {
                n++;
            }

            return n;
        }

        private void buried(int wx, int wz)
        {
            // The terrain above is natural ground the maze is tucked under — leave
            // it, and anything growing on it (trees, grass), completely alone. Just
            // seal the corridor beneath it with the ceiling material.

            // An open-top layout can leave a row of air between the wall top and the
            // ceiling plane; close it over walls so the wall meets the roof (never
            // over corridors, where that row is head-room).
            if (capY - 2 >= worldMin
                    && TerrainCategory.of(world.blockStateAt(wx, capY - 1, wz)) != TerrainCategory.SOLID
                    && TerrainCategory.of(world.blockStateAt(wx, capY - 2, wz)) == TerrainCategory.SOLID)
            {
                world.setBlock(wx, capY - 1, wz, ceiling, 0);
            }

            // Cap every buried column at capY with ceiling material, uniformly — the
            // block directly above the maze becomes a clean solid roof and the
            // natural terrain from capY+1 up (the final layer of rock/dirt/grass and
            // anything rooted on it) is preserved on top of it.
            if (capY >= worldMin && capY < worldMax)
            {
                world.setBlock(wx, capY, wz, ceiling, 0);
                capped.add(colKey(wx, wz));
                buriedCols.add(new int[]{wx, wz});
            }
        }

        /**
         * Laps the ceiling one block out over the open corridor at a buried edge:
         * around a capped column, any adjacent <em>maze</em> column that's still open
         * (not itself capped) gets a ceiling block at capY — a short eave where the
         * buried section meets the open-top section, so the terrain above reads as
         * overhanging rather than ending flush at a wall. It never leaves the maze
         * footprint, so the margin and surrounding terrain are untouched.
         */
        void extendStep(int i)
        {
            int[] c = buriedCols.get(i);

            if (capY < worldMin || capY >= worldMax)
            {
                return;
            }

            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx == 0 && dz == 0)
                    {
                        continue;
                    }

                    int nx = c[0] + dx;
                    int nz = c[1] + dz;
                    long k = colKey(nx, nz);

                    // Only lip into an open corridor of the maze itself — a real maze
                    // column that isn't already carrying ceiling.
                    if (mazeCols.contains(k) && !capped.contains(k))
                    {
                        world.setBlock(nx, capY, nz, ceiling, 0);
                        capped.add(k);
                    }
                }
            }
        }

        private static long colKey(int x, int z)
        {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        private void openClear(int wx, int wz)
        {
            // Clear the space over the open top: strip ground cover (grass, water,
            // snow, flowers, leaf litter) and any lone floating solid layer (a thin
            // dirt/grass shelf that would otherwise hang over the maze), walking up
            // the column. Stop at genuine terrain — a solid slab two or more blocks
            // thick, or anything past the cap reach — so a hillside, floating island,
            // or far overhang is left in place.
            //
            // A log over the open top seeds a whole-tree flood; tree *leaves* are NOT
            // stripped here — they come off only when the flood reaches them from a
            // seeded trunk. That way a tree rooted beyond the maze whose canopy merely
            // overhangs the footprint keeps its head, instead of being decapitated.
            for (int d = 0; d < band; d++)
            {
                TerrainCategory cat = catAt[d];

                if (cat == TerrainCategory.SOLID)
                {
                    if (d >= cap || slabThickness(d) >= 2)
                    {
                        break; // genuine terrain / far overhang — leave it and all above
                    }

                    world.setBlock(wx, capY + d, wz, air, 0); // lone shelf — remove, keep going
                }
                else if (cat == TerrainCategory.CLEARABLE)
                {
                    if (TerrainCategory.isLog(blockAt[d]))
                    {
                        seedLog(wx, capY + d, wz);
                    }
                    else if (!TerrainCategory.isTreePart(blockAt[d]))
                    {
                        world.setBlock(wx, capY + d, wz, air, 0); // ground cover, not canopy
                    }
                }
                // AIR / tree leaves → left for the flood to decide
            }
        }

        /**
         * Queues a position for the flood-fill if it's part of the tree body the
         * flood pulls out — a log/stem, or a huge-mushroom cap (which never decays
         * on its own, so the flood has to take it) — in reach, and not already
         * seen. Seeds come only from logs over the maze (see {@link #openClear});
         * caps are reached only by travelling in from a seeded stem, so a mushroom
         * merely overhanging from beside the maze keeps its head, like a tree.
         */
        private void seedLog(int x, int y, int z)
        {
            if (!inReach(x, y, z) || !seen.add(key(x, y, z)))
            {
                return;
            }

            String block = world.blockStateAt(x, y, z);

            if (TerrainCategory.isLog(block) || TerrainCategory.isMushroomCap(block))
            {
                queue.add(new int[]{x, y, z});
            }
        }

        /**
         * Removes one <em>log</em> of a tree rooted over the maze and reaches on to
         * its connected logs (trunk and branches) — logs only, never leaves. The log
         * is broken <em>with</em> a neighbour update, exactly like a player felling a
         * tree, so vanilla's leaf-decay takes over: the canopy this trunk supported,
         * now out of range of any log, decays on its own; a neighbouring tree's
         * canopy stays, still supported by that tree's untouched trunk. Following
         * logs only means a maze-rooted tree never bleeds into a neighbour whose
         * canopy merely touches it — no decapitated margin trees.
         */
        void floodStep()
        {
            int[] p = queue.poll();

            if (p == null)
            {
                return;
            }

            // Break like a player would: notifies the neighbouring leaves so the
            // orphaned canopy recomputes its distance-to-log and decays.
            world.clearWithPhysics(p[0], p[1], p[2]);

            if (++removed >= FLOOD_CAP)
            {
                return; // bail out of a runaway forest
            }

            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        if (dx != 0 || dy != 0 || dz != 0)
                        {
                            seedLog(p[0] + dx, p[1] + dy, p[2] + dz);
                        }
                    }
                }
            }
        }

        private boolean inReach(int x, int y, int z)
        {
            return x >= fMinX && x <= fMaxX && z >= fMinZ && z <= fMaxZ
                    && y >= capY && y < capY + band && y >= worldMin && y < worldMax;
        }

        private long key(int x, int y, int z)
        {
            return ((long) (x - fMinX) << 40) | ((long) (z - fMinZ) << 20) | (long) (y - capY);
        }
    }
}
