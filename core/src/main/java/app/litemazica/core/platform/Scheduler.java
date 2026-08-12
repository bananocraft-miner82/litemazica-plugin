package app.litemazica.core.platform;

import java.util.function.BooleanSupplier;

/**
 * Server-thread scheduling. Core owns the batching policy — how many blocks per
 * slice, when to stop — while the platform owns what a "tick" actually is.
 */
public interface Scheduler
{
    /**
     * Runs {@code slice} on the main thread once per tick until it returns true
     * (meaning "finished"), then runs {@code onDone} on the main thread. This is
     * how a large paste is spread across ticks instead of stalling the server.
     */
    void eachTick(BooleanSupplier slice, Runnable onDone);

    /** Runs work off the main thread — network and disk only. */
    void async(Runnable work);

    /** Hops back onto the main thread. */
    void onMain(Runnable work);

    /** A repeating main-thread timer. */
    Cancellable everySeconds(long seconds, Runnable work);

    interface Cancellable
    {
        void cancel();
    }
}
