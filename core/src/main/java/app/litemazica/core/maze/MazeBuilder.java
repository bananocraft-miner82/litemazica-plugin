package app.litemazica.core.maze;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;
import app.litemazica.core.platform.Platform;
import app.litemazica.core.platform.WorldAccess;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Materialises a maze into the world and takes it away again — the {@code
 * generate}, {@code place} and {@code remove} flows. Fetching happens off the
 * main thread and placement on it; before a single block is written the build
 * checks volume, overlap with other mazes, and that nobody is standing in the
 * footprint. The terrain under a maze is snapshotted first so {@code remove}
 * restores it.
 *
 * <p>Split out of {@link MazeService} so the build lifecycle is one cohesive unit;
 * scheduled/manual regeneration lives in {@link MazeRebuilder}.
 */
final class MazeBuilder
{
    private final Platform platform;
    private final LitemazicaClient client;
    private final MazeRegistry registry;
    private final OperationLocks locks;
    private final MazeStorage storage;
    private final SnapshotPolicy snapshots;
    private final BlendPolicy blend;
    private final SchematicFolder schematics;

    MazeBuilder(Platform platform, LitemazicaClient client, MazeRegistry registry, OperationLocks locks,
                MazeStorage storage, SnapshotPolicy snapshots, BlendPolicy blend, SchematicFolder schematics)
    {
        this.platform = platform;
        this.client = client;
        this.registry = registry;
        this.locks = locks;
        this.storage = storage;
        this.snapshots = snapshots;
        this.blend = blend;
        this.schematics = schematics;
    }

    // ── generate / place ─────────────────────────────────────────────────────

    /**
     * @param exemptName player allowed to be standing in the region — the sender,
     *                   when the maze is anchored at their own feet. Null when
     *                   explicit coordinates were given, so nobody gets a pass.
     * @param name       chosen maze name, which becomes its id; null to auto-assign.
     */
    void generate(Audience audience, String code, String worldName,
                  int ax, int ay, int az, float yaw, String exemptName, String name)
    {
        WorldAccess world = platform.world(worldName);

        if (world == null)
        {
            audience.send(MessageStyle.ERROR, "World '" + worldName + "' is not loaded.");
            return;
        }

        MazeSource source = new ApiMazeSource(client, code);
        audience.send(MessageStyle.INFO, "Fetching maze from " + client.baseUrl() + " …");
        loadAsync(source, audience,
                maze -> buildAndRegister(audience, maze, source, world, ax, ay, az, yaw, exemptName, name));
    }

    /**
     * Builds a maze from a {@code .litematic} file in the schematics folder — the
     * offline counterpart to {@link #generate}. Same anchor/facing rules; the
     * file's own layout is fixed, so its scheduled reset always reproduces it.
     */
    void place(Audience audience, String fileName, String worldName,
               int ax, int ay, int az, float yaw, String exemptName, String name)
    {
        WorldAccess world = platform.world(worldName);

        if (world == null)
        {
            audience.send(MessageStyle.ERROR, "World '" + worldName + "' is not loaded.");
            return;
        }

        String problem = SchematicName.validate(fileName);

        if (problem != null)
        {
            audience.send(MessageStyle.ERROR, problem);
            return;
        }

        String resolved = SchematicName.normalize(fileName);

        if (!new File(schematics.dir(), resolved).isFile())
        {
            audience.send(MessageStyle.ERROR, "No schematic named '" + resolved
                    + "' in the schematics folder. Use /litemazica files to list them.");
            return;
        }

        MazeSource source = new FileMazeSource(schematics.dir(), resolved, platform.logger());
        audience.send(MessageStyle.INFO, "Reading " + resolved + " …");
        loadAsync(source, audience,
                maze -> buildAndRegister(audience, maze, source, world, ax, ay, az, yaw, exemptName, name));
    }

