package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regeneration scheduling and the bounding-box delegates the scheduler leans on. */
class PlacedMazeTest
{
    private static final long MINUTE = 60_000L;

    private static PlacedMaze maze(int regenMinutes, boolean fresh, long lastRegen)
    {
        return new PlacedMaze("m1", "world", MazeSource.TYPE_API, "CODE", 10, 64, 20, 0f,
                new Region(0, 60, 0, 10, 70, 20), regenMinutes, fresh, 0L, lastRegen);
    }

    @Test
    void aMazeWithNoIntervalIsNeverDue()
    {
        assertFalse(maze(0, true, 0L).isRegenDue(Long.MAX_VALUE / 2));
    }

    @Test
    void isNotDueBeforeTheIntervalElapses()
    {
        assertFalse(maze(10, true, 0L).isRegenDue(9 * MINUTE));
    }

    @Test
    void becomesDueOnTheIntervalAndStaysDue()
    {
        assertTrue(maze(10, true, 0L).isRegenDue(10 * MINUTE));
        assertTrue(maze(10, true, 0L).isRegenDue(90 * MINUTE));
    }

    @Test
    void intervalIsMeasuredFromTheLastReset()
    {
        PlacedMaze m = maze(10, true, 0L);
        assertTrue(m.isRegenDue(10 * MINUTE));

        m.setLastRegenEpochMs(10 * MINUTE);
        assertFalse(m.isRegenDue(15 * MINUTE), "clock should restart after a reset");
        assertTrue(m.isRegenDue(20 * MINUTE));
    }

    @Test
    void regenSummaryDescribesTheSchedule()
    {
        assertEquals("off", maze(0, true, 0L).regenSummary());
        assertEquals("daily fresh", maze(RegenInterval.DAILY.minutes(), true, 0L).regenSummary());
        assertEquals("weekly same", maze(RegenInterval.WEEKLY.minutes(), false, 0L).regenSummary());
        // Non-preset values still read sensibly.
        assertEquals("10m fresh", maze(10, true, 0L).regenSummary());
        assertEquals("5m same", maze(5, false, 0L).regenSummary());
    }

    @Test
    void delegatesToItsRegion()
    {
        PlacedMaze m = maze(0, true, 0L);

        assertEquals(0, m.minX());
        assertEquals(60, m.minY());
        assertEquals(0, m.minZ());
        assertEquals(10, m.maxX());
        assertEquals(70, m.maxY());
        assertEquals(20, m.maxZ());
        assertTrue(m.contains(5, 65, 10));
        assertFalse(m.contains(50, 65, 10));
    }

    @Test
    void movingTheRegionMovesTheDelegates()
    {
        // A fresh layout can shift the footprint, so the delegates must follow.
        PlacedMaze m = maze(0, true, 0L);
        m.setRegion(new Region(100, 60, 100, 110, 70, 120));

        assertEquals(100, m.minX());
        assertFalse(m.contains(5, 65, 10));
        assertTrue(m.contains(105, 65, 110));
    }
}
