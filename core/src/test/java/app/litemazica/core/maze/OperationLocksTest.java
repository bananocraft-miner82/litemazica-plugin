package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maze-rewrite lock table: acquisition is a compare-and-set, and a lock that
 * outlives {@link OperationLocks#STALE_MS} is swept so a stranded operation can't
 * block a maze forever.
 */
class OperationLocksTest
{
    @Test
    void acquiringAFreeLockSucceedsAndMarksItBusy()
    {
        OperationLocks locks = new OperationLocks();

        assertTrue(locks.tryAcquire("m1", 0L), "a free lock should be acquirable");
        assertTrue(locks.isBusy("m1"));
    }

    @Test
    void aSecondAcquireIsRefusedUntilRelease()
    {
        OperationLocks locks = new OperationLocks();
        locks.tryAcquire("m1", 0L);

        assertFalse(locks.tryAcquire("m1", 5L), "a held lock can't be taken again");

        locks.release("m1");
        assertFalse(locks.isBusy("m1"));
        assertTrue(locks.tryAcquire("m1", 10L), "released, so acquirable again");
    }

    @Test
    void reserveTakesTheLockUnconditionally()
    {
        OperationLocks locks = new OperationLocks();
        locks.reserve("m1", 0L);

        assertTrue(locks.isBusy("m1"));
        assertFalse(locks.tryAcquire("m1", 1L), "reserve holds it against a later acquire");
    }

    @Test
    void sweepReleasesOnlyLocksOlderThanTheStaleWindow()
    {
        OperationLocks locks = new OperationLocks();
        locks.tryAcquire("old", 0L);
        locks.tryAcquire("fresh", 0L);

        // "fresh" was re-touched just before the sweep; "old" is well past the window.
        long now = OperationLocks.STALE_MS + 1;
        locks.release("fresh");
        locks.tryAcquire("fresh", now);

        List<String> released = locks.sweepStale(now);

        assertEquals(List.of("old"), released, "only the stale lock is swept");
        assertFalse(locks.isBusy("old"));
        assertTrue(locks.isBusy("fresh"), "a lock inside the window survives");
    }

    @Test
    void sweepReturnsEmptyWhenNothingIsStale()
    {
        OperationLocks locks = new OperationLocks();
        locks.tryAcquire("m1", 100L);

        assertTrue(locks.sweepStale(100L + OperationLocks.STALE_MS).isEmpty(),
                "a lock exactly at the window edge is not yet stale");
        assertTrue(locks.isBusy("m1"));
    }
}
