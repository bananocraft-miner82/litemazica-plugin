package app.litemazica.core.maze;


import app.litemazica.core.api.MazeSchematic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The placement transform: where a maze lands when anchored at a player and
 * rotated to their facing. This is the highest-risk logic in the plugin — an
 * off-by-one here puts the entrance in a wall — and it's pure maths, so it's
 * worth pinning down without a server.
 */
class PlacementGeometryTest
{
    // Minecraft yaw: 0 = south (+z), 90 = west (-x), 180 = north (-z), 270 = east (+x).
    private static final float SOUTH = 0f;
    private static final float WEST = 90f;
    private static final float NORTH = 180f;
    private static final float EAST = 270f;

    private static final int SIZE_X = 7;
    private static final int SIZE_Y = 4;
    private static final int SIZE_Z = 11;
    /** Entrance in the middle of the north edge, so the body extends +z. */
    private static final int ENTRANCE_X = 3;
    private static final int ENTRANCE_Z = 0;

    private static final int AX = 100;
    private static final int AY = 64;
    private static final int AZ = 200;

    private static MazeSchematic maze()
    {
        return maze(0);
    }

    private static MazeSchematic maze(int originY)
    {
        long[] states = new long[(int) (((long) SIZE_X * SIZE_Y * SIZE_Z * 2 + 63) / 64) + 1];

        return new MazeSchematic("test", 3465, SIZE_X, SIZE_Y, SIZE_Z, originY,
                ENTRANCE_X, 0, ENTRANCE_Z, 0, 0,
                List.of("minecraft:air"), states, List.of(), 0, "minecraft:stone_bricks");
    }

    private static int width(Region r)
    {
        return r.maxX() - r.minX() + 1;
    }

    private static int depth(Region r)
    {
        return r.maxZ() - r.minZ() + 1;
    }

    @Test
    void entranceLandsOnTheAnchorWhicheverWayYouFace()
    {
        for (float yaw : new float[]{SOUTH, WEST, NORTH, EAST})
        {
            Region r = PlacementGeometry.regionFor(maze(), AX, AY, AZ, yaw);
            assertTrue(r.contains(AX, AY - 1, AZ), "entrance column missing from region at yaw " + yaw);
        }
    }

    @Test
    void floorSitsOneBlockBelowTheAnchor()
    {
        Region r = PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH);

        // The player stands *on* the maze floor, so it's at feet-1.
        assertEquals(AY - 1, r.minY());
        assertEquals(AY - 1 + SIZE_Y - 1, r.maxY());
    }

    @Test
    void originYSinksTheRegionForBasementLayers()
    {
        // A negative originY means trap/basement layers hang below the floor.
        Region r = PlacementGeometry.regionFor(maze(-3), AX, AY, AZ, SOUTH);

        assertEquals(AY - 1 - 3, r.minY());
    }

    @Test
    void rotationPreservesVolume()
    {
        for (float yaw : new float[]{SOUTH, WEST, NORTH, EAST})
        {
            Region r = PlacementGeometry.regionFor(maze(), AX, AY, AZ, yaw);
            assertEquals(maze().volume(), r.volume(), "volume changed at yaw " + yaw);
        }
    }

    @Test
    void bodyExtendsSouthWhenFacingSouth()
    {
        Region r = PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH);

        assertEquals(AZ, r.minZ(), "entrance should be at the near edge");
        assertEquals(AZ + SIZE_Z - 1, r.maxZ(), "body should run away to the south");
    }

    @Test
    void bodyExtendsNorthWhenFacingNorth()
    {
        Region r = PlacementGeometry.regionFor(maze(), AX, AY, AZ, NORTH);

        assertEquals(AZ, r.maxZ());
        assertEquals(AZ - SIZE_Z + 1, r.minZ());
    }

    @Test
    void bodyExtendsEastWhenFacingEast()
    {
        Region r = PlacementGeometry.regionFor(maze(), AX, AY, AZ, EAST);

        assertEquals(AX, r.minX());
        assertEquals(AX + SIZE_Z - 1, r.maxX());
    }

    @Test
    void bodyExtendsWestWhenFacingWest()
    {
        Region r = PlacementGeometry.regionFor(maze(), AX, AY, AZ, WEST);

        assertEquals(AX, r.maxX());
        assertEquals(AX - SIZE_Z + 1, r.minX());
    }

    @Test
    void quarterTurnsSwapTheFootprint()
    {
        Region south = PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH);
        Region east = PlacementGeometry.regionFor(maze(), AX, AY, AZ, EAST);

        assertEquals(SIZE_X, width(south));
        assertEquals(SIZE_Z, depth(south));

        assertEquals(SIZE_Z, width(east));
        assertEquals(SIZE_X, depth(east));
    }

    @Test
    void yawIsNormalisedBeforeUse()
    {
        // Bukkit hands out yaw well outside [0,360).
        assertEquals(PlacementGeometry.regionFor(maze(), AX, AY, AZ, EAST),
                PlacementGeometry.regionFor(maze(), AX, AY, AZ, -90f));
        assertEquals(PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH),
                PlacementGeometry.regionFor(maze(), AX, AY, AZ, 720f));
    }

    @Test
    void nearlyCardinalYawSnapsToTheSameFacing()
    {
        Region exact = PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH);

        assertEquals(exact, PlacementGeometry.regionFor(maze(), AX, AY, AZ, 44f));
        assertEquals(exact, PlacementGeometry.regionFor(maze(), AX, AY, AZ, -44f));
    }

    @Test
    void isDeterministic()
    {
        assertEquals(PlacementGeometry.regionFor(maze(), AX, AY, AZ, WEST),
                PlacementGeometry.regionFor(maze(), AX, AY, AZ, WEST));
    }

    @Test
    void blendDoesNotInflateTheStoredRegion()
    {
        // The blend reaches above and beyond the maze, but the stored region
        // stays body-sized so the terrain snapshot and a clear-on-remove don't
        // balloon past their size cap and eat the surrounding terrain.
        assertEquals(PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH, 0),
                PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH, 40));
    }

    @Test
    void negativeClearAboveIsTreatedAsZero()
    {
        assertEquals(PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH, 0),
                PlacementGeometry.regionFor(maze(), AX, AY, AZ, SOUTH, -5));
    }
}
