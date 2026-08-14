package app.litemazica.core.maze;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;
import app.litemazica.core.platform.Platform;
import app.litemazica.core.platform.Scheduler;
import app.litemazica.core.platform.WorldAccess;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Resets placed mazes — on a schedule, on demand, and (via {@link #replace}) when
 * an edited design is applied in place. A maze only resets when no player is inside
 * its region; the fetch runs off the main thread and the block work on it. The
 * "terrain dance" — restore or clear the old footprint, re-snapshot if a fresh
 * layout moved it, then place — is shared with the in-place editor.
 *
 * <p>Split out of {@link MazeService} so the regeneration lifecycle, including the
 * scheduled loop, is one cohesive unit.
 */
final class MazeRebuilder
{
    private final Platform platform;
    private final LitemazicaClient client;
    private final MazeRegistry registry;
    private final OperationLocks locks;
    private final MazeStorage storage;
    private final SnapshotPolicy snapshots;
    private final BlendPolicy blend;
    private final SchematicFolder schematics;

    private Scheduler.Cancellable schedulerTask;

    /**
     * Maze ids the scheduled loop currently has a reset in flight for. Bounds how
     * many scheduled resets run at once (see {@code regen-max-concurrent}) so a
     * batch of mazes sharing a schedule staggers across checks instead of all
     * fetching and pasting at the same instant. Touched only from {@link #tick}
     * on the main thread, so a plain set is safe.
     */
    private final Set<String> scheduledRegens = new HashSet<>();

    MazeRebuilder(Platform platform, LitemazicaClient client, MazeRegistry registry, OperationLocks locks,
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

    // ── scheduled loop ─────────────────────────────────────────────────────────

    void startScheduler()
    {
        int checkSeconds = Math.max(5, platform.config().getInt("regen-check-seconds", 30));
        schedulerTask = platform.scheduler().everySeconds(checkSeconds, this::tick);
    }

    void stopScheduler()
    {
        if (schedulerTask != null)
        {
            schedulerTask.cancel();
            schedulerTask = null;
        }
    }

    private void tick()
    {
        long now = System.currentTimeMillis();

        for (String stale : locks.sweepStale(now))
        {
            platform.logger().warning("Operation on maze " + stale
                    + " never completed — releasing it for the next check.");
        }

        // Forget resets that have since finished (the maze released its lock), so
        // the in-flight count reflects only work still running.
        scheduledRegens.removeIf(id -> !locks.isBusy(id));

        int maxConcurrent = Math.max(1, platform.config().getInt("regen-max-concurrent", 1));

        for (PlacedMaze maze : registry.all())
        {
            if (scheduledRegens.size() >= maxConcurrent)
            {
                // At the concurrency cap. The mazes left over are still due, so
                // the next check picks them up — a shared schedule staggers out
                // instead of stampeding the server all at once.
                break;
            }

            if (!maze.isRegenDue(now) || locks.isBusy(maze.id()))
            {
                continue;
            }

            if (!playersInside(maze).isEmpty())
            {
                // A reset requires the region to be empty — retry next check.
                continue;
            }

            // Nobody asked for this reset, but online admins should still hear
            // it happened — the console log alone reaches no one who's in-game.
            regenerate(maze, null, platform.admins());
            scheduledRegens.add(maze.id());
        }
    }

    // ── regen settings / on-demand ─────────────────────────────────────────────

    void setRegen(Audience audience, String id, int minutes, Boolean fresh)
    {
        PlacedMaze maze = registry.get(id);

        if (maze == null)
        {
            audience.send(MessageStyle.ERROR, "No placed maze with id '" + id + "'. Use /litemazica list.");
            return;
        }

        maze.setRegenMinutes(Math.max(0, minutes));

        // A file maze has no generator, so "fresh" can't apply — say so instead
        // of silently storing a setting that would never take effect.
        if (Boolean.TRUE.equals(fresh) && maze.isFileSource())
        {
            audience.send(MessageStyle.INFO, "Maze " + maze.id()
                    + " comes from a file, so it always resets to the same layout.");
            fresh = false;
        }

        if (fresh != null)
        {
            maze.setFreshLayout(fresh);
        }

        maze.setLastRegenEpochMs(System.currentTimeMillis());
        storage.save(registry.all());

        audience.send(MessageStyle.SUCCESS, "Maze " + maze.id() + " regen set to " + maze.regenSummary() + ".");
    }

    /**
     * Resets a maze straight away, on request rather than on schedule. Subject to
     * the same rule as a scheduled reset: nobody may be inside the region.
     *
     * @param freshOverride layout for this run only; null uses the maze's setting.
     */
    void regenerateNow(Audience audience, String id, Boolean freshOverride)
    {
        PlacedMaze maze = registry.get(id);

        if (maze == null)
        {
            audience.send(MessageStyle.ERROR, "No placed maze with id '" + id + "'. Use /litemazica list.");
            return;
        }

        String mazeId = maze.id();

        if (locks.isBusy(mazeId))
        {
            // Could be a scheduled regen, another manual one, a remove, or the
            // initial build — all hold the same marker, so keep the wording generic.
            audience.send(MessageStyle.WARNING, "Maze " + mazeId
                    + " is busy right now — try again in a moment.");
            return;
        }

        if (platform.world(maze.worldName()) == null)
        {
            audience.send(MessageStyle.ERROR, "World '" + maze.worldName() + "' is not loaded.");
            return;
        }

        List<String> inside = playersInside(maze);

        if (!inside.isEmpty())
        {
            audience.send(MessageStyle.ERROR, "Cannot regenerate " + mazeId + " — " + MazeText.describe(inside)
                    + " inside the maze. They must leave the region first.");
            return;
        }

        audience.send(MessageStyle.INFO, "Regenerating maze " + mazeId + " …");
        regenerate(maze, freshOverride, audience);
    }

    private void regenerate(PlacedMaze maze, Boolean freshOverride, Audience audience)
    {
        if (!locks.tryAcquire(maze.id(), System.currentTimeMillis()))
        {
            return;
        }

        if (platform.world(maze.worldName()) == null)
        {
            locks.release(maze.id());
            return;
        }

        MazeSource source = sourceFor(maze);
        // A file source reproduces the same layout, so a fresh seed is moot —
        // only ask the API for a new one.
        boolean useFresh = source.supportsFreshLayout()
                && (freshOverride != null ? freshOverride : maze.freshLayout());
        String seed = useFresh
                ? Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36)
                : null;

        platform.scheduler().async(() ->
        {
            try
            {
                MazeSchematic fresh = source.load(seed);
                platform.scheduler().onMain(() -> replace(maze, fresh, seed != null, "regenerated", audience));
            }
            catch (Exception e)
            {
                platform.scheduler().onMain(() ->
                {
                    platform.logger().warning("Regen of maze " + maze.id() + " failed: " + MazeText.reason(e));
                    audience.send(MessageStyle.ERROR, "Could not rebuild that maze: " + MazeText.reason(e));
                    // Back off a full interval rather than hammering a down API.
                    maze.setLastRegenEpochMs(System.currentTimeMillis());
                    storage.save(registry.all());
                    locks.release(maze.id());
                });
            }
        });
    }

    /**
     * Rewrites a placed maze from a freshly-loaded schematic at its existing
     * anchor: restore-or-clear the old footprint, re-snapshot if the footprint
     * moved, then place. Shared by scheduled/manual regeneration and by an
     * in-place edit; {@code action} ("regenerated"/"updated") only shapes the
     * wording of the completion message. Assumes the caller already holds the
     * maze's lock.
     */
    void replace(PlacedMaze maze, MazeSchematic fresh, boolean wasFresh, String action, Audience audience)
    {
        // Re-resolve rather than carrying a reference across the fetch: the world
        // may have been unloaded while we were waiting on the network.
        WorldAccess world = platform.world(maze.worldName());

        if (world == null)
        {
            platform.logger().warning("World '" + maze.worldName() + "' unloaded during the fetch — "
                    + "rebuild of maze " + maze.id() + " abandoned.");
            audience.send(MessageStyle.ERROR, "World '" + maze.worldName() + "' is no longer loaded.");
            locks.release(maze.id());
            return;
        }

        // Players may have entered during the fetch — re-check before clearing.
        List<String> inside = playersInside(maze);

        if (!inside.isEmpty())
        {
            audience.send(MessageStyle.WARNING, "Rebuild of " + maze.id() + " cancelled — "
                    + MazeText.describe(inside) + " now inside the maze.");
            locks.release(maze.id());
            return;
        }

        int clearAbove = blend.clearAboveFor(fresh);
        Region newRegion = PlacementGeometry.regionFor(fresh, maze.anchorX(), maze.anchorY(), maze.anchorZ(), maze.yaw(), clearAbove);
        Runnable placeIt = () -> MazePlacer.place(platform.scheduler(), world, fresh,
                maze.anchorX(), maze.anchorY(), maze.anchorZ(), maze.yaw(), clearAbove,
                region -> finishRegen(maze, region, wasFresh, action, audience));

        final File dir = snapshots.dir(maze.id());
        final Region newSnapshotRegion = PlacementGeometry.snapshotRegionFor(newRegion, clearAbove);

        if (!snapshots.has(maze.id()))
        {
            MazePlacer.clear(platform.scheduler(), world, maze.region(), placeIt);
            return;
        }

        Runnable clearAndPlace = () -> MazePlacer.clear(platform.scheduler(), world, maze.region(), placeIt);

        // Read just the manifest (off the main thread) to see whether the footprint moved.
        platform.scheduler().async(() ->
        {
            Region snapshotRegion;

            try
            {
                snapshotRegion = TerrainSnapshot.readRegion(dir);
            }
            catch (IOException e)
            {
                platform.logger().warning("Could not read snapshot for " + maze.id() + ": " + e.getMessage());
                platform.scheduler().onMain(clearAndPlace);
                return;
            }

            Region sr = snapshotRegion;
            platform.scheduler().onMain(() ->
            {
                if (sr.equals(newSnapshotRegion))
                {
                    // Same footprint: placement rewrites every voxel in it, so
                    // there's nothing to clear and the snapshot stays accurate.
                    placeIt.run();
                    return;
                }

                // A fresh layout moved the footprint. Put the original terrain
                // back, replace the snapshot with one of the new footprint —
                // pristine again — then build, so a later remove still cleans up.
                TerrainSnapshot.restore(platform.scheduler(), world, dir, platform.logger(),
                        () -> platform.scheduler().async(() ->
                        {
                            TerrainSnapshot.delete(dir);
                            platform.scheduler().onMain(() -> TerrainSnapshot.capture(
                                    platform.scheduler(), world, newSnapshotRegion, dir, platform.logger(), placeIt));
                        }),
                        clearAndPlace);
            });
        });
    }

    private void finishRegen(PlacedMaze maze, Region region, boolean wasFresh, String action, Audience audience)
    {
        maze.setRegion(region);
        maze.setLastRegenEpochMs(System.currentTimeMillis());
        storage.save(registry.all());
        locks.release(maze.id());
        String verbCap = Character.toUpperCase(action.charAt(0)) + action.substring(1);
        platform.logger().info(verbCap + " maze " + maze.id() + (wasFresh ? " (fresh layout)." : "."));
        audience.send(MessageStyle.SUCCESS, "Maze " + maze.id() + " " + action
                + (wasFresh ? " with a fresh layout." : "."));
    }

    /** Rebuilds the source a placed maze resets from — API by code, or a file by name. */
    private MazeSource sourceFor(PlacedMaze maze)
    {
        return maze.isFileSource()
                ? new FileMazeSource(schematics.dir(), maze.shareCode(), platform.logger())
                : new ApiMazeSource(client, maze.shareCode());
    }

    private List<String> playersInside(PlacedMaze maze)
    {
        return platform.players().namesInside(maze.worldName(), maze.region(), null);
    }
}
