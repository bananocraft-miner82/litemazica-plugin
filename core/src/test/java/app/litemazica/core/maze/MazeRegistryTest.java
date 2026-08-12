package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Id minting matters on restart: mazes loaded from disk keep their ids, and a
 * newly minted one must not collide with them.
 */
class MazeRegistryTest
{
    private static PlacedMaze maze(String id)
    {
        return new PlacedMaze(id, "world", MazeSource.TYPE_API, "CODE", 0, 64, 0, 0f,
                new Region(0, 60, 0, 1, 61, 1), 0, false, 0L, 0L);
    }

    @Test
    void mintsSequentialIds()
    {
        MazeRegistry registry = new MazeRegistry();

        assertEquals("m1", registry.nextId());
        assertEquals("m2", registry.nextId());
        assertEquals("m3", registry.nextId());
    }

    @Test
    void adoptingAnIdKeepsTheCounterAhead()
    {
        // Simulates loading mazes.yml on startup.
        MazeRegistry registry = new MazeRegistry();
        registry.put(maze("m7"));

        assertEquals("m8", registry.nextId());
    }

    @Test
    void theCounterTracksTheHighestAdoptedId()
    {
        MazeRegistry registry = new MazeRegistry();
        registry.put(maze("m3"));
        registry.put(maze("m9"));
        registry.put(maze("m5"));

        assertEquals("m10", registry.nextId());
    }

    @Test
    void customIdsLeaveTheCounterAlone()
    {
        MazeRegistry registry = new MazeRegistry();
        registry.put(maze("arena"));
        registry.put(maze("m-not-a-number"));

        assertEquals("m1", registry.nextId());
    }

    @Test
    void storesAndRemovesByIdInInsertionOrder()
    {
        MazeRegistry registry = new MazeRegistry();
        assertTrue(registry.isEmpty());

        registry.put(maze("m1"));
        registry.put(maze("m2"));

        assertFalse(registry.isEmpty());
        assertEquals("m1", registry.get("m1").id());
        assertEquals(List.of("m1", "m2"), registry.all().stream().map(PlacedMaze::id).toList());

        assertEquals("m1", registry.remove("m1").id());
        assertNull(registry.get("m1"));
        assertNull(registry.remove("m1"));
        assertEquals(List.of("m2"), registry.all().stream().map(PlacedMaze::id).toList());
    }

    @Test
    void looksUpNamesIgnoringCase()
    {
        MazeRegistry registry = new MazeRegistry();
        registry.put(maze("Arena"));

        // Whatever case a player types, they get the maze — and its canonical id.
        assertEquals("Arena", registry.get("arena").id());
        assertEquals("Arena", registry.get("ARENA").id());
        assertEquals("Arena", registry.get("Arena").id());
        assertNull(registry.get("aren"));
        assertNull(registry.get(null));

        assertTrue(registry.exists("arena"));
        assertFalse(registry.exists("other"));
    }

    @Test
    void removesIgnoringCase()
    {
        MazeRegistry registry = new MazeRegistry();
        registry.put(maze("Arena"));

        assertEquals("Arena", registry.remove("ARENA").id());
        assertTrue(registry.isEmpty());
        assertNull(registry.remove("arena"));
    }

    @Test
    void puttingTheSameIdReplacesIt()
    {
        MazeRegistry registry = new MazeRegistry();
        registry.put(maze("m1"));
        PlacedMaze replacement = maze("m1");
        registry.put(replacement);

        assertEquals(1, registry.all().size());
        assertSame(replacement, registry.get("m1"));
    }
}
