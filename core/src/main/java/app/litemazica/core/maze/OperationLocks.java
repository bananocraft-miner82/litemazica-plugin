package app.litemazica.core.maze;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tracks which mazes are mid-rewrite — a build, a regeneration, an in-place
 * edit, or a removal — so nothing else touches those blocks while they are being
 * laid down. This is the single most safety-critical piece of shared state in
 * {@link MazeService}: holding a lock keeps the scheduler from double-firing,
 * keeps players from being teleported into a half-built maze, and stops two
 * removals racing.
 *
 * <p>Each lock is timestamped because the chain it guards (fetch → restore →
 * capture → place) spans many ticks and can be cut short by a world unload; without
 * {@link #sweepStale} a stranded id would block that maze from ever regenerating
 * again. {@link #sweepStale} releases any lock older than {@link #STALE_MS}.
 *
 * <h2>Threading</h2>
 * The backing map is synchronized, so every method here is individually atomic
 * and safe to call from any thread. Acquisition must go through {@link #tryAcquire}
 * — a compare-and-set — never a separate {@link #isBusy} check followed by a
 * {@link #reserve}, which would race. {@link #isBusy} is only an advisory read for
 * the user-facing "busy right now" messages that front the real gate.
 */
final class OperationLocks
{
    /** How long a lock may be held before {@link #sweepStale} treats it as abandoned. */
    static final long STALE_MS = 10 * 60 * 1000L;

    private final Map<String, Long> heldSince = Collections.synchronizedMap(new HashMap<>());

    /**
     * Takes the lock for {@code id} if it is free.
     *
     * @return true if this call acquired it; false if it was already held. This is
     *         the compare-and-set that actually gates a rewrite.
     */
    boolean tryAcquire(String id, long now)
    {
        return heldSince.putIfAbsent(id, now) == null;
    }

    /**
     * Takes the lock unconditionally, for a path that has already run every check
     * that could reject and is committed to the rewrite (the initial build reserves
     * its freshly-minted id before the multi-tick snapshot + placement begins).
     */
    void reserve(String id, long now)
    {
        heldSince.put(id, now);
    }

    /** True while {@code id} is mid-rewrite. Advisory — see the threading note. */
    boolean isBusy(String id)
    {
        return heldSince.containsKey(id);
    }

    /** Releases {@code id}'s lock, if held. */
    void release(String id)
    {
        heldSince.remove(id);
    }

    /**
     * Releases every lock older than {@link #STALE_MS} — a rewrite that started but
     * never finished, because the world was unloaded mid-chain or a step was
     * cancelled at shutdown. Without this the maze would stay locked until the next
     * restart.
     *
     * @return the ids that were released, so the caller can log them. Empty when
     *         nothing was stale.
     */
    List<String> sweepStale(long now)
    {
        List<String> released = new ArrayList<>();

        synchronized (heldSince)
        {
            Iterator<Map.Entry<String, Long>> it = heldSince.entrySet().iterator();

            while (it.hasNext())
            {
                Map.Entry<String, Long> entry = it.next();

                if (now - entry.getValue() > STALE_MS)
                {
                    released.add(entry.getKey());
                    it.remove();
                }
            }
        }

        return released;
    }
}
