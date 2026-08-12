package app.litemazica.core.platform;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-tick batching engine both mod loaders run on. The load-bearing
 * behaviours: a slice runs until it reports done then its completion fires once;
 * a slice that throws is dropped (never retried forever) and logged; timers count
 * down, repeat and cancel; and a throwing timer survives for its next run.
 */
class TickSchedulerTest
{
    /** Ticks in one whole second — {@link TickScheduler#everySeconds} scales by this. */
    private static final int TICKS_PER_SECOND = 20;

    @Test
    void aSliceRunsEachTickUntilDoneThenFiresOnDoneExactlyOnce()
    {
        TickScheduler scheduler = new TickScheduler(Runnable::run, quietLogger());
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();

        scheduler.eachTick(() -> calls.incrementAndGet() >= 3, done::incrementAndGet);

        scheduler.tick();
        scheduler.tick();
        assertEquals(0, done.get(), "not finished after two slices");

        scheduler.tick(); // third slice returns true
        assertEquals(1, done.get(), "onDone fired once on completion");

        scheduler.tick(); // nothing left to run
        assertEquals(3, calls.get(), "a finished slice isn't called again");
        assertEquals(1, done.get());
    }

    @Test
    void aThrowingSliceIsDroppedAndNotRetried()
    {
        AtomicInteger severe = new AtomicInteger();
        TickScheduler scheduler = new TickScheduler(Runnable::run, countingLogger(severe));
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();

        scheduler.eachTick(() ->
        {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }, done::incrementAndGet);

        scheduler.tick();
        scheduler.tick();

        assertEquals(1, calls.get(), "dropped after the first throw, never retried");
        assertEquals(0, done.get(), "a failed slice does not fire onDone");
        assertEquals(1, severe.get(), "the failure is logged");
    }

    @Test
    void aThrowingOnDoneDoesNotEscapeTheTick()
    {
        AtomicInteger severe = new AtomicInteger();
        TickScheduler scheduler = new TickScheduler(Runnable::run, countingLogger(severe));

        scheduler.eachTick(() -> true, () ->
        {
            throw new RuntimeException("boom in completion");
        });

        scheduler.tick(); // must not throw
        assertEquals(1, severe.get(), "the completion failure is logged, not propagated");
    }

    @Test
    void aTimerFiresOnItsPeriodRepeatsAndStopsWhenCancelled()
    {
        TickScheduler scheduler = new TickScheduler(Runnable::run, quietLogger());
        AtomicInteger fired = new AtomicInteger();

        Scheduler.Cancellable handle = scheduler.everySeconds(1, fired::incrementAndGet);

        tickTimes(scheduler, TICKS_PER_SECOND - 1);
        assertEquals(0, fired.get(), "not yet due");

        scheduler.tick();
        assertEquals(1, fired.get(), "fires on its period");

        tickTimes(scheduler, TICKS_PER_SECOND);
        assertEquals(2, fired.get(), "and repeats");

        handle.cancel();
        tickTimes(scheduler, TICKS_PER_SECOND * 2);
        assertEquals(2, fired.get(), "cancelled — never fires again");
    }

    @Test
    void aThrowingTimerIsKeptForItsNextRun()
    {
        AtomicInteger severe = new AtomicInteger();
        TickScheduler scheduler = new TickScheduler(Runnable::run, countingLogger(severe));
        AtomicInteger fired = new AtomicInteger();

        scheduler.everySeconds(1, () ->
        {
            fired.incrementAndGet();
            throw new RuntimeException("boom");
        });

        tickTimes(scheduler, TICKS_PER_SECOND);
        tickTimes(scheduler, TICKS_PER_SECOND);

        assertEquals(2, fired.get(), "a throwing timer keeps running — the next run may succeed");
        assertEquals(2, severe.get(), "each failure is logged");
    }

    @Test
    void asyncRunsWorkOffThreadAndOnMainRunsInline() throws Exception
    {
        TickScheduler scheduler = new TickScheduler(Runnable::run, quietLogger());
        CountDownLatch ran = new CountDownLatch(1);

        scheduler.async(ran::countDown);
        assertTrue(ran.await(2, TimeUnit.SECONDS), "async work ran on the background pool");

        AtomicInteger onMain = new AtomicInteger();
        scheduler.onMain(onMain::incrementAndGet);
        assertEquals(1, onMain.get(), "onMain used the supplied executor (inline here)");

        scheduler.shutdown();
    }

    @Test
    void aThrowingAsyncTaskIsCaughtNotPropagated() throws Exception
    {
        AtomicInteger severe = new AtomicInteger();
        TickScheduler scheduler = new TickScheduler(Runnable::run, countingLogger(severe));
        CountDownLatch ran = new CountDownLatch(1);

        scheduler.async(() ->
        {
            try
            {
                throw new RuntimeException("boom");
            }
            finally
            {
                ran.countDown();
            }
        });

        assertTrue(ran.await(2, TimeUnit.SECONDS));
        // Give the pool a moment to log after the finally block.
        for (int i = 0; i < 50 && severe.get() == 0; i++)
        {
            Thread.sleep(10);
        }
        assertEquals(1, severe.get(), "a background failure is logged, never thrown to the caller");

        scheduler.shutdown();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void tickTimes(TickScheduler scheduler, int n)
    {
        for (int i = 0; i < n; i++)
        {
            scheduler.tick();
        }
    }

    private static Logger quietLogger()
    {
        return countingLogger(new AtomicInteger());
    }

    /** A logger that counts SEVERE records and prints nothing. */
    private static Logger countingLogger(AtomicInteger severeCount)
    {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler()
        {
            @Override
            public void publish(LogRecord record)
            {
                if (record.getLevel() == Level.SEVERE)
                {
                    severeCount.incrementAndGet();
                }
            }

            @Override
            public void flush()
            {
            }

            @Override
            public void close()
            {
            }
        });
        return logger;
    }
}
