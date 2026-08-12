package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Rules for a schematic file name. The name is typed in a command and turned
 * into a path under the data folder, so it has to be impossible to make it point
 * outside that folder.
 */
class SchematicNameTest
{
    @Test
    void acceptsAPlainName()
    {
        assertNull(SchematicName.validate("arena.litematic"));
        assertNull(SchematicName.validate("arena"));
        assertNull(SchematicName.validate("spooky-maze_v2.litematic"));
    }

    @Test
    void rejectsPathsSoAFileCantEscapeTheFolder()
    {
        assertNotNull(SchematicName.validate("../secret.litematic"));
        assertNotNull(SchematicName.validate("sub/arena.litematic"));
        assertNotNull(SchematicName.validate("sub\\arena.litematic"));
        assertNotNull(SchematicName.validate("..\\..\\evil"));
    }

    @Test
    void rejectsEmptyAndSpaces()
    {
        assertNotNull(SchematicName.validate(""));
        assertNotNull(SchematicName.validate("  "));
        assertNotNull(SchematicName.validate("my maze.litematic"));
    }

    @Test
    void normalizeAppendsTheExtensionOnlyWhenMissing()
    {
        assertEquals("arena.litematic", SchematicName.normalize("arena"));
        assertEquals("arena.litematic", SchematicName.normalize("arena.litematic"));
        // Case-insensitive on the extension it already has.
        assertEquals("arena.LITEMATIC", SchematicName.normalize("arena.LITEMATIC"));
    }

    @Test
    void listsOnlyLitematicFilesSorted(@TempDir File dir) throws IOException
    {
        Files.writeString(new File(dir, "b.litematic").toPath(), "x");
        Files.writeString(new File(dir, "a.litematic").toPath(), "x");
        Files.writeString(new File(dir, "notes.txt").toPath(), "x");

        assertEquals(List.of("a.litematic", "b.litematic"), SchematicName.list(dir));
    }

    @Test
    void listReturnsEmptyForAnAbsentFolder(@TempDir File dir)
    {
        assertEquals(List.of(), SchematicName.list(new File(dir, "nope")));
    }
}
