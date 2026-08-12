package app.litemazica.core.maze;

import app.litemazica.core.platform.Scheduler;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Persists the placed-maze registry to {@code mazes.properties}, so scheduled
 * regeneration survives a restart. Wraps {@link MazeStore} (the pure
 * serialize/parse) with the threading policy: the registry is read on the main
 * thread, then the bytes are written off it, and a lock serializes concurrent
 * writes so two async saves can't interleave.
 */
final class MazeStorage
{
    private final File file;
    private final Scheduler scheduler;
    private final Logger logger;
    /** Serializes writes to the file so two async saves can't interleave. */
    private final Object writeLock = new Object();

    MazeStorage(File file, Scheduler scheduler, Logger logger)
    {
        this.file = file;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    String fileName()
    {
        return file.getName();
    }

    List<PlacedMaze> load()
    {
        return MazeStore.load(file, logger);
    }

    /**
     * Serializes the registry on the calling (main) thread — where it is safe to
     * read — then writes to disk asynchronously.
     */
    void save(Collection<PlacedMaze> mazes)
    {
        String data = MazeStore.serialize(mazes);
        scheduler.async(() -> writeLocked(data));
    }

    /** Synchronous save for shutdown, when async tasks can no longer be scheduled. */
    void saveNow(Collection<PlacedMaze> mazes)
    {
        writeLocked(MazeStore.serialize(mazes));
    }

    private void writeLocked(String data)
    {
        synchronized (writeLock)
        {
            try
            {
                MazeStore.write(file, data);
            }
            catch (IOException e)
            {
                logger.warning("Could not save " + file.getName() + ": " + e.getMessage());
            }
        }
    }
}
