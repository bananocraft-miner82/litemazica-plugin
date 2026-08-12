package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;

import java.io.IOException;

/**
 * Where a maze's blocks come from. Two implementations back the two ways a maze
 * is created: {@link ApiMazeSource} fetches a share code from the Litemazica
 * API, {@link FileMazeSource} reads a {@code .litematic} an admin dropped on
 * disk. {@link MazeService} works only against this interface, so generation,
 * scheduled reset, snapshots and removal are identical for both.
 */
public interface MazeSource
{
    /** Persisted discriminator for an API-backed source. */
    String TYPE_API = "api";
    /** Persisted discriminator for a file-backed source. */
    String TYPE_FILE = "file";

    /**
     * Loads a placement-ready maze. Blocking (network or disk I/O) — always call
     * off the main server thread.
     *
     * @param seed a fresh layout seed, or null for the source's default layout.
     *             Sources that can't vary their layout ({@link #supportsFreshLayout()}
     *             is false) ignore it.
     */
    MazeSchematic load(String seed) throws IOException, InterruptedException;

    /**
     * Whether a fresh seed yields a new layout in the same footprint. True for
     * the API generator; false for a static file, which resets to itself.
     */
    boolean supportsFreshLayout();

    /** {@link #TYPE_API} or {@link #TYPE_FILE} — persisted so a reset can rebuild the source. */
    String type();

    /** The share code or file name this source resolves — persisted alongside the type. */
    String reference();
}
