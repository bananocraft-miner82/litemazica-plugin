package app.litemazica.core.maze;

import app.litemazica.core.api.MazeSchematic;
import app.litemazica.core.api.SchematicParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * A maze read from a {@code .litematic} file a server admin dropped in the
 * schematics folder. Unlike the API, a file has no generator behind it: every
 * load reproduces the same layout, so a scheduled reset restores the maze to its
 * built state (re-rolling loot chests, undoing anything players dug through)
 * rather than shuffling the walls.
 *
 * <p>A raw file also lacks the placement metadata the API sends in headers, so
 * the entrance and paste origin are defaulted from the geometry: the entrance is
 * the middle of the schematic's north (z=0) edge at floor level, which the
 * placer then rotates to face the player.
 */
public final class FileMazeSource implements MazeSource
{
    private final File dir;
    private final String fileName;
    private final Logger logger;

    /** @param fileName a {@link SchematicName#normalize normalized} name (with the .litematic extension). */
    public FileMazeSource(File dir, String fileName, Logger logger)
    {
        this.dir = dir;
        this.fileName = fileName;
        this.logger = logger;
    }

    @Override
    public MazeSchematic load(String seed) throws IOException
    {
        File file = new File(dir, fileName);

        if (!file.isFile())
        {
            throw new IOException("no schematic named '" + fileName + "' in the schematics folder");
        }

        byte[] bytes = Files.readAllBytes(file.toPath());
        SchematicParser.ParsedRegion region = SchematicParser.parse(bytes);

        if (region.regionCount() > 1)
        {
            // The parser reads only the first region; a multi-region export would
            // otherwise silently lose the rest, so say so.
            logger.warning(fileName + " has " + region.regionCount()
                    + " regions — only the first is placed.");
        }

        return build(region, baseName(fileName));
    }

    /** Assembles a maze from a file's region, defaulting the fields a raw file doesn't record. */
    private static MazeSchematic build(SchematicParser.ParsedRegion r, String nameFallback) throws IOException
    {
        // Simple default: entrance at the middle of the north (z=0) edge, floor
        // level. rotationFor() then turns the body to run toward the player.
        int entranceX = r.sizeX() / 2;
        int entranceY = 0;
        int entranceZ = 0;

        String name = r.name() != null && !r.name().isBlank() ? r.name() : nameFallback;

        try
        {
            // A raw file carries no blend metadata; the server's
            // clear-above-default config still applies at placement time, and a
            // cap falls back to the default ceiling material.
            return new MazeSchematic(
                    name, r.dataVersion(), r.sizeX(), r.sizeY(), r.sizeZ(), 0,
                    entranceX, entranceY, entranceZ, r.totalBlocks(), 0,
                    r.palette(), r.blockStates(), r.tileEntities(), 0, "minecraft:stone_bricks");
        }
        catch (IllegalArgumentException e)
        {
            throw new IOException("malformed .litematic: " + e.getMessage(), e);
        }
    }

    private static String baseName(String fileName)
    {
        return fileName.toLowerCase(Locale.ROOT).endsWith(SchematicName.EXTENSION)
                ? fileName.substring(0, fileName.length() - SchematicName.EXTENSION.length())
                : fileName;
    }

    @Override
    public boolean supportsFreshLayout()
    {
        return false;
    }

    @Override
    public String type()
    {
        return TYPE_FILE;
    }

    @Override
    public String reference()
    {
        return fileName;
    }
}
