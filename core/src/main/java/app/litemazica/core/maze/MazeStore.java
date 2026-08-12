package app.litemazica.core.maze;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Reads and writes the placed-maze registry, so scheduled regeneration survives
 * a restart.
 *
 * <p>Uses {@link Properties} rather than YAML: core has no dependencies and no
 * platform, and Bukkit's bundled YAML isn't available to a Fabric or NeoForge
 * build. Properties is in the JDK, handles escaping, and stays readable.
 *
 * <p>{@link #serialize} reads the registry and is meant to run on the main
 * thread; {@link #write} is pure I/O and can run off it.
 */
public final class MazeStore
{
    private static final String PREFIX = "maze.";

    private MazeStore()
    {
    }

    /** Renders the mazes to a properties document. Reads the registry — main-thread. */
    public static String serialize(Collection<PlacedMaze> mazes)
    {
        Properties props = new Properties();

        for (PlacedMaze m : mazes)
        {
            String p = PREFIX + m.id() + ".";
            props.setProperty(p + "world", m.worldName());

            // A file maze records its source and file name; an API maze keeps
            // writing a bare "code" (no "source" key), so existing files — and
            // anything that reads them — are untouched. A missing source loads
            // back as "api" below.
            if (m.isFileSource())
            {
                props.setProperty(p + "source", MazeSource.TYPE_FILE);
                props.setProperty(p + "file", m.shareCode());
            }
            else
            {
                props.setProperty(p + "code", m.shareCode());
            }

            props.setProperty(p + "anchorX", Integer.toString(m.anchorX()));
            props.setProperty(p + "anchorY", Integer.toString(m.anchorY()));
            props.setProperty(p + "anchorZ", Integer.toString(m.anchorZ()));
            props.setProperty(p + "yaw", Float.toString(m.yaw()));
            props.setProperty(p + "minX", Integer.toString(m.minX()));
            props.setProperty(p + "minY", Integer.toString(m.minY()));
            props.setProperty(p + "minZ", Integer.toString(m.minZ()));
            props.setProperty(p + "maxX", Integer.toString(m.maxX()));
            props.setProperty(p + "maxY", Integer.toString(m.maxY()));
            props.setProperty(p + "maxZ", Integer.toString(m.maxZ()));
            props.setProperty(p + "regenMinutes", Integer.toString(m.regenMinutes()));
            props.setProperty(p + "fresh", Boolean.toString(m.freshLayout()));
            props.setProperty(p + "placedAt", Long.toString(m.placedAtEpochMs()));
            props.setProperty(p + "lastRegen", Long.toString(m.lastRegenEpochMs()));
        }

        StringWriter writer = new StringWriter();

        try
        {
            props.store(writer, "Litemazica placed mazes - edited by the plugin, but safe to read");
        }
        catch (IOException e)
        {
            // StringWriter cannot fail.
            throw new IllegalStateException(e);
        }

        // Properties.store writes a timestamp comment and arbitrary key order,
        // which makes every save look like a change. Drop the comments and sort.
        List<String> lines = new ArrayList<>();

        for (String line : writer.toString().split("\\R"))
        {
            if (!line.startsWith("#") && !line.isBlank())
            {
                lines.add(line);
            }
        }

        lines.sort(String::compareTo);
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    /** Writes a serialized document to disk. Pure I/O — safe off the main thread. */
    public static void write(File file, String data) throws IOException
    {
        File parent = file.getParentFile();

        if (parent != null && !parent.exists())
        {
            parent.mkdirs();
        }

        Files.writeString(file.toPath(), data, StandardCharsets.UTF_8);
    }

    public static List<PlacedMaze> load(File file, Logger logger)
    {
        List<PlacedMaze> out = new ArrayList<>();

        if (!file.exists())
        {
            return out;
        }

        Properties props = new Properties();

        try
        {
            props.load(new StringReader(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
        }
        catch (IOException e)
        {
            return out;
        }

        for (String id : idsIn(props))
        {
            // An id becomes a path (snapshots/<id>.dat) that is both written and
            // deleted, so it has to clear the same bar as a player-supplied name.
            // Creation validates; this file does not have to have come from us.
            String bad = MazeName.validate(id);

            if (bad != null)
            {
                logger.warning("Ignoring maze '" + id + "' in " + file.getName() + ": " + bad);
                continue;
            }

            String p = PREFIX + id + ".";
            String world = props.getProperty(p + "world");
            // No "source" key means an entry written before file mazes existed —
            // always an API maze, keyed by "code".
            String source = props.getProperty(p + "source", MazeSource.TYPE_API);
            String reference = MazeSource.TYPE_FILE.equals(source)
                    ? props.getProperty(p + "file")
                    : props.getProperty(p + "code");

            if (world == null || reference == null)
            {
                continue; // half-written entry; skip rather than fail the load
            }

            Region region = new Region(
                    intAt(props, p + "minX"), intAt(props, p + "minY"), intAt(props, p + "minZ"),
                    intAt(props, p + "maxX"), intAt(props, p + "maxY"), intAt(props, p + "maxZ"));

            out.add(new PlacedMaze(
                    id, world, source, reference,
                    intAt(props, p + "anchorX"), intAt(props, p + "anchorY"), intAt(props, p + "anchorZ"),
                    floatAt(props, p + "yaw"),
                    region,
                    intAt(props, p + "regenMinutes"),
                    Boolean.parseBoolean(props.getProperty(p + "fresh", "false")),
                    longAt(props, p + "placedAt"),
                    longAt(props, p + "lastRegen")));
        }

        return out;
    }

    /** Distinct maze ids present in the file, in a stable order. */
    private static Set<String> idsIn(Properties props)
    {
        Set<String> ids = new TreeSet<>();

        for (String key : props.stringPropertyNames())
        {
            if (!key.startsWith(PREFIX))
            {
                continue;
            }

            int dot = key.indexOf('.', PREFIX.length());

            if (dot > PREFIX.length())
            {
                ids.add(key.substring(PREFIX.length(), dot));
            }
        }

        return ids;
    }

    private static int intAt(Properties props, String key)
    {
        try
        {
            return Integer.parseInt(props.getProperty(key, "0").trim());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private static long longAt(Properties props, String key)
    {
        try
        {
            return Long.parseLong(props.getProperty(key, "0").trim());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    private static float floatAt(Properties props, String key)
    {
        try
        {
            return Float.parseFloat(props.getProperty(key, "0").trim());
        }
        catch (NumberFormatException e)
        {
            return 0f;
        }
    }
}
