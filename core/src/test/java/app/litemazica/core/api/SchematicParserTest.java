package app.litemazica.core.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parsing raw {@code .litematic} NBT — the step shared by the API fetch and the
 * local-file source. A file source has no HTTP headers to lean on, so this is
 * the whole contract: geometry, palette, and packed states straight out of the
 * region.
 */
class SchematicParserTest
{
    private static final List<String> PALETTE = List.of("minecraft:air", "minecraft:stone_bricks");

    // 2x1x2, every voxel stone (index 1). Two palette entries → 2 bits/block.
    private static byte[] fixture(boolean gzip)
    {
        long[] states = LitematicFixture.pack(new int[]{1, 1, 1, 1}, 2);
        return gzip
                ? LitematicFixture.gzipped("Test Maze", 3465, 4, 2, 1, 2, PALETTE, states)
                : LitematicFixture.raw("Test Maze", 3465, 4, 2, 1, 2, PALETTE, states);
    }

    @Test
    void readsGeometryPaletteAndMetadataFromAGzippedFile() throws IOException
    {
        SchematicParser.ParsedRegion region = SchematicParser.parse(fixture(true));

        assertEquals("Test Maze", region.name());
        assertEquals(3465, region.dataVersion());
        assertEquals(4, region.totalBlocks());
        assertEquals(2, region.sizeX());
        assertEquals(1, region.sizeY());
        assertEquals(2, region.sizeZ());
        assertEquals(PALETTE, region.palette());
        assertEquals(1, region.regionCount());
    }

    @Test
    void alsoReadsAnUncompressedFileBySniffingTheGzipMagic() throws IOException
    {
        // An admin who decompressed a .litematic by hand still gets a clean parse.
        SchematicParser.ParsedRegion region = SchematicParser.parse(fixture(false));

        assertEquals(2, region.sizeX());
        assertEquals(PALETTE, region.palette());
    }

    @Test
    void rejectsBytesThatArentNbt()
    {
        assertThrows(IOException.class, () -> SchematicParser.parse(new byte[]{1, 2, 3, 4}));
    }
}
