package app.litemazica.core.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link Scheduler} for platforms that have none of their own — which is both
 * mod loaders. Per-tick slices and repeating timers are drained by {@link #tick()},
 * which the mod calls from its end-of-server-tick event.
 *
 * <p>The only platform-specific part is how to get back onto the server thread,
 * supplied as an {@link Executor} (typically {@code server::execute}).
 */
public final class TickScheduler implements Scheduler
{
    private record Slice(BooleanSupplier work, Runnable onDone)
    {
    }

    private static final class Timer implements Cancellable
    {
        private final long periodTicks;
        private final Runnable work;
        private long countdown;
        private volatile boolean cancelled;

        Timer(long periodTicks, Runnable work)
        {
            this.periodTicks = periodTicks;
            this.work = work;
            this.countdown = periodTicks;
        }

        @Override
        public void cancel()
        {
            cancelled = true;
        }
    }

    private final Executor mainThread;
    private final Logger logger;
    private final List<Slice> slices = new CopyOnWriteArrayList<>();
    private final List<Timer> timers = new CopyOnWriteArrayList<>();

    private final ExecutorService async = Executors.newFixedThreadPool(2, runnable ->
    {
        Thread thread = new Thread(runnable, "Litemazica-async");
        thread.setDaemon(true);
        return thread;
    });

    public TickScheduler(Executor mainThread, Logger logger)
    {
        this.mainThread = mainThread;
        this.logger = logger;
    }

    /**
     * Runs everything due this tick. Must be called on the server thread.
     *
     * <p>Every unit of work is isolated: this runs from the server's tick event,
     * so an escaping exception would take the tick with it, and a slice that
     * throws would otherwise be retried — and throw again — every tick forever.
     * A failing slice is dropped and logged instead, and one failure never stops
     * the others from running.
     */
    public void tick()
    {
        if (!slices.isEmpty())
        {
            List<Slice> finished = new ArrayList<>();

            for (Slice slice : slices)
            {
                boolean done;

                try
                {
                    done = slice.work().getAsBoolean();
                }
                catch (Throwable t)
                {
                    // Drop it: whatever it was doing cannot be completed, and
                    // retrying next tick would just repeat the failure.
                    slices.remove(slice);
                    logger.log(Level.SEVERE, "Litemazica task failed and was cancelled", t);
                    continue;
                }

                if (done)
                {
                    finished.add(slice);
                }
            }

            for (Slice slice : finished)
            {
                slices.remove(slice);

                try
                {
                    slice.onDone().run();
                }
                catch (Throwable t)
                {
                    logger.log(Level.SEVERE, "Litemazica task completion handler failed", t);
                }
            }
        }

        for (Timer timer : timers)
        {
            if (timer.cancelled)
            {
                timers.remove(timer);
                continue;
            }

            if (--timer.countdown <= 0)
            {
                timer.countdown = timer.periodTicks;

                try
                {
                    timer.work.run();
                }
                catch (Throwable t)
                {
                    // Keep the timer: the next run may well succeed.
                    logger.log(Level.SEVERE, "Litemazica scheduled task failed", t);
                }
            }
        }
    }

    @Override
    public void eachTick(BooleanSupplier slice, Runnable onDone)
    {
        slices.add(new Slice(slice, onDone));
    }

    @Override
    public void async(Runnable work)
    {
        // Network and disk only — never touches the world. A small fixed pool
        // rather than a thread per call: several mazes coming due at once used
        // to spawn a thread each, and nothing here benefits from more than a
        // couple of workers (fetches are timeout-bounded, writes are small).
        async.execute(() ->
        {
            try
            {
                work.run();
            }
            catch (Throwable t)
            {
                logger.log(Level.SEVERE, "Litemazica background task failed", t);
            }
        });
    }

    /** Stops the background pool. Call when the server shuts down. */
    public void shutdown()
    {
        async.shutdownNow();
    }

    @Override
    public void onMain(Runnable work)
    {
        mainThread.execute(work);
    }

    @Override
    public Cancellable everySeconds(long seconds, Runnable work)
    {
        Timer timer = new Timer(Math.max(1L, seconds * 20L), work);
        timers.add(timer);
        return timer;
    }
}
