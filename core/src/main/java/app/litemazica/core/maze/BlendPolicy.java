package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.ConfigSource;

/**
 * Decides how far above a maze to blend into the terrain (0 = off) — the policy
 * half of the blend, split out from {@link MazePlacer}'s mechanics so the
 * decision can be pinned without a world.
 *
 * <p>An editor-built maze carries its own reach: the editor only offers "blend
 * top" when no ceiling was selected, so it is trusted as-is. Otherwise the
 * {@code blend-top-reach} config applies — but only to a maze that is actually
 * open-topped, so a file maze with a roof is never touched.
 */
final class BlendPolicy
{
    private final ConfigSource config;

    BlendPolicy(ConfigSource config)
    {
        this.config = config;
    }

    /** How far above {@code maze} to blend, honouring its own reach then the config fallback. */
    int clearAboveFor(MazeSchematic maze)
    {
        if (maze.clearAbove() > 0)
        {
            return maze.clearAbove();
        }

        int def = config.getInt("blend-top-reach", 0);
        return def > 0 && isOpenTop(maze) ? def : 0;
    }

    /** True when the maze's top layer is all air/structure_void — i.e. it has no ceiling. */
    static boolean isOpenTop(MazeSchematic maze)
    {
        int topY = maze.sizeY() - 1;

        for (int z = 0; z < maze.sizeZ(); z++)
        {
            for (int x = 0; x < maze.sizeX(); x++)
            {
                String block = maze.blockAt(x, topY, z);

                if (!block.equals("minecraft:air") && !block.startsWith("minecraft:structure_void"))
                {
                    return false;
                }
            }
        }

        return true;
    }
}
