package app.litemazica.core.maze;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;
import app.litemazica.core.platform.Platform;
import app.litemazica.core.platform.PlayerPose;
import app.litemazica.core.platform.Scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the web-editor handshake: mint a session, send the player a link, poll
 * until they press "Apply to server", then build. A fresh session builds at the
 * player's feet (via {@link MazeBuilder}); an {@code edit} session rebuilds an
 * existing maze in place (via {@link MazeRebuilder#replace}). One session per
 * player — starting another replaces it.
 *
 * <p>Split out of {@link MazeService} because the session lifecycle — the poll
 * loop, its in-flight guard, and the replace-on-apply — is a self-contained
 * concern with its own state.
 */
final class EditorSessionController
{
    private final Platform platform;
    private final LitemazicaClient client;
    private final MazeRegistry registry;
    private final OperationLocks locks;
    private final MazeStorage storage;
    private final MazeBuilder builder;
    private final MazeRebuilder rebuilder;

    /**
     * Active editor poll loops, keyed by player name — one open browser session
     * each. Only touched on the main thread (commands and the poll timer), so a
     * plain map is safe. Starting a new session replaces any previous one.
     */
    private final Map<String, Scheduler.Cancellable> editorPolls = new HashMap<>();

    EditorSessionController(Platform platform, LitemazicaClient client, MazeRegistry registry, OperationLocks locks,
                            MazeStorage storage, MazeBuilder builder, MazeRebuilder rebuilder)
    {
        this.platform = platform;
        this.client = client;
        this.registry = registry;
        this.locks = locks;
        this.storage = storage;
        this.builder = builder;
        this.rebuilder = rebuilder;
    }

    /**
     * Opens a web-editor session: mints a token, sends the player the URL, and
     * polls until they click Apply — then builds the maze at wherever they're
     * standing at that moment. A player may only have one session open; starting
     * another replaces it.
     */
    void startEditor(Audience audience, String playerName)
    {
        if (playerName == null)
        {
            audience.send(MessageStyle.ERROR, "Only a player can open the maze editor.");
            return;
        }

        audience.send(MessageStyle.INFO, "Opening an editor session on " + client.baseUrl() + " …");

        platform.scheduler().async(() ->
        {
            try
            {
                LitemazicaClient.EditorSession session = client.newEditorSession();
                platform.scheduler().onMain(() -> beginEditorPolling(audience, playerName, session, null));
            }
            catch (Exception e)
            {
                platform.scheduler().onMain(() ->
                        audience.send(MessageStyle.ERROR, "Could not open an editor session: " + MazeText.reason(e)));
            }
        });
    }

    /**
     * Re-opens the web editor on an existing maze's design so it can be reworked
     * and rebuilt in place. Only API mazes carry a design code; a file maze has
     * nothing to re-open. When the player presses Apply the maze is rewritten at
     * its current anchor/facing — no new maze, no teleport.
     */
    void editMaze(Audience audience, String playerName, String id)
    {
        if (playerName == null)
        {
            audience.send(MessageStyle.ERROR, "Only a player can open the maze editor.");
            return;
        }

        PlacedMaze maze = registry.get(id);

        if (maze == null)
        {
            audience.send(MessageStyle.ERROR, "No placed maze with id '" + id + "'. Use /litemazica list.");
            return;
        }

        if (maze.isFileSource())
        {
            audience.send(MessageStyle.ERROR, "Maze " + maze.id() + " was built from a local .litematic, "
                    + "so there's no editor design to re-open. Edit the file and /litemazica place it again.");
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

        final String targetId = maze.id();
        final String seedCode = maze.shareCode();
        audience.send(MessageStyle.INFO, "Opening the editor for maze " + targetId + " on " + client.baseUrl() + " …");

        platform.scheduler().async(() ->
        {
            try
            {
                LitemazicaClient.EditorSession session = client.newEditorSession(seedCode);
                platform.scheduler().onMain(() -> beginEditorPolling(audience, playerName, session, targetId));
            }
            catch (Exception e)
            {
                platform.scheduler().onMain(() ->
                        audience.send(MessageStyle.ERROR, "Could not open an editor session: " + MazeText.reason(e)));
            }
        });
    }

    /**
     * Drives one editor session to completion. {@code editTargetId} is the maze
     * being re-edited in place, or null for a fresh maze built at the player's
     * feet.
     */
    private void beginEditorPolling(Audience audience, String playerName,
                                    LitemazicaClient.EditorSession session, String editTargetId)
    {
        stopEditor(playerName); // one session per player — replace any previous

        int minutes = Math.max(1, session.expiresInSeconds() / 60);
        boolean editing = editTargetId != null;
        audience.send(MessageStyle.INFO, editing
                ? "Rework maze " + editTargetId + " in the browser, then press \"Apply to server\":"
                : "Design your maze in the browser, then press \"Apply to server\":");
        audience.sendLink(MessageStyle.HEADING, "» Click here to open the maze editor «", session.url());
        audience.send(MessageStyle.INFO, "(or copy this link: " + session.url() + ")");
        audience.send(MessageStyle.INFO, (editing
                ? "I'll rebuild " + editTargetId + " in place once you apply."
                : "I'll build it where you're standing once you apply.")
                + " Session lasts " + minutes + " minutes.");

        long deadline = System.currentTimeMillis() + session.expiresInSeconds() * 1000L;
        int pollSeconds = Math.max(2, platform.config().getInt("editor-poll-seconds", 4));
        boolean[] inFlight = {false};
        Scheduler.Cancellable[] handle = new Scheduler.Cancellable[1];

        handle[0] = platform.scheduler().everySeconds(pollSeconds, () ->
        {
            if (inFlight[0])
            {
                return; // last poll's network call hasn't returned yet
            }

            if (System.currentTimeMillis() > deadline)
            {
                stopEditor(playerName);
                audience.send(MessageStyle.WARNING, "Your editor session expired. Run /litemazica editor again.");
                return;
            }

            inFlight[0] = true;
            platform.scheduler().async(() ->
            {
                LitemazicaClient.EditorPoll result;

                try
                {
                    result = client.pollEditor(session.token());
                }
                catch (Exception e)
                {
                    result = new LitemazicaClient.EditorPoll("pending", null); // transient — retry next tick
                }

                LitemazicaClient.EditorPoll r = result;
                platform.scheduler().onMain(() ->
                {
                    inFlight[0] = false;

                    // Session was replaced or stopped while this poll was in flight.
                    if (editorPolls.get(playerName) != handle[0])
                    {
                        return;
                    }

                    if ("ready".equals(r.status()))
                    {
                        stopEditor(playerName);
                        onEditorReady(audience, playerName, r.code(), editTargetId);
                    }
                    else if ("expired".equals(r.status()))
                    {
                        stopEditor(playerName);
                        audience.send(MessageStyle.WARNING, "That editor session is no longer valid.");
                    }
                    // "pending" — keep waiting.
                });
            });
        });

        editorPolls.put(playerName, handle[0]);
    }

    private void onEditorReady(Audience audience, String playerName, String code, String editTargetId)
    {
        // An edit session rebuilds an existing maze in place — the player's
        // position is irrelevant, so it doesn't matter if they've since moved or
        // logged off.
        if (editTargetId != null)
        {
            audience.send(MessageStyle.SUCCESS, "Edit received — rebuilding maze " + editTargetId + " in place.");
            applyEdit(audience, editTargetId, code);
            return;
        }

        PlayerPose pose = platform.players().poseOf(playerName);

        if (pose == null)
        {
            audience.send(MessageStyle.WARNING,
                    "Your maze is ready, but you're offline now — run /litemazica editor again to place it.");
            return;
        }

        audience.send(MessageStyle.SUCCESS, "Maze received — building it where you're standing.");
        // Same path as /litemazica generate at the player's feet; they're exempt
        // from the presence check because the entrance opens where they stand.
        builder.generate(audience, code, pose.worldName(), pose.x(), pose.y(), pose.z(), pose.yaw(), playerName, null);
    }

    /**
     * Rewrites an existing maze from a freshly-edited design code, in place at its
     * current anchor. The new code is committed only after it fetches cleanly, so
     * a network failure leaves the maze pointing at the design that's actually
     * standing. Reuses the regeneration machinery for the terrain dance.
     */
    private void applyEdit(Audience audience, String id, String newCode)
    {
        PlacedMaze maze = registry.get(id);

        if (maze == null)
        {
            audience.send(MessageStyle.ERROR, "Maze " + id + " is gone — it was removed while you were editing.");
            return;
        }

        final String mazeId = maze.id();

        if (platform.world(maze.worldName()) == null)
        {
            audience.send(MessageStyle.ERROR, "World '" + maze.worldName() + "' is not loaded.");
            return;
        }

        List<String> inside = playersInside(maze);

        if (!inside.isEmpty())
        {
            audience.send(MessageStyle.ERROR, "Cannot rebuild " + mazeId + " — " + MazeText.describe(inside)
                    + " inside the maze. They must leave the region first.");
            return;
        }

        // Take the same in-flight marker regen/remove use, so nothing else
        // rewrites these blocks while the edit is being laid down.
        if (!locks.tryAcquire(mazeId, System.currentTimeMillis()))
        {
            audience.send(MessageStyle.WARNING, "Maze " + mazeId
                    + " is busy right now — try again in a moment.");
            return;
        }

        MazeSource source = new ApiMazeSource(client, newCode);

        platform.scheduler().async(() ->
        {
            try
            {
                MazeSchematic fresh = source.load(null);
                platform.scheduler().onMain(() ->
                {
                    // Commit the new design only now that it has loaded, so future
                    // resets reproduce the edited layout.
                    maze.setShareCode(newCode);
                    storage.save(registry.all());
                    rebuilder.replace(maze, fresh, false, "updated", audience);
                });
            }
            catch (Exception e)
            {
                platform.scheduler().onMain(() ->
                {
                    platform.logger().warning("Edit of maze " + mazeId + " failed: " + MazeText.reason(e));
                    audience.send(MessageStyle.ERROR, "Could not rebuild that maze: " + MazeText.reason(e));
                    locks.release(mazeId);
                });
            }
        });
    }

    /** Stops and forgets a player's editor poll loop, if any. */
    void stopEditor(String playerName)
    {
        Scheduler.Cancellable handle = editorPolls.remove(playerName);

        if (handle != null)
        {
            handle.cancel();
        }
    }

    private List<String> playersInside(PlacedMaze maze)
    {
        return platform.players().namesInside(maze.worldName(), maze.region(), null);
    }
}
