package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;

/**
 * The pure geometry of a placement: where a maze lands when its entrance is
 * anchored at a block and the whole thing is rotated to a player's facing. This
 * is the highest-risk logic in the plugin — an off-by-one puts the entrance in a
 * wall — and it touches no world, so it is pinned by {@link PlacementGeometryTest}.
 *
 * <p>{@link MazePlacer} executes a placement against a world using these results;
 * {@link TerrainBlender} reuses the rotation helpers to walk the columns above it.
 */
final class PlacementGeometry
{
    /**
     * How far past the footprint a tree-removal flood may reach — a canopy spills
     * beyond the wall it's rooted near, and this is the distance it is chased. Also
     * the amount the terrain snapshot is inflated by (see {@link #snapshotRegionFor}),
     * so everything the flood can touch is captured and a remove restores it.
     */
    static final int CANOPY_MARGIN = 8;

    private PlacementGeometry()
    {
    }

    /** The rotation + translation + resulting world region for a placement. */
    record Transform(int rot, int offX, int offZ, int baseY, Region region)
    {
    }

    /**
     * The world region a maze would occupy if placed at (ax,ay,az) facing
     * {@code yaw}, without touching the world. Used to check for overlaps and
     * for players before committing to a placement.
     */
    static Region regionFor(MazeSchematic maze, int ax, int ay, int az, float yaw)
    {
        return regionFor(maze, ax, ay, az, yaw, maze.clearAbove());
    }

    /** As {@link #regionFor(MazeSchematic, int, int, int, float)}, with an explicit headroom band. */
    static Region regionFor(MazeSchematic maze, int ax, int ay, int az, float yaw, int clearAbove)
    {
        return computeTransform(maze, ax, ay, az, yaw, Math.max(0, clearAbove)).region();
    }

    /**
     * The region a terrain snapshot must cover for a placement: the maze
     * {@code body}, plus the {@code clearAbove} band above it, plus a {@link
     * #CANOPY_MARGIN} skirt on every side — the band and skirt bound everywhere the
     * blend can edit (vegetation cleared, trees flooded out past the wall, corridors
     * capped). Kept separate from the stored (body) region so a clear-on-remove
     * without a snapshot only touches the maze, never the terrain around it.
     */
    static Region snapshotRegionFor(Region body, int clearAbove)
    {
        int m = CANOPY_MARGIN;
        return new Region(body.minX() - m, body.minY(), body.minZ() - m,
                body.maxX() + m, body.maxY() + Math.max(0, clearAbove), body.maxZ() + m);
    }

    static Transform computeTransform(MazeSchematic maze, int ax, int ay, int az, float yaw, int clearAbove)
    {
        int rot = rotationFor(maze, yaw);
        int sizeX = maze.sizeX();
        int sizeY = maze.sizeY();
        int sizeZ = maze.sizeZ();

        // Translate so the rotated entrance lands on the anchor block.
        int rex = rotX(maze.entranceX(), maze.entranceZ(), rot, sizeX, sizeZ);
        int rez = rotZ(maze.entranceX(), maze.entranceZ(), rot, sizeX, sizeZ);
        int offX = ax - rex;
        int offZ = az - rez;
        // The player stands in the entrance's *own* level, so sink the body by
        // that level's height above the floor plane (entranceY, in paste-origin
        // coords). A ground-level (level-0) entrance has entranceY == 0 and lands
        // its floor at ay-1; a higher-level entrance drops the whole maze further,
        // putting its own floor at ay-1 and the levels below it underground.
        int baseY = ay - 1 + maze.originY() - maze.entranceY(); // entrance level sits at ay-1

        // Bounding box from the four rotated xz corners.
        int[][] corners = {{0, 0}, {sizeX - 1, 0}, {0, sizeZ - 1}, {sizeX - 1, sizeZ - 1}};
        int lowX = Integer.MAX_VALUE, highX = Integer.MIN_VALUE, lowZ = Integer.MAX_VALUE, highZ = Integer.MIN_VALUE;

        for (int[] c : corners)
        {
            int wx = offX + rotX(c[0], c[1], rot, sizeX, sizeZ);
            int wz = offZ + rotZ(c[0], c[1], rot, sizeX, sizeZ);
            lowX = Math.min(lowX, wx);
            highX = Math.max(highX, wx);
            lowZ = Math.min(lowZ, wz);
            highZ = Math.max(highZ, wz);
        }

        // The stored region is the maze body only — what the plugin actually
        // wrote. The blend reaches above and just beyond it, but deliberately
        // isn't folded into this region: doing so ballooned the terrain snapshot
        // (a tall band of mostly-air) past its size cap, and made a snapshot-less
        // remove clear that whole band — eating the surrounding terrain. So the
        // snapshot/overlap/clear all stay body-sized; blend edits are one-way.
        Region region = new Region(lowX, baseY, lowZ, highX, baseY + sizeY - 1, highZ);

        return new Transform(rot, offX, offZ, baseY, region);
    }

