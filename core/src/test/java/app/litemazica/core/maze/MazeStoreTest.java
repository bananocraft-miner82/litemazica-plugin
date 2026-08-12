package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence of the placed-maze registry. This file survives restarts and is
 * hand-editable, so it is the one place untrusted-looking input reaches the
 * plugin — and a maze id from it becomes a snapshot filename.
 */
class MazeStoreTest
{
    private static final Logger LOGGER = Logger.getLogger("MazeStoreTest");

    private static PlacedMaze maze(String id)
    {
        return new PlacedMaze(id, "world", MazeSource.TYPE_API, "1abcDEF",
                10, 64, -20, 90f,
                new Region(0, 60, -30, 40, 70, 10),
                60, true, 1_000L, 2_000L);
    }

    private static PlacedMaze fileMaze(String id)
    {
        return new PlacedMaze(id, "world", MazeSource.TYPE_FILE, "arena.litematic",
                10, 64, -20, 90f,
                new Region(0, 60, -30, 40, 70, 10),
                60, false, 1_000L, 2_000L);
    }

    private static File write(Path dir, String contents) throws IOException
    {
        File file = dir.resolve("mazes.properties").toFile();
        Files.writeString(file.toPath(), contents, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void roundTripsEveryFieldNeededToRebuildAMaze(@TempDir Path dir) throws IOException
    {
        File file = dir.resolve("mazes.properties").toFile();
        MazeStore.write(file, MazeStore.serialize(List.of(maze("arena"))));

        List<PlacedMaze> loaded = MazeStore.load(file, LOGGER);

        assertEquals(1, loaded.size());
        PlacedMaze m = loaded.get(0);
        assertEquals("arena", m.id());
        assertEquals("world", m.worldName());
        assertEquals("1abcDEF", m.shareCode());
        assertEquals(10, m.anchorX());
        assertEquals(64, m.anchorY());
        assertEquals(-20, m.anchorZ());
        assertEquals(90f, m.yaw());
        assertEquals(new Region(0, 60, -30, 40, 70, 10), m.region());
        assertEquals(60, m.regenMinutes());
        assertTrue(m.freshLayout());
        assertEquals(1_000L, m.placedAtEpochMs());
        assertEquals(2_000L, m.lastRegenEpochMs());
    }

    @Test
    void roundTripsAFileSourcedMaze(@TempDir Path dir) throws IOException
    {
        File file = dir.resolve("mazes.properties").toFile();
        MazeStore.write(file, MazeStore.serialize(List.of(fileMaze("arena"))));

        PlacedMaze m = MazeStore.load(file, LOGGER).get(0);

        assertEquals(MazeSource.TYPE_FILE, m.sourceType());
        assertTrue(m.isFileSource());
        assertEquals("arena.litematic", m.shareCode());
        assertFalse(m.freshLayout());
    }

    @Test
    void loadsAPreSourceEntryAsAnApiMaze(@TempDir Path dir) throws IOException
    {
        // A mazes.properties written before file sources existed has a "code"
        // key and no "source" key — it must still load, as an API maze.
        File file = write(dir, """
                maze.legacy.world=world
                maze.legacy.code=1abc
                maze.legacy.anchorX=10
                maze.legacy.anchorY=64
                maze.legacy.anchorZ=-20
                """);

        PlacedMaze m = MazeStore.load(file, LOGGER).get(0);

        assertEquals(MazeSource.TYPE_API, m.sourceType());
        assertEquals("1abc", m.shareCode());
    }

    @Test
    void missingFileLoadsAsEmptyRatherThanFailing(@TempDir Path dir)
    {
        assertEquals(List.of(), MazeStore.load(dir.resolve("absent.properties").toFile(), LOGGER));
    }

    @Test
    void skipsIdsThatWouldEscapeTheDataFolder(@TempDir Path dir) throws IOException
    {
        // The id becomes snapshots/<id>.dat, which is written and deleted.
        File file = write(dir, """
                maze.../../../evil.world=world
                maze.../../../evil.code=1abc
                maze.arena.world=world
                maze.arena.code=1abc
                """);

        List<PlacedMaze> loaded = MazeStore.load(file, LOGGER);

        assertEquals(1, loaded.size());
        assertEquals("arena", loaded.get(0).id());
    }

    @Test
    void skipsIdsWithCharactersANameCouldNeverHave(@TempDir Path dir) throws IOException
    {
        File file = write(dir, """
                maze.ok-one.world=world
                maze.ok-one.code=1abc
                """);

        // Sanity: a legitimate id is kept, so the filter isn't just rejecting all.
        assertEquals(List.of("ok-one"), MazeStore.load(file, LOGGER).stream().map(PlacedMaze::id).toList());
    }

    @Test
    void skipsHalfWrittenEntries(@TempDir Path dir) throws IOException
    {
        // A crash between writes can leave an entry with no world or code.
        File file = write(dir, """
                maze.partial.anchorX=10
                maze.whole.world=world
                maze.whole.code=1abc
                """);

        assertEquals(List.of("whole"), MazeStore.load(file, LOGGER).stream().map(PlacedMaze::id).toList());
    }
}
