package app.litemazica.core.maze;

import app.litemazica.core.platform.MessageStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build lifecycle end-to-end over the fake platform: the pre-build guards
 * (volume, overlap, players in the way, name clash), and that a placed maze is
 * registered, cleared on remove, and its terrain snapshotted and restored.
 */
class MazeBuilderTest
{
    private static final int AX = 100;
    private static final int AY = 64;
    private static final int AZ = 100;
    private static final int FLOOR_Y = AY - 1;

    @Test
    void generateBuildsRegistersAndPlacesTheMaze(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", false);

        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);

        assertEquals(1, f.registry.all().size(), "the maze is registered");
        assertNotNull(f.registry.get("m1"), "auto-assigned the first id");
        assertEquals("minecraft:stone", f.world.blockAt(AX, FLOOR_Y, AZ), "the body was written to the world");
        assertTrue(f.audience.sent(MessageStyle.SUCCESS));
        assertFalse(f.locks.isBusy("m1"), "the build lock is released when done");
    }

    @Test
    void generateRejectsAMazeOverTheVolumeCap(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("max-volume", 4); // the fixture maze is 9 blocks

        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);

        assertTrue(f.registry.all().isEmpty(), "nothing is built");
        assertTrue(f.audience.sent(MessageStyle.ERROR, "max-volume"));
    }

    @Test
    void generateRefusesToBuildOverANonExemptPlayer(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", false);
        f.platform.players.standing("Bob", AX, FLOOR_Y, AZ);

        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);

        assertTrue(f.registry.all().isEmpty());
        assertTrue(f.audience.sent(MessageStyle.ERROR, "Bob"), "names who is in the way");
    }

    @Test
    void generateExemptsThePlayerAnchoringAtTheirOwnFeet(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", false);
        f.platform.players.standing("Bob", AX, FLOOR_Y, AZ);

        // exemptName = Bob: the entrance opens where they stand.
        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, "Bob", null);

        assertEquals(1, f.registry.all().size(), "the anchoring player doesn't block their own build");
    }

    @Test
    void generateRejectsOverlapWithAnExistingMaze(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", false);
        f.registry.put(new PlacedMaze("other", "world", MazeSource.TYPE_API, "X",
                AX, AY, AZ, 0f, new Region(AX - 1, FLOOR_Y, AZ - 1, AX + 1, FLOOR_Y, AZ + 1),
                0, false, 0L, 0L));

        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);

        assertEquals(1, f.registry.all().size(), "only the pre-existing maze remains");
        assertTrue(f.audience.sent(MessageStyle.ERROR, "overlap"));
    }

    @Test
    void generateUsesAGivenNameAsTheId(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", false);

        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, "arena");

        assertNotNull(f.registry.get("arena"));
    }

    @Test
    void generateWarnsButStillBuildsAboveTheSnapshotCap(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", true).set("snapshot-max-volume", 1); // maze region > 1

        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);

        assertEquals(1, f.registry.all().size(), "still builds");
        assertTrue(f.audience.sent(MessageStyle.WARNING, "snapshot-max-volume"), "but warns it won't be restorable");
    }

    @Test
    void removeClearsToAirWhenThereIsNoSnapshot(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", false);
        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);
        assertEquals("minecraft:stone", f.world.blockAt(AX, FLOOR_Y, AZ));

        FakeAudience removeAudience = new FakeAudience();
        f.builder.remove(removeAudience, "m1", false);

        assertTrue(f.registry.all().isEmpty(), "deregistered");
        assertEquals("minecraft:air", f.world.blockAt(AX, FLOOR_Y, AZ), "the body is cleared to air");
        assertTrue(removeAudience.sent(MessageStyle.SUCCESS));
    }

    @Test
    void removeRefusesOverPlayersUntilConfirmed(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", false);
        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);
        f.platform.players.standing("Bob", AX, FLOOR_Y, AZ);

        FakeAudience unconfirmed = new FakeAudience();
        f.builder.remove(unconfirmed, "m1", false);
        assertEquals(1, f.registry.all().size(), "not removed without confirm");
        assertTrue(unconfirmed.sent(MessageStyle.WARNING, "confirm"));

        FakeAudience confirmed = new FakeAudience();
        f.builder.remove(confirmed, "m1", true);
        assertTrue(f.registry.all().isEmpty(), "confirm removes it anyway");
    }

    @Test
    void removeReportsBusyWhenTheMazeIsMidRewrite(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", false);
        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);
        f.locks.tryAcquire("m1", System.currentTimeMillis()); // pretend a rewrite is in flight

        FakeAudience removeAudience = new FakeAudience();
        f.builder.remove(removeAudience, "m1", false);

        assertEquals(1, f.registry.all().size(), "not removed while busy");
        assertTrue(removeAudience.sent(MessageStyle.WARNING, "busy"));
    }

    @Test
    void removeRestoresTheOriginalTerrainFromItsSnapshot(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.platform.config.set("snapshot", true).set("snapshot-max-volume", 1_000_000L);
        // Original terrain under the maze: dirt where the floor will land.
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                f.world.seed(AX + dx, FLOOR_Y, AZ + dz, "minecraft:dirt");
            }
        }

        f.builder.generate(f.audience, "CODE", "world", AX, AY, AZ, 0f, null, null);
        assertEquals("minecraft:stone", f.world.blockAt(AX, FLOOR_Y, AZ), "the maze overwrote the dirt");

        FakeAudience removeAudience = new FakeAudience();
        f.builder.remove(removeAudience, "m1", false);

        assertEquals("minecraft:dirt", f.world.blockAt(AX, FLOOR_Y, AZ), "remove put the original terrain back");
        assertTrue(removeAudience.sent(MessageStyle.SUCCESS, "terrain restored"));
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    /** Wires a builder over a fake platform with one loaded "world" and a canned maze. */
    private static final class Fixture
    {
        final FakePlatform platform;
        final FakeClient client = new FakeClient();
        final FakeWorld world = new FakeWorld("world");
        final MazeRegistry registry = new MazeRegistry();
        final OperationLocks locks = new OperationLocks();
        final FakeAudience audience = new FakeAudience();
        final MazeBuilder builder;

        Fixture(File data)
        {
            platform = new FakePlatform(data).withWorld(world);
            client.schematic = TestMazes.stoneFloor();
            MazeStorage storage = new MazeStorage(new File(data, "mazes.properties"),
                    platform.scheduler(), platform.logger());
            SnapshotPolicy snapshots = new SnapshotPolicy(platform.config(), data);
            BlendPolicy blend = new BlendPolicy(platform.config());
            SchematicFolder schematics = new SchematicFolder(platform);
            builder = new MazeBuilder(platform, client, registry, locks, storage, snapshots, blend, schematics);
        }
    }

}
