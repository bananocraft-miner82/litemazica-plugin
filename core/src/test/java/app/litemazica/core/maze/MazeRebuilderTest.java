package app.litemazica.core.maze;

import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regeneration over the fake platform: the settings and on-demand guards, the
 * shared replace dance, and the scheduled loop's due / empty-region checks.
 */
class MazeRebuilderTest
{
    private static final int AX = 100;
    private static final int AY = 64;
    private static final int AZ = 100;
    private static final int FLOOR_Y = AY - 1;

    @Test
    void setRegenReportsAnUnknownId(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.rebuilder.setRegen(f.audience, "nope", 60, null);
        assertTrue(f.audience.sent(MessageStyle.ERROR, "No placed maze"));
    }

    @Test
    void setRegenStoresTheIntervalAndLayout(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();

        f.rebuilder.setRegen(f.audience, "m1", 60, true);

        PlacedMaze maze = f.registry.get("m1");
        assertEquals(60, maze.regenMinutes());
        assertTrue(maze.freshLayout());
        assertTrue(f.audience.sent(MessageStyle.SUCCESS));
    }

    @Test
    void setRegenRefusesFreshForAFileMaze(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.registry.put(new PlacedMaze("f1", "world", MazeSource.TYPE_FILE, "maze.litematic",
                AX, AY, AZ, 0f, new Region(AX, FLOOR_Y, AZ, AX, FLOOR_Y, AZ), 0, false, 0L, 0L));

        f.rebuilder.setRegen(f.audience, "f1", 30, true);

        PlacedMaze maze = f.registry.get("f1");
        assertEquals(30, maze.regenMinutes(), "the interval still applies");
        assertFalse(maze.freshLayout(), "but fresh is refused — a file maze resets to itself");
        assertTrue(f.audience.sent(MessageStyle.INFO, "same layout"));
    }

    @Test
    void regenerateNowReportsAnUnknownId(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.rebuilder.regenerateNow(f.audience, "nope", null);
        assertTrue(f.audience.sent(MessageStyle.ERROR, "No placed maze"));
    }

    @Test
    void regenerateNowReportsBusy(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        f.locks.tryAcquire("m1", System.currentTimeMillis());

        f.rebuilder.regenerateNow(f.audience, "m1", null);

        assertTrue(f.audience.sent(MessageStyle.WARNING, "busy"));
    }

    @Test
    void regenerateNowRefusesOverPlayersInside(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        f.platform.players.standing("Bob", AX, FLOOR_Y, AZ);

        f.rebuilder.regenerateNow(f.audience, "m1", null);

        assertTrue(f.audience.sent(MessageStyle.ERROR, "Bob"));
    }

    @Test
    void regenerateNowRebuildsTheMaze(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        // Simulate a player having dug through the floor.
        f.world.seed(AX, FLOOR_Y, AZ, "minecraft:air");

        f.rebuilder.regenerateNow(f.audience, "m1", false);

        assertEquals("minecraft:stone", f.world.blockAt(AX, FLOOR_Y, AZ), "the dug block is rebuilt");
        assertTrue(f.audience.sent(MessageStyle.SUCCESS, "regenerated"));
        assertFalse(f.locks.isBusy("m1"), "the lock is released");
    }

    @Test
    void theScheduledLoopRegeneratesADueMazeWithAnEmptyRegion(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        PlacedMaze maze = f.registry.get("m1");
        maze.setRegenMinutes(1);
        maze.setLastRegenEpochMs(0L); // long overdue
        f.world.seed(AX, FLOOR_Y, AZ, "minecraft:air"); // dug

        f.rebuilder.startScheduler();
        f.platform.scheduler.fireTimers(); // one scheduler check

        assertEquals("minecraft:stone", f.world.blockAt(AX, FLOOR_Y, AZ), "the due maze was rebuilt");
        assertTrue(maze.lastRegenEpochMs() > 0, "its regen clock was reset");
    }

    @Test
    void theScheduledLoopTellsOnlineAdminsWhenItRegenerates(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        PlacedMaze maze = f.registry.get("m1");
        maze.setRegenMinutes(1);
        maze.setLastRegenEpochMs(0L); // long overdue

        f.rebuilder.startScheduler();
        f.platform.scheduler.fireTimers();

        assertTrue(f.platform.admins.sent(MessageStyle.SUCCESS, "regenerated"),
                "a scheduled reset nobody asked for still announces itself to online admins");
    }

