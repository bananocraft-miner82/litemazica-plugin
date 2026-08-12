package app.litemazica.core.maze;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;
import app.litemazica.core.platform.Platform;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The entry point platforms build their command layer on. Everything the plugin
 * and the mods do, minus the bits that must touch a server, lives here or in one
 * of the focused collaborators this coordinates:
 *
 * <ul>
 *   <li>{@link MazeBuilder} — {@code generate}, {@code place}, {@code remove}.</li>
 *   <li>{@link MazeRebuilder} — scheduled and on-demand regeneration, and the
 *   scheduled loop.</li>
 *   <li>{@link EditorSessionController} — the web-editor handshake.</li>
 *   <li>{@link MazeRegistry} / {@link MazeStorage} — the placed-maze set and its
 *   persistence.</li>
 *   <li>{@link OperationLocks} — the mid-rewrite lock table shared by all of them.</li>
 *   <li>{@link SnapshotPolicy} / {@link BlendPolicy} — config-driven decisions.</li>
 * </ul>
 *
 * <p>Only {@code start} (teleporting a player to an entrance) is handled here
 * directly, since it touches no terrain. Platforms supply world access, scheduling
 * and messaging through {@link Platform}, and provide their own command layer on
 * top of these methods.
 */
public final class MazeService
{
    private final Platform platform;
    private final LitemazicaClient client;
    private final MazeRegistry registry = new MazeRegistry();
    private final OperationLocks locks = new OperationLocks();
    private final MazeStorage storage;
    private final SchematicFolder schematics;
    private final MazeBuilder builder;
    private final MazeRebuilder rebuilder;
    private final EditorSessionController editor;

    public MazeService(Platform platform, LitemazicaClient client)
    {
        this.platform = platform;
        this.client = client;
        this.schematics = new SchematicFolder(platform);
        this.storage = new MazeStorage(new File(platform.dataFolder(), "mazes.properties"),
                platform.scheduler(), platform.logger());

        SnapshotPolicy snapshots = new SnapshotPolicy(platform.config(), platform.dataFolder());
        BlendPolicy blend = new BlendPolicy(platform.config());

        this.builder = new MazeBuilder(platform, client, registry, locks, storage, snapshots, blend, schematics);
        this.rebuilder = new MazeRebuilder(platform, client, registry, locks, storage, snapshots, blend, schematics);
        this.editor = new EditorSessionController(platform, client, registry, locks, storage, builder, rebuilder);
    }

    public MazeRegistry registry()
    {
        return registry;
    }

    public LitemazicaClient client()
    {
        return client;
    }

    public void load()
    {
        schematics.ensureExists();

        for (PlacedMaze maze : storage.load())
        {
            registry.put(maze);
        }

        int count = registry.all().size();

        if (count > 0)
        {
            platform.logger().info("Loaded " + count + " placed maze(s) from " + storage.fileName() + ".");
        }
    }

    /** Synchronous save for shutdown, when async tasks can no longer be scheduled. */
    public void saveNow()
    {
        storage.saveNow(registry.all());
    }

    /** The {@code .litematic} files available to {@link #place}, by name. */
    public List<String> listSchematics()
    {
        return schematics.list();
    }

    public void startScheduler()
    {
        rebuilder.startScheduler();
    }

    public void stopScheduler()
    {
        rebuilder.stopScheduler();
    }

    // ── build / remove (delegated to MazeBuilder) ────────────────────────────

    public void generate(Audience audience, String code, String worldName,
                         int ax, int ay, int az, float yaw, String exemptName, String name)
    {
        builder.generate(audience, code, worldName, ax, ay, az, yaw, exemptName, name);
    }

    public void place(Audience audience, String fileName, String worldName,
                      int ax, int ay, int az, float yaw, String exemptName, String name)
    {
        builder.place(audience, fileName, worldName, ax, ay, az, yaw, exemptName, name);
    }

    public void remove(Audience audience, String id, boolean confirmed)
    {
        builder.remove(audience, id, confirmed);
    }

    // ── regeneration (delegated to MazeRebuilder) ────────────────────────────

    public void setRegen(Audience audience, String id, int minutes, Boolean fresh)
    {
        rebuilder.setRegen(audience, id, minutes, fresh);
    }

    public void regenerateNow(Audience audience, String id, Boolean freshOverride)
    {
        rebuilder.regenerateNow(audience, id, freshOverride);
    }

    // ── editor (delegated to EditorSessionController) ─────────────────────────

    public void startEditor(Audience audience, String playerName)
    {
        editor.startEditor(audience, playerName);
    }

    public void editMaze(Audience audience, String playerName, String id)
    {
        editor.editMaze(audience, playerName, id);
    }

    public void stopEditor(String playerName)
    {
        editor.stopEditor(playerName);
    }

    // ── start (player-facing) ────────────────────────────────────────────────

    /**
     * Teleports a player to a maze entrance, facing in. The stored anchor is the
     * entrance opening and the stored yaw is the direction the maze runs, so
     * dropping the player there with that yaw puts them on the threshold looking
     * down the first corridor.
     *
     * @param playerName who to move; null when the sender isn't a player.
     * @param id         maze to enter, or null to pick the only placed maze.
     */
    public void start(Audience audience, String playerName, String id)
    {
        if (playerName == null)
        {
            audience.send(MessageStyle.ERROR, "Only a player can be teleported to a maze.");
            return;
        }

        PlacedMaze maze = resolveForStart(audience, id);

        if (maze == null)
        {
            return;
        }

        if (platform.world(maze.worldName()) == null)
        {
            audience.send(MessageStyle.ERROR, "World '" + maze.worldName() + "' is not loaded.");
            return;
        }

        if (locks.isBusy(maze.id()))
        {
            audience.send(MessageStyle.WARNING, "Maze " + maze.id()
                    + " is busy right now — try again in a moment.");
            return;
        }

        // Centre of the anchor block, feet on the entrance floor, pitch level.
        boolean moved = platform.players().teleport(playerName, maze.worldName(),
                maze.anchorX() + 0.5, maze.anchorY(), maze.anchorZ() + 0.5, maze.yaw(), 0f);

        if (!moved)
        {
            audience.send(MessageStyle.ERROR, "Could not teleport you there.");
            return;
        }

        audience.send(MessageStyle.SUCCESS, "Teleported to the entrance of maze " + maze.id() + ". Good luck!");
    }

    /** The requested maze, or the only one placed when no id was given. */
    private PlacedMaze resolveForStart(Audience audience, String id)
    {
        if (id != null)
        {
            PlacedMaze maze = registry.get(id);

            if (maze == null)
            {
                audience.send(MessageStyle.ERROR, "No placed maze with id '" + id + "'.");
            }

            return maze;
        }

        List<PlacedMaze> all = registry.all();

        if (all.isEmpty())
        {
            audience.send(MessageStyle.INFO, "No mazes are currently placed.");
            return null;
        }

        if (all.size() == 1)
        {
            return all.get(0);
        }

        List<String> ids = new ArrayList<>();

        for (PlacedMaze maze : all)
        {
            ids.add(maze.id());
        }

        audience.send(MessageStyle.WARNING, "Several mazes are placed — pick one: /litemazica start <"
                + String.join("|", ids) + ">");
        return null;
    }
}
