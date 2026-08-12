package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounding-box maths. {@code contains} decides whether a player is inside a maze
 * (and so whether a scheduled reset is safe); {@code intersects} stops a new
 * maze being pasted over an existing one.
 */
class RegionTest
{
    private static final Region BOX = new Region(0, 60, 0, 10, 70, 20);

    @Test
    void containsIsInclusiveOfItsBounds()
    {
        assertTrue(BOX.contains(0, 60, 0));
        assertTrue(BOX.contains(10, 70, 20));
        assertTrue(BOX.contains(5, 65, 10));
    }

    @Test
    void containsRejectsAnythingOutside()
    {
        assertFalse(BOX.contains(-1, 65, 10));
        assertFalse(BOX.contains(11, 65, 10));
        assertFalse(BOX.contains(5, 59, 10));
        assertFalse(BOX.contains(5, 71, 10));
        assertFalse(BOX.contains(5, 65, -1));
        assertFalse(BOX.contains(5, 65, 21));
    }

    @Test
    void overlappingBoxesIntersect()
    {
        assertTrue(BOX.intersects(new Region(5, 65, 5, 15, 75, 25)));
        assertTrue(BOX.intersects(new Region(-5, 55, -5, 5, 65, 5)));
    }

    @Test
    void touchingBoxesIntersectBecauseBoundsAreInclusive()
    {
        // Shares exactly the x=10 plane — that's a shared block, so it counts.
        assertTrue(BOX.intersects(new Region(10, 60, 0, 20, 70, 20)));
    }

    @Test
    void separationOnAnySingleAxisIsEnough()
    {
        assertFalse(BOX.intersects(new Region(11, 60, 0, 20, 70, 20)), "separated on x");
        assertFalse(BOX.intersects(new Region(0, 71, 0, 10, 80, 20)), "separated on y");
        assertFalse(BOX.intersects(new Region(0, 60, 21, 10, 70, 40)), "separated on z");
    }

    @Test
    void intersectsIsSymmetric()
    {
        Region other = new Region(5, 65, 5, 15, 75, 25);

        assertEquals(BOX.intersects(other), other.intersects(BOX));
    }

    @Test
    void volumeCountsBlocksInclusively()
    {
        assertEquals(11L * 11L * 21L, BOX.volume());
        assertEquals(1L, new Region(0, 0, 0, 0, 0, 0).volume());
    }

    @Test
    void sizeStringReadsAsWidthHeightDepth()
    {
        assertEquals("11×11×21", BOX.sizeString());
    }
}
