package app.litemazica.core.maze;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the mazes currently placed in the world. In-memory for now; Phase 4
 * adds on-disk persistence so scheduled regeneration survives a restart. All
 * methods are synchronized because ids may be minted from an async task.
 */
public final class MazeRegistry
{
    private final Map<String, PlacedMaze> byId = new LinkedHashMap<>();
    private int counter = 0;

    public synchronized String nextId()
    {
        return "m" + (++counter);
    }

    public synchronized void put(PlacedMaze maze)
    {
        byId.put(maze.id(), maze);
        // Keep the auto-id counter ahead of any "m<n>" id we adopt (e.g. loaded
        // from disk) so nextId() never collides with an existing maze.
        String id = maze.id();

        if (id.length() > 1 && id.charAt(0) == 'm')
        {
            try
            {
                counter = Math.max(counter, Integer.parseInt(id.substring(1)));
            }
            catch (NumberFormatException ignored)
            {
                // custom id, not of the m<n> form — leave the counter alone
            }
        }
    }

    /**
     * Looks up by id, ignoring case — mazes are named by people and typed by
     * other people. Callers should use the returned maze's {@link PlacedMaze#id()}
     * from then on, not the string they searched with, so filenames and messages
     * use the canonical spelling.
     */
    public synchronized PlacedMaze get(String id)
    {
        String key = resolveKey(id);
        return key == null ? null : byId.get(key);
    }

    public synchronized PlacedMaze remove(String id)
    {
        String key = resolveKey(id);
        return key == null ? null : byId.remove(key);
    }

    /** True if this name is taken, ignoring case. */
    public synchronized boolean exists(String id)
    {
        return resolveKey(id) != null;
    }

    private String resolveKey(String id)
    {
        if (id == null)
        {
            return null;
        }

        if (byId.containsKey(id))
        {
            return id;
        }

        for (String key : byId.keySet())
        {
            if (key.equalsIgnoreCase(id))
            {
                return key;
            }
        }

        return null;
    }

    public synchronized List<PlacedMaze> all()
    {
        return new ArrayList<>(byId.values());
    }

    public synchronized boolean isEmpty()
    {
        return byId.isEmpty();
    }
}
