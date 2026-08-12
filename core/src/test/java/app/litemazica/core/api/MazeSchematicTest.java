package app.litemazica.core.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Litematica bit-packing. Palette indices use a fixed bit width and freely
 * straddle two longs, so this is easy to get subtly wrong — and a wrong unpack
 * means the whole maze is built out of the wrong blocks.
 */
class MazeSchematicTest
{
    private static final int SIZE_X = 5;
    private static final int SIZE_Y = 3;
    private static final int SIZE_Z = 3;

    /** Five entries → 3 bits per block, so values straddle the 64-bit boundary. */
    private static final List<String> PALETTE = List.of(
            "minecraft:air",
            "minecraft:stone_bricks",
            "minecraft:mossy_stone_bricks",
            "minecraft:chest[facing=east]",
            "minecraft:torch");

    private static final int BITS = 3;

    private static int linear(int x, int y, int z)
    {
        return (y * SIZE_Z + z) * SIZE_X + x;
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

    private static MazeSchematic schematic(int[] values)
    {
        return new MazeSchematic("test", 3465, SIZE_X, SIZE_Y, SIZE_Z, 0,
                2, 0, 0, values.length, 0,
                PALETTE, pack(values, BITS), List.of(), 0, "minecraft:stone_bricks");
    }

    @Test
    void unpacksEveryVoxelIncludingThoseStraddlingTwoLongs()
    {
        int count = SIZE_X * SIZE_Y * SIZE_Z;
        int[] values = new int[count];

        for (int i = 0; i < count; i++)
        {
            values[i] = i % PALETTE.size();
        }

        MazeSchematic maze = schematic(values);

        for (int y = 0; y < SIZE_Y; y++)
        {
            for (int z = 0; z < SIZE_Z; z++)
            {
                for (int x = 0; x < SIZE_X; x++)
                {
                    int expected = values[linear(x, y, z)];
                    assertEquals(expected, maze.paletteIndex(x, y, z),
                            "palette index at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void blockAtResolvesThroughThePalette()
    {
        int count = SIZE_X * SIZE_Y * SIZE_Z;
        int[] values = new int[count];
        values[linear(2, 1, 1)] = 3;
        values[linear(0, 0, 0)] = 1;

        MazeSchematic maze = schematic(values);

        assertEquals("minecraft:chest[facing=east]", maze.blockAt(2, 1, 1));
        assertEquals("minecraft:stone_bricks", maze.blockAt(0, 0, 0));
        assertEquals("minecraft:air", maze.blockAt(4, 2, 2));
    }

    @Test
    void dataOrderIsXFastestThenZThenY()
    {
        int count = SIZE_X * SIZE_Y * SIZE_Z;
        int[] values = new int[count];
        // Three consecutive entries differ only along x.
        values[linear(0, 0, 0)] = 1;
        values[linear(1, 0, 0)] = 2;
        values[linear(2, 0, 0)] = 3;
        // The next row along z, then the next layer up.
        values[linear(0, 0, 1)] = 4;
        values[linear(0, 1, 0)] = 2;

        MazeSchematic maze = schematic(values);

        assertEquals(1, maze.paletteIndex(0, 0, 0));
        assertEquals(2, maze.paletteIndex(1, 0, 0));
        assertEquals(3, maze.paletteIndex(2, 0, 0));
        assertEquals(4, maze.paletteIndex(0, 0, 1));
        assertEquals(2, maze.paletteIndex(0, 1, 0));
    }

    @Test
    void reportsItsVolume()
    {
        assertEquals((long) SIZE_X * SIZE_Y * SIZE_Z, schematic(new int[SIZE_X * SIZE_Y * SIZE_Z]).volume());
    }

    // ── malformed responses ──────────────────────────────────────────────────
    // These all used to surface as an ArrayIndexOutOfBoundsException part-way
    // through the paste — inside a per-tick task, so it repeated every tick and
    // left the maze half-built. They must fail on construction instead.

    @Test
    void rejectsBlockStatesTooShortForTheDeclaredSize()
    {
        long[] truncated = new long[2]; // nowhere near 45 voxels × 3 bits

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new MazeSchematic("test", 3465, SIZE_X, SIZE_Y, SIZE_Z, 0,
                        2, 0, 0, 0, 0, PALETTE, truncated, List.of(), 0, "minecraft:stone_bricks"));

        assertTrue(e.getMessage().contains("BlockStates"), e.getMessage());
    }

    @Test
    void rejectsNonPositiveDimensions()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new MazeSchematic("test", 3465, SIZE_X, 0, SIZE_Z, 0,
                        0, 0, 0, 0, 0, PALETTE, new long[64], List.of(), 0, "minecraft:stone_bricks"));
    }

    @Test
    void rejectsAnEmptyPalette()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new MazeSchematic("test", 3465, SIZE_X, SIZE_Y, SIZE_Z, 0,
                        0, 0, 0, 0, 0, List.of(), new long[64], List.of(), 0, "minecraft:stone_bricks"));
    }

    @Test
    void treatsAnOutOfRangePaletteIndexAsAir()
    {
        // Three entries need two bits, which can encode 3 — one past the end.
        List<String> small = List.of("minecraft:air", "minecraft:stone", "minecraft:dirt");
        int count = SIZE_X * SIZE_Y * SIZE_Z;
        int[] values = new int[count];
        values[linear(1, 0, 0)] = 3;

        MazeSchematic maze = new MazeSchematic("test", 3465, SIZE_X, SIZE_Y, SIZE_Z, 0,
                0, 0, 0, 0, 0, small, pack(values, 2), List.of(), 0, "minecraft:stone_bricks");

        // Clamped rather than thrown: this runs inside the paste loop.
        assertEquals(0, maze.paletteIndex(1, 0, 0));
        assertEquals("minecraft:air", maze.blockAt(1, 0, 0));
    }
}
