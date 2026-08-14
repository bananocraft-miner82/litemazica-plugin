package app.litemazica.core.platform;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * A properties file, written with documented defaults on first run. Bukkit has
 * its own config system; the mod loaders don't, and core has no dependencies, so
 * both mods share this.
 */
public final class PropertiesConfig implements ConfigSource
{
    /** The same settings the Bukkit build exposes in config.yml. */
    public static final String DEFAULTS = """
            # Litemazica configuration.

            # Base URL of the Litemazica API - the service that turns a share code
            # into a .litematic. No trailing slash needed.
            api-base-url=https://litemazica.app

            # How long to wait for the API before giving up, in seconds.
            request-timeout-seconds=20

            # Safety cap: refuse to build a maze larger than this many blocks.
            max-volume=6000000

            # Record the terrain a maze is built over, so removing it puts the
            # world back instead of leaving a box of air.
            snapshot=true

            # Skip the snapshot for mazes larger than this many blocks. Snapshots
            # are per-chunk files streamed one chunk at a time, so this is about
            # disk/time, not memory. 6,000,000 = the max buildable maze.
            snapshot-max-volume=6000000

            # Blend an open-top maze into the terrain above it: scan this many
            # blocks up, stripping canopy/water under open sky and capping under
            # rock/dirt (0 = off; ~40 is a good "on" value). The editor's per-maze
            # "Blend top" toggle wins; this is the fallback for file mazes, and
            # only applies to mazes that have no ceiling.
            blend-top-reach=0

            # Default reset interval, in minutes, for a newly built maze (0 = off).
            default-regen-minutes=0

            # On reset, build a brand-new layout (true) or the same maze (false).
            regen-fresh-layout=true

            # How often (seconds) the scheduler checks whether a maze is due.
            regen-check-seconds=30

            # The most mazes the scheduler resets at once. Mazes that come due
            # together are staggered across checks instead of rebuilt in one
            # burst, so fetches and block work don't spike. Minimum 1.
            regen-max-concurrent=1

            # How often (seconds) /litemazica editor and /litemazica edit poll for
            # a pressed "Apply to server". Minimum 2.
            editor-poll-seconds=4
            """;

    private final File file;
    private final String defaults;
    private final Properties props = new Properties();

    public PropertiesConfig(File file, String defaults)
    {
        this.file = file;
        this.defaults = defaults;
        reload();
    }

    public void reload()
    {
        try
        {
            if (!file.exists())
            {
                File parent = file.getParentFile();

                if (parent != null && !parent.exists())
                {
                    parent.mkdirs();
                }

                Files.writeString(file.toPath(), defaults, StandardCharsets.UTF_8);
            }

            props.clear();
            props.load(new StringReader(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
        }
        catch (IOException e)
        {
            // Fall back to the built-in defaults rather than refusing to start.
            props.clear();
        }
    }

    @Override
    public String getString(String key, String fallback)
    {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public int getInt(String key, int fallback)
    {
        try
        {
            return Integer.parseInt(getString(key, Integer.toString(fallback)));
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    @Override
    public long getLong(String key, long fallback)
    {
        try
        {
            return Long.parseLong(getString(key, Long.toString(fallback)));
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean fallback)
    {
        // Via getString so a missing OR blank value both fall back — consistent
        // with getInt/getLong/getString. Reading the raw property instead made a
        // blank value (e.g. "snapshot=") parse to false, silently overriding the
        // default rather than deferring to it.
        String value = getString(key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