    private void buildAndRegister(Audience audience, MazeSchematic maze, MazeSource source, WorldAccess world,
                                  int ax, int ay, int az, float yaw, String exemptName, String name)
    {
        long maxVolume = platform.config().getLong("max-volume", 6_000_000L);

        if (maze.volume() > maxVolume)
        {
            audience.send(MessageStyle.ERROR, "That maze is " + maze.volume()
                    + " blocks — above the configured max-volume of " + maxVolume + ".");
            return;
        }

        // How much terrain to strip above an open-top maze — folded into the
        // planned region so the checks, snapshot and placement all agree on it.
        int clearAbove = blend.clearAboveFor(maze);

        // Refuse to clobber another placed maze: compute where this one would
        // land and reject if it overlaps an existing region in the same world.
        Region plannedRegion = PlacementGeometry.regionFor(maze, ax, ay, az, yaw, clearAbove);

        for (PlacedMaze other : registry.all())
        {
            if (other.worldName().equals(world.name()) && other.region().intersects(plannedRegion))
            {
                audience.send(MessageStyle.ERROR, "That would overlap maze " + other.id()
                        + ". Remove it first (/litemazica remove " + other.id() + ") or pick another spot.");
                return;
            }
        }

        // Never bury anyone: placement rewrites every block in the region, so
        // anyone standing in it would end up inside a wall.
        List<String> inside = platform.players().namesInside(world.name(), plannedRegion, exemptName);

        if (!inside.isEmpty())
        {
            audience.send(MessageStyle.ERROR, "Cannot build there — " + MazeText.describe(inside)
                    + " inside the maze region. Wait until they move away, or pick another spot.");
            return;
        }

        // Re-check the name here too: the fetch gave someone else time to claim it.
        if (name != null && registry.exists(name))
        {
            audience.send(MessageStyle.ERROR, "A maze called '" + name + "' already exists. Pick another name.");
            return;
        }

        String id = name != null ? name : registry.nextId();
        int interval = platform.config().getInt("default-regen-minutes", 0);
        // A file source can't vary its layout, so "fresh" never applies — pin it
        // to "same" regardless of the config default.
        boolean fresh = source.supportsFreshLayout() && platform.config().getBoolean("regen-fresh-layout", true);

        audience.send(MessageStyle.INFO, "Building \"" + maze.name() + "\" ("
                + maze.sizeX() + "×" + maze.sizeY() + "×" + maze.sizeZ() + ", "
                + maze.blockCount() + " blocks) as " + id + " …");

        long now = System.currentTimeMillis();
        // regionFor and place compute the same transform, so the region is known
        // before a single block is written.
        PlacedMaze placed = new PlacedMaze(id, world.name(), source.type(), source.reference(), ax, ay, az, yaw,
                plannedRegion, interval, fresh, now, now);

        // Reserve the maze before the multi-tick snapshot + placement starts, for
        // two reasons. Registering it makes the overlap and name checks above
        // catch a second generate aimed here during this build — until now the
        // entry appeared only after capture, so two racing builds both passed.
        // Marking it busy makes remove/regen/start defer, so nothing else rewrites
        // these blocks while they are still being laid down. Everything that can
        // reject has already run, so this reservation always leads to a build (or
        // is released by the stale sweep if the world unloads mid-build).
        registry.put(placed);
        locks.reserve(id, now);
        storage.save(registry.all());

        Runnable placeMaze = () ->
                MazePlacer.place(platform.scheduler(), world, maze, ax, ay, az, yaw, clearAbove, region ->
                {
                    placed.setRegion(region);
                    locks.release(id);
                    storage.save(registry.all());
                    audience.send(MessageStyle.SUCCESS, "Maze " + id + " built in " + world.name()
                            + " at (" + ax + "," + ay + "," + az + "). Regen: " + placed.regenSummary()
                            + ". Remove with /litemazica remove " + id + ".");
                });

        // Record the terrain first, so removing the maze later puts the world
        // back instead of leaving a box of air. The snapshot covers the maze body
        // plus the blend band above it (the cap check, though, is on the body, so
        // whether a maze snapshots doesn't hinge on how far its blend reaches up).
        if (snapshots.enabled() && plannedRegion.volume() <= snapshots.maxVolume())
        {
            audience.send(MessageStyle.INFO, "Saving the existing terrain …");
            Region snapshotRegion = PlacementGeometry.snapshotRegionFor(plannedRegion, clearAbove);
            TerrainSnapshot.capture(platform.scheduler(), world, snapshotRegion, snapshots.dir(id),
                    platform.logger(), placeMaze);
            return;
        }

        if (snapshots.enabled())
        {
            audience.send(MessageStyle.WARNING, "Region is " + plannedRegion.volume()
                    + " blocks — above snapshot-max-volume (" + snapshots.maxVolume()
                    + "). The original terrain will not be restorable.");
        }

        placeMaze.run();
    }

