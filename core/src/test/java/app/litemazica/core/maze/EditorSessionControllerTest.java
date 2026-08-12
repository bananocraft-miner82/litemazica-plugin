package app.litemazica.core.maze;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.platform.MessageStyle;
import app.litemazica.core.platform.PlayerPose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web-editor session: the up-front guards, and the poll loop driven to a
 * pressed "Apply" — building a fresh maze at the player's feet, or rewriting an
 * existing one in place.
 */
class EditorSessionControllerTest
{
    private static final int AX = 100;
    private static final int AY = 64;
    private static final int AZ = 100;
    private static final int FLOOR_Y = AY - 1;

    @Test
    void startEditorRequiresAPlayer(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.editor.startEditor(f.audience, null);
        assertTrue(f.audience.sent(MessageStyle.ERROR, "Only a player"));
    }

    @Test
    void editMazeRejectsAnUnknownId(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.editor.editMaze(f.audience, "Alice", "nope");
        assertTrue(f.audience.sent(MessageStyle.ERROR, "No placed maze"));
    }

    @Test
    void editMazeRejectsAFileMaze(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.registry.put(new PlacedMaze("f1", "world", MazeSource.TYPE_FILE, "maze.litematic",
                AX, AY, AZ, 0f, new Region(AX, FLOOR_Y, AZ, AX, FLOOR_Y, AZ), 0, false, 0L, 0L));

        f.editor.editMaze(f.audience, "Alice", "f1");

        assertTrue(f.audience.sent(MessageStyle.ERROR, "local .litematic"), "a file maze has no editor design");
    }

    @Test
    void editMazeReportsBusy(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        f.locks.tryAcquire("m1", System.currentTimeMillis());

        f.editor.editMaze(f.audience, "Alice", "m1");

        assertTrue(f.audience.sent(MessageStyle.WARNING, "busy"));
    }

    @Test
    void applyingAFreshSessionBuildsAtThePlayersFeet(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.client.poll = new LitemazicaClient.EditorPoll("ready", "NEWCODE");
        f.platform.players.pose = new PlayerPose("world", AX, AY, AZ, 0f);

        f.editor.startEditor(f.audience, "Alice");
        f.platform.scheduler.fireTimers(); // the poll comes back "ready"

        assertEquals(1, f.registry.all().size(), "a maze was built on apply");
        assertEquals("minecraft:stone", f.world.blockAt(AX, FLOOR_Y, AZ), "built where the player stands");
        assertEquals(0, f.platform.scheduler.liveTimers(), "the poll loop stops once applied");
    }

    @Test
    void applyingWhileOfflineBuildsNothingAndSaysSo(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.client.poll = new LitemazicaClient.EditorPoll("ready", "NEWCODE");
        f.platform.players.pose = null; // logged off before pressing apply

        f.editor.startEditor(f.audience, "Alice");
        f.platform.scheduler.fireTimers();

        assertTrue(f.registry.all().isEmpty(), "nothing built");
        assertTrue(f.audience.sent(MessageStyle.WARNING, "offline"));
    }

    @Test
    void applyingAnEditRewritesTheMazeInPlace(@TempDir File data)
    {
        Fixture f = new Fixture(data);
        f.buildMaze();
        f.world.seed(AX, FLOOR_Y, AZ, "minecraft:air"); // dug through since it was built
        f.client.poll = new LitemazicaClient.EditorPoll("ready", "EDITEDCODE");

        f.editor.editMaze(f.audience, "Alice", "m1");
        f.platform.scheduler.fireTimers();

        assertEquals("minecraft:stone", f.world.blockAt(AX, FLOOR_Y, AZ), "rebuilt in place at its anchor");
        assertEquals("EDITEDCODE", f.registry.get("m1").shareCode(), "the new design is committed for future resets");
        assertTrue(f.audience.sent(MessageStyle.SUCCESS, "updated"));
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
        final EditorSessionController editor;

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
            MazeRebuilder rebuilder = new MazeRebuilder(platform, client, registry, locks, storage, snapshots, blend, schematics);
            editor = new EditorSessionController(platform, client, registry, locks, storage, builder, rebuilder);
        }

        void buildMaze()
        {
            builder.generate(new FakeAudience(), "CODE", "world", AX, AY, AZ, 0f, null, null);
        }
    }
}
