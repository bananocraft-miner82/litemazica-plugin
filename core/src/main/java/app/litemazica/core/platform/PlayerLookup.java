package app.litemazica.core.platform;

import app.litemazica.core.maze.Region;

import java.util.List;

/** Who is online and where they are — the basis of every "don't bury anyone" guard. */
public interface PlayerLookup
{
    /**
     * Names of players standing inside {@code region} of {@code worldName},
     * ignoring {@code exemptName} (may be null).
     */
    /**
     * Names of players standing inside {@code region} of {@code worldName} whom a
     * block rewrite would actually affect, ignoring {@code exemptName} (may be
     * null). Spectators are always excluded: they pass through blocks and take no
     * damage, so building or resetting around one is harmless.
     */
    List<String> namesInside(String worldName, Region region, String exemptName);

    /**
     * @return false if that player isn't online, or the world isn't loaded.
     */
    boolean teleport(String playerName, String worldName,
                     double x, double y, double z, float yaw, float pitch);

    /**
     * The player's current world, block position and facing, or null if they are
     * offline. Read at apply-time so an editor maze lands where the player is
     * standing then — not where they were when they ran the command.
     */
    PlayerPose poseOf(String playerName);
}