    // ── rotation geometry ────────────────────────────────────────────────────

    /**
     * Picks the 90° rotation that makes the maze body extend in the player's
     * facing direction. The maze extends from its entrance toward its centre;
     * we rotate that vector onto the player's forward vector.
     */
    private static int rotationFor(MazeSchematic maze, float yaw)
    {
        int dx = maze.sizeX() / 2 - maze.entranceX();
        int dz = maze.sizeZ() / 2 - maze.entranceZ();
        int fx;
        int fz;

        if (Math.abs(dx) >= Math.abs(dz))
        {
            fx = Integer.signum(dx);
            fz = 0;
        }
        else
        {
            fx = 0;
            fz = Integer.signum(dz);
        }

        if (fx == 0 && fz == 0)
        {
            fz = 1;
        }

        int[] forward = forwardFromYaw(yaw);

        for (int rot = 0; rot < 4; rot++)
        {
            if (rotVecX(fx, fz, rot) == forward[0] && rotVecZ(fx, fz, rot) == forward[1])
            {
                return rot;
            }
        }

        return 0;
    }

    /** Player forward as a unit cardinal vector. Minecraft yaw: 0=+z, 90=-x, 180=-z, 270=+x. */
    private static int[] forwardFromYaw(float yaw)
    {
        float y = yaw % 360f;

        if (y < 0)
        {
            y += 360f;
        }

        if (y >= 315f || y < 45f)
        {
            return new int[]{0, 1};   // south
        }

        if (y < 135f)
        {
            return new int[]{-1, 0};  // west
        }

        if (y < 225f)
        {
            return new int[]{0, -1};  // north
        }

        return new int[]{1, 0};       // east
    }

    // Point rotation within a region of size (sizeX, sizeZ). rot 1 = clockwise 90°.
    static int rotX(int x, int z, int rot, int sizeX, int sizeZ)
    {
        return switch (rot)
        {
            case 1 -> sizeZ - 1 - z;
            case 2 -> sizeX - 1 - x;
            case 3 -> z;
            default -> x;
        };
    }

    static int rotZ(int x, int z, int rot, int sizeX, int sizeZ)
    {
        return switch (rot)
        {
            case 1 -> x;
            case 2 -> sizeZ - 1 - z;
            case 3 -> sizeX - 1 - x;
            default -> z;
        };
    }

    // Direction-vector rotation (no size offset), matching the point rotation above.
    private static int rotVecX(int dx, int dz, int rot)
    {
        return switch (rot)
        {
            case 1 -> -dz;
            case 2 -> -dx;
            case 3 -> dz;
            default -> dx;
        };
    }

    private static int rotVecZ(int dx, int dz, int rot)
    {
        return switch (rot)
        {
            case 1 -> dx;
            case 2 -> -dz;
            case 3 -> -dx;
            default -> dz;
        };
    }
}
