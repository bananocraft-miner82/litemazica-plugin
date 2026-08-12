package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.platform.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blend decision: a maze's own reach wins, else the config fallback applies
 * but only to a genuinely open-topped maze.
 */
class BlendPolicyTest
{
    @Test
    void openTopIsTrueWhenTheTopLayerIsAllAir()
    {
        // Two layers: stone floor, air ceiling → the top layer is open.
        assertTrue(BlendPolicy.isOpenTop(maze(2, List.of("minecraft:air", "minecraft:stone"),
                new int[]{1, 0}, 0)));
    }

    @Test
    void openTopIsFalseWhenTheTopLayerHasSolidBlocks()
    {
        // Stone floor and stone ceiling → roofed.
        assertFalse(BlendPolicy.isOpenTop(maze(2, List.of("minecraft:air", "minecraft:stone"),
                new int[]{1, 1}, 0)));
    }

    @Test
    void aMazesOwnReachAlwaysWinsOverTheConfig()
    {
        BlendPolicy policy = new BlendPolicy(config(Map.of("blend-top-reach", 40)));
        // Roofed, but it carries its own reach (an editor maze is trusted as-is).
        MazeSchematic roofedWithReach = maze(2, List.of("minecraft:air", "minecraft:stone"),
                new int[]{1, 1}, 25);

        assertEquals(25, policy.clearAboveFor(roofedWithReach));
    }

    @Test
    void theConfigFallbackAppliesOnlyToAnOpenTopMaze()
    {
        BlendPolicy policy = new BlendPolicy(config(Map.of("blend-top-reach", 40)));

        MazeSchematic open = maze(2, List.of("minecraft:air", "minecraft:stone"), new int[]{1, 0}, 0);
        MazeSchematic roofed = maze(2, List.of("minecraft:air", "minecraft:stone"), new int[]{1, 1}, 0);

        assertEquals(40, policy.clearAboveFor(open), "open-top maze takes the config fallback");
        assertEquals(0, policy.clearAboveFor(roofed), "a roofed file maze is never blended");
    }

    @Test
    void aZeroConfigMeansNoBlendEvenForAnOpenTopMaze()
    {
        BlendPolicy policy = new BlendPolicy(config(Map.of()));
        MazeSchematic open = maze(2, List.of("minecraft:air", "minecraft:stone"), new int[]{1, 0}, 0);

        assertEquals(0, policy.clearAboveFor(open));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** A 1×sizeY×1 maze; {@code voxels} is the palette index per layer, bottom-up. */
    private static MazeSchematic maze(int sizeY, List<String> palette, int[] voxels, int clearAbove)
    {
        return new MazeSchematic("test", 3465, 1, sizeY, 1, 0,
                0, 0, 0, voxels.length, 0,
                palette, pack(voxels, 2), List.of(), clearAbove, "minecraft:stone_bricks");
    }

    private static long[] pack(int[] values, int bits)
    {
        long[] out = new long[(int) (((long) values.length * bits + 63) / 64) + 1];
        long mask = (1L << bits) - 1;

        for (int i = 0; i < values.length; i++)
        {
            long bitPos = (long) i * bits;
            int startLong = (int) (bitPos >> 6);
            int startBit = (int) (bitPos & 63);
            long v = values[i] & mask;
            out[startLong] |= v << startBit;

            if (startBit + bits > 64)
            {
                out[startLong + 1] |= v >>> (64 - startBit);
            }
        }

        return out;
    }

    private static ConfigSource config(Map<String, Integer> ints)
    {
        Map<String, Integer> values = new HashMap<>(ints);
        return new ConfigSource()
        {
            @Override
            public String getString(String key, String fallback)
            {
                return fallback;
            }

            @Override
            public int getInt(String key, int fallback)
            {
                return values.getOrDefault(key, fallback);
            }

            @Override
            public long getLong(String key, long fallback)
            {
                return fallback;
            }

            @Override
            public boolean getBoolean(String key, boolean fallback)
            {
                return fallback;
            }
        };
    }
}