    // ── remove ───────────────────────────────────────────────────────────────

    void remove(Audience audience, String id, boolean confirmed)
    {
        PlacedMaze maze = registry.get(id);

        if (maze == null)
        {
            audience.send(MessageStyle.ERROR, "No placed maze with id '" + id + "'. Use /litemazica list.");
            return;
        }

        // Lookup is case-insensitive; from here on use the canonical spelling so
        // the snapshot filename matches the one written at placement.
        final String mazeId = maze.id();

        // Report a build/regen in progress before asking about players, so a maze
        // that is still being laid down says "busy", not "confirm past the player".
        if (locks.isBusy(mazeId))
        {
            audience.send(MessageStyle.WARNING, "Maze " + mazeId
                    + " is busy right now — try again in a moment.");
            return;
        }

        List<String> inside = playersInside(maze);

        if (!confirmed && !inside.isEmpty())
        {
            audience.send(MessageStyle.WARNING, MazeText.describe(inside) + " inside " + mazeId
                    + ". Run '/litemazica remove " + mazeId + " confirm' to remove it anyway.");
            return;
        }

        // Removing rewrites the whole region, so take the same in-flight marker a
        // regeneration would: it keeps the scheduler off this maze, keeps players
        // from being teleported into it, and stops two removes racing.
        if (!locks.tryAcquire(mazeId, System.currentTimeMillis()))
        {
            audience.send(MessageStyle.WARNING, "Maze " + mazeId
                    + " is busy right now — try again in a moment.");
            return;
        }

        // Deregister only once the world work is done. Dropping the entry first
        // meant a crash mid-restore left maze blocks standing with nothing in the
        // registry pointing at them — unfindable and unremovable.
        Runnable deregister = () ->
        {
            registry.remove(mazeId);
            storage.save(registry.all());
            locks.release(mazeId);
        };

        WorldAccess world = platform.world(maze.worldName());
        Runnable clearToAir = () ->
        {
            if (world == null)
            {
                deregister.run();
                audience.send(MessageStyle.SUCCESS, "Maze " + mazeId + " removed from the registry.");
                return;
            }

            audience.send(MessageStyle.INFO, "Clearing maze " + mazeId + " …");
            MazePlacer.clear(platform.scheduler(), world, maze.region(), () ->
            {
                deregister.run();
                audience.send(MessageStyle.SUCCESS, "Maze " + mazeId + " removed.");
            });
        };

        if (world == null || !snapshots.has(mazeId))
        {
            // Nothing recorded (snapshots off, too large, or placed before
            // snapshots existed) — the old behaviour is the best we can do.
            clearToAir.run();
            return;
        }

        audience.send(MessageStyle.INFO, "Restoring the original terrain under " + mazeId + " …");

        TerrainSnapshot.restore(platform.scheduler(), world, snapshots.dir(mazeId), platform.logger(), () ->
        {
            platform.scheduler().async(() -> TerrainSnapshot.delete(snapshots.dir(mazeId)));
            deregister.run();
            audience.send(MessageStyle.SUCCESS, "Maze " + mazeId + " removed and the terrain restored.");
        }, () ->
        {
            audience.send(MessageStyle.WARNING, "That snapshot could not be read — clearing to air instead.");
            clearToAir.run();
        });
    }

    // ── shared fetch ─────────────────────────────────────────────────────────

    private void loadAsync(MazeSource source, Audience audience, Consumer<MazeSchematic> onOk)
    {
        platform.scheduler().async(() ->
        {
            try
            {
                MazeSchematic maze = source.load(null);
                platform.scheduler().onMain(() -> onOk.accept(maze));
            }
            catch (Exception e)
            {
                platform.scheduler().onMain(() ->
                        audience.send(MessageStyle.ERROR, "Could not load that maze: " + MazeText.reason(e)));
            }
        });
    }

    private List<String> playersInside(PlacedMaze maze)
    {
        return platform.players().namesInside(maze.worldName(), maze.region(), null);
    }
}
