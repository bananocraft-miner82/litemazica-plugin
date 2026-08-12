package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;

import java.util.List;

/** Small schematic fixtures shared by the service tests. */
final class TestMazes
{
    private TestMazes()
    {
    }

    /** A 3×1×3 solid stone floor, entrance at the middle of the north edge. */
    static MazeSchematic stoneFloor()
    {
        int[] voxels = {1, 1, 1, 1, 1, 1, 1, 1, 1};
        return new MazeSchematic("test", 3465, 3, 1, 3, 0,
                1, 0, 0, voxels.length, 0,
                List.of("minecraft:air", "minecraft:stone"), pack(voxels, 2), List.of(),
                0, "minecraft:stone_bricks");
    }

    static long[] pack(int[] values, int bits)
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
}
