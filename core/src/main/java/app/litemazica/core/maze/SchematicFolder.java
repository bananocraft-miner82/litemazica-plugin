package app.litemazica.core.maze;

import app.litemazica.core.platform.Platform;

import java.io.File;
import java.util.List;

/**
 * The folder admins drop {@code .litematic} files into, under the data folder —
 * the location of {@code /litemazica place}'s inputs. A thin wrapper so the path
 * (config-driven) is computed in one place rather than re-derived by the builder,
 * the rebuilder and the service.
 */
final class SchematicFolder
{
    private final Platform platform;

    SchematicFolder(Platform platform)
    {
        this.platform = platform;
    }

    File dir()
    {
        return new File(platform.dataFolder(), platform.config().getString("schematic-folder", "schematics"));
    }

    /** Creates the folder on first run so admins have somewhere to drop files. */
    void ensureExists()
    {
        File dir = dir();

        if (!dir.isDirectory() && !dir.mkdirs())
        {
            platform.logger().warning("Could not create the schematics folder at " + dir.getAbsolutePath() + ".");
        }
    }

    /** The {@code .litematic} files available to {@code place}, by name. */
    List<String> list()
    {
        return SchematicName.list(dir());
    }
}
