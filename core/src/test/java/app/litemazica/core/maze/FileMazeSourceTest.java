package app.litemazica.core.maze;

import app.litemazica.core.api.LitematicFixture;
import app.litemazica.core.api.MazeSchematic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Loading a maze from a {@code .litematic} on disk. A hand-made file carries no
 * placement metadata, so the source has to default the entrance and paste origin
 * from the geometry — the values a scheduled reset and the entrance teleport all
 * depend on.
 */
class FileMazeSourceTest
{
    private static final Logger LOGGER = Logger.getLogger("FileMazeSourceTest");
    private static final List<String> PALETTE = List.of("minecraft:air", "minecraft:stone_bricks");

    private static File write(File dir, String fileName, byte[] bytes) throws IOException
    {
        File file = new File(dir, fileName);
        Files.write(file.toPath(), bytes);
        return file;
    }

    /** A 5x1x3 all-stone schematic. */
    private static byte[] schematic(String name)
    {
        int[] voxels = new int[5 * 1 * 3];
        java.util.Arrays.fill(voxels, 1);
        return LitematicFixture.gzipped(name, 3465, voxels.length, 5, 1, 3, PALETTE,
                LitematicFixture.pack(voxels, 2));
    }

    @Test
    void defaultsTheEntranceToTheMiddleOfTheNorthEdgeAtFloorLevel(@TempDir File dir) throws Exception
    {
        write(dir, "arena.litematic", schematic("Arena"));

        MazeSchematic maze = new FileMazeSource(dir, "arena.litematic", LOGGER).load(null);

        assertEquals(5, maze.sizeX());
        assertEquals(1, maze.sizeY());
        assertEquals(3, maze.sizeZ());
        // Middle of the z=0 edge, floor level.
        assertEquals(2, maze.entranceX());
        assertEquals(0, maze.entranceY());
        assertEquals(0, maze.entranceZ());
        assertEquals(0, maze.originY());
        assertEquals("Arena", maze.name());
    }

    @Test
    void isNeverAFreshLayoutSource()
    {
        assertFalse(new FileMazeSource(new File("."), "x.litematic", LOGGER).supportsFreshLayout());
    }

    @Test
    void reportsTypeAndReferenceForPersistence()
    {
        MazeSource source = new FileMazeSource(new File("."), "arena.litematic", LOGGER);
        assertEquals(MazeSource.TYPE_FILE, source.type());
        assertEquals("arena.litematic", source.reference());
    }

    @Test
    void failsClearlyWhenTheFileIsMissing(@TempDir File dir)
    {
        assertThrows(IOException.class, () -> new FileMazeSource(dir, "absent.litematic", LOGGER).load(null));
    }
}
