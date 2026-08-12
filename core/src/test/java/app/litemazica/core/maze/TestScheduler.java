package app.litemazica.core.maze;

import app.litemazica.core.platform.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A synchronous {@link Scheduler} for tests: per-tick slices run to completion
 * immediately, and async/onMain run inline, so a whole build or reset finishes
 * within the call that started it. Repeating timers do <em>not</em> auto-fire —
 * the test drives them with {@link #fireTimers()}, so an editor poll loop can be
 * advanced one cycle at a time.
 */
final class TestScheduler implements Scheduler
{
    private final List<Timer> timers = new ArrayList<>();

    @Override
    public void eachTick(BooleanSupplier slice, Runnable onDone)
    {
        while (!slice.getAsBoolean())
        {
            // keep slicing until it reports done
        }

        onDone.run();
    }

    @Override
    public void async(Runnable work)
    {
        work.run();
    }

    @Override
    public void onMain(Runnable work)
    {
        work.run();
    }

    @Override
    public Cancellable everySeconds(long seconds, Runnable work)
    {
        Timer timer = new Timer(work);
        timers.add(timer);
        return timer;
    }

    /** Runs every live repeating timer once — as if its period had elapsed. */
    void fireTimers()
    {
        for (Timer timer : new ArrayList<>(timers))
        {
            if (!timer.cancelled)
            {
                timer.work.run();
            }
        }
    }

    /** Number of timers still registered and not cancelled. */
    int liveTimers()
    {
        return (int) timers.stream().filter(t -> !t.cancelled).count();
    }

    private static final class Timer implements Cancellable
    {
        private final Runnable work;
        private boolean cancelled;

        Timer(Runnable work)
        {
            this.work = work;
        }

        @Override
        public void cancel()
        {
            cancelled = true;
        }
    }
}