    @Test
    void theScheduledLoopStaggersMazesThatComeDueTogether(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        // Two mazes on the same schedule, both long overdue in the same check.
        f.buildMaze(); // m1 at the default anchor
        f.builder.generate(new FakeAudience(), "CODE", "world", AX + 50, AY, AZ + 50, 0f, null, null); // m2

        for (PlacedMaze maze : f.registry.all())
        {
            maze.setRegenMinutes(1);
            maze.setLastRegenEpochMs(0L);
        }

        f.rebuilder.startScheduler();

        // Default cap is 1, so the first check resets exactly one of the two;
        // the other is still due (its regen clock untouched) and waits its turn.
        f.platform.scheduler.fireTimers();
        assertEquals(1, regenerated(f), "only one maze reset on the first check — not both at once");

        // The next check picks up the one that was held back.
        f.platform.scheduler.fireTimers();
        assertEquals(2, regenerated(f), "the staggered maze reset on the following check");
    }

    /** How many placed mazes have completed at least one reset (clock advanced past 0). */
    private static int regenerated(Fixture f)
    {
        return (int) f.registry.all().stream().filter(m -> m.lastRegenEpochMs() > 0).count();
    }

    @Test
    void theScheduledLoopSkipsAMazeWithSomeoneInside(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        PlacedMaze maze = f.registry.get("m1");
        maze.setRegenMinutes(1);
        maze.setLastRegenEpochMs(0L);
        f.world.seed(AX, FLOOR_Y, AZ, "minecraft:air"); // dug
        f.platform.players.standing("Bob", AX, FLOOR_Y, AZ);

        f.rebuilder.startScheduler();
        f.platform.scheduler.fireTimers();

        assertEquals("minecraft:air", f.world.blockAt(AX, FLOOR_Y, AZ), "not rebuilt while occupied");
        assertEquals(0L, maze.lastRegenEpochMs(), "its regen clock is untouched — it retries next check");
    }

    @Test
    void replaceReleasesTheLockWhenTheWorldUnloaded(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        PlacedMaze maze = f.registry.get("m1");
        f.locks.tryAcquire("m1", System.currentTimeMillis()); // replace assumes the lock is held
        f.platform.unload("world");

        f.rebuilder.replace(maze, TestMazes.stoneFloor(), false, "regenerated", f.audience);

        assertTrue(f.audience.sent(MessageStyle.ERROR, "no longer loaded"));
        assertFalse(f.locks.isBusy("m1"), "the lock is released so the maze isn't stranded");
    }

    @Test
    void replaceCancelsWhenAPlayerEnteredDuringTheFetch(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        PlacedMaze maze = f.registry.get("m1");
        f.locks.tryAcquire("m1", System.currentTimeMillis());
        f.platform.players.standing("Bob", AX, FLOOR_Y, AZ);

        f.rebuilder.replace(maze, TestMazes.stoneFloor(), false, "regenerated", f.audience);

        assertTrue(f.audience.sent(MessageStyle.WARNING, "now inside"));
        assertFalse(f.locks.isBusy("m1"), "the lock is released");
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private static final class Fixture
    {
        final FakePlatform platform;
        final FakeClient client = new FakeClient();
        final FakeWorld world = new FakeWorld("world");
        final MazeRegistry registry = new MazeRegistry();
        final OperationLocks locks = new OperationLocks();
        final FakeAudience audience = new FakeAudience();
        final MazeBuilder builder;
        final MazeRebuilder rebuilder;

        Fixture(File data)
        {
            platform = new FakePlatform(data).withWorld(world);
            platform.config.set("snapshot", false);
            client.schematic = TestMazes.stoneFloor();
            MazeStorage storage = new MazeStorage(new File(data, "mazes.properties"),
                    platform.scheduler(), platform.logger());
            SnapshotPolicy snapshots = new SnapshotPolicy(platform.config(), data);
            BlendPolicy blend = new BlendPolicy(platform.config());
            SchematicFolder schematics = new SchematicFolder(platform);
            builder = new MazeBuilder(platform, client, registry, locks, storage, snapshots, blend, schematics);
            rebuilder = new MazeRebuilder(platform, client, registry, locks, storage, snapshots, blend, schematics);
        }

        void buildMaze()
        {
            builder.generate(new FakeAudience(), "CODE", "world", AX, AY, AZ, 0f, null, null);
        }
    }
}
