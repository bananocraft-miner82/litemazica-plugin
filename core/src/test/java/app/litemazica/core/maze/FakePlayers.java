package app.litemazica.core.maze;

import app.litemazica.core.platform.PlayerLookup;
import app.litemazica.core.platform.PlayerPose;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A configurable {@link PlayerLookup}: players stand at fixed positions, so
 * {@code namesInside} reflects who a rewrite would actually hit, and teleport /
 * pose results are set per test.
 */
final class FakePlayers implements PlayerLookup
{
    private final Map<String, int[]> positions = new LinkedHashMap<>(); // name → {x,y,z}
    private String world = "world";

    boolean teleportResult = true;
    String lastTeleportPlayer;
    PlayerPose pose;

    /** Places {@code name} at a block position in the tracked world. */
    FakePlayers standing(String name, int x, int y, int z)
    {
        positions.put(name, new int[]{x, y, z});
        return this;
    }

    FakePlayers world(String world)
    {
        this.world = world;
        return this;
    }

    @Override
    public List<String> namesInside(String worldName, Region region, String exemptName)
    {
        List<String> out = new ArrayList<>();

        for (Map.Entry<String, int[]> e : positions.entrySet())
        {
            if (e.getKey().equals(exemptName) || !world.equals(worldName))
            {
                continue;
            }

            int[] p = e.getValue();

            if (region.contains(p[0], p[1], p[2]))
            {
                out.add(e.getKey());
            }
        }

        return out;
    }

    @Override
    public boolean teleport(String playerName, String worldName,
                            double x, double y, double z, float yaw, float pitch)
    {
        lastTeleportPlayer = playerName;
        return teleportResult;
    }

    @Override
    public PlayerPose poseOf(String playerName)
    {
        return pose;
    }
}
