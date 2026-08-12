package app.litemazica.core.api;

import java.util.List;
import java.util.Map;

/**
 * A maze fetched from the Litemazica API and parsed into a placement-ready
 * form. The palette is already rendered as Bukkit block-data strings (e.g.
 * {@code "minecraft:stone_bricks"} or {@code "minecraft:chest[facing=east,...]"}),
 * so placement is just: for each voxel, look up its palette string and apply it.
 *
 * <p>Coordinates are region-local: x in [0,sizeX), etc. The data order matches
 * Litematica — x fastest, then z, then y.
 */
public final class MazeSchematic
{
    private final String name;
    private final int dataVersion;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    /** Paste-origin Y offset (negated basement depth): floor lands at origin. */
    private final int originY;
    /**
     * Blocks of terrain to clear directly above the maze, for open-top mazes
     * whose surroundings would otherwise leave tree canopy and overhangs poking
     * into the play area. 0 means "clear nothing above" — the historical
     * behaviour. Supplied by the editor (which only offers it when no ceiling is
     * selected); a raw file leaves it 0.
     */
    private final int clearAbove;
    /**
     * Block used to cap open-top maze sections that end up buried under solid
     * terrain during a "blend into terrain" placement — the maze's own ceiling
     * material, so a cap reads as a proper roof. Defaults to stone bricks.
     */
    private final String ceilingBlock;
    private final int entranceX;
    private final int entranceY;
    private final int entranceZ;
    private final int blockCount;
    private final int commandBlocks;

    /** Block-data strings; index 0 is always {@code minecraft:air}. */
    private final List<String> palette;
    /** Bit-packed palette indices (Litematica scheme). */
    private final long[] blockStates;
    private final int bitsPerBlock;
    /** Block entities; each map has int x/y/z, String id, and any extra NBT. */
    private final List<Map<String, Object>> tileEntities;

    /** Upper bound on {@link #clearAbove}, so a bad header can't inflate the region absurdly. */
    private static final int MAX_CLEAR_ABOVE = 512;

    public MazeSchematic(
            String name, int dataVersion,
            int sizeX, int sizeY, int sizeZ, int originY,
            int entranceX, int entranceY, int entranceZ,
            int blockCount, int commandBlocks,
            List<String> palette, long[] blockStates, List<Map<String, Object>> tileEntities,
            int clearAbove, String ceilingBlock)
    {
        this.name = name;
        this.dataVersion = dataVersion;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.originY = originY;
        this.clearAbove = Math.max(0, Math.min(clearAbove, MAX_CLEAR_ABOVE));
        this.ceilingBlock = ceilingBlock == null || ceilingBlock.isBlank()
                ? "minecraft:stone_bricks"
                : ceilingBlock;
        this.entranceX = entranceX;
        this.entranceY = entranceY;
        this.entranceZ = entranceZ;
        this.blockCount = blockCount;
        this.commandBlocks = commandBlocks;
        this.palette = List.copyOf(palette);
        this.blockStates = blockStates;
        this.bitsPerBlock = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
        this.tileEntities = tileEntities;

        validate();
    }

    /**
     * Rejects a malformed response up front, on the fetching thread, rather than
     * letting it fail block-by-block inside the per-tick paste — where the same
     * exception would repeat every tick and strand the placement half-finished.
     */
    private void validate()
    {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0)
        {
            throw new IllegalArgumentException("maze has a non-positive dimension: "
                    + sizeX + "×" + sizeY + "×" + sizeZ);
        }

        if (palette.isEmpty())
        {
            throw new IllegalArgumentException("maze has an empty block palette");
        }

        long volume = volume();

        if (volume > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("maze volume " + volume + " is too large to index");
        }

        if (blockStates == null)
        {
            throw new IllegalArgumentException("maze has no BlockStates");
        }

        // Litematica packs volume × bitsPerBlock bits, so anything shorter is a
        // truncated or mismatched response.
        long requiredLongs = (volume * bitsPerBlock + 63) / 64;

        if (blockStates.length < requiredLongs)
        {
            throw new IllegalArgumentException("BlockStates holds " + blockStates.length
                    + " longs but " + requiredLongs + " are needed for a "
                    + sizeX + "×" + sizeY + "×" + sizeZ + " maze");
        }
    }

    public String name()
    {
        return name;
    }

    public int dataVersion()
    {
        return dataVersion;
    }

    public int sizeX()
    {
        return sizeX;
    }

    public int sizeY()
    {
        return sizeY;
    }

    public int sizeZ()
    {
        return sizeZ;
    }

    public int originY()
    {
        return originY;
    }

    /** Blocks of terrain to clear above the maze (0 = none). See the field doc. */
    public int clearAbove()
    {
        return clearAbove;
    }

    /** Block-state string used to cap buried sections when blending into terrain. */
    public String ceilingBlock()
    {
        return ceilingBlock;
    }

    public int entranceX()
    {
        return entranceX;
    }

    public int entranceY()
    {
        return entranceY;
    }

    public int entranceZ()
    {
        return entranceZ;
    }

    public int blockCount()
    {
        return blockCount;
    }

    public int commandBlocks()
    {
        return commandBlocks;
    }

    public long volume()
    {
        return (long) sizeX * sizeY * sizeZ;
    }

    public List<String> palette()
    {
        return palette;
    }

    public List<Map<String, Object>> tileEntities()
    {
        return tileEntities;
    }

    /** The block-data string at a region-local coordinate (index 0 = air). */
    public String blockAt(int x, int y, int z)
    {
        return palette.get(paletteIndex(x, y, z));
    }

    /** The palette index at a region-local coordinate. */
    public int paletteIndex(int x, int y, int z)
    {
        long linear = ((long) y * sizeZ + z) * sizeX + x;
        int index = unpack(linear);

        // bitsPerBlock can encode values past the end of the palette — three
        // entries need two bits, which spans four values. Constructor validation
        // catches a truncated array; this catches a bad index without throwing
        // inside the paste loop. Index 0 is always air.
        return index >= 0 && index < palette.size() ? index : 0;
    }

    /** Litematica bit-packing: fixed bits/block, values may straddle two longs. */
    private int unpack(long index)
    {
        long bitPos = index * bitsPerBlock;
        int startLong = (int) (bitPos >> 6);
        int startBit = (int) (bitPos & 63);
        int endLong = (int) (((index + 1) * bitsPerBlock - 1) >> 6);
        long mask = (1L << bitsPerBlock) - 1;

        if (startLong == endLong)
        {
            return (int) ((blockStates[startLong] >>> startBit) & mask);
        }

        return (int) (((blockStates[startLong] >>> startBit)
                | (blockStates[endLong] << (64 - startBit))) & mask);
    }
}
