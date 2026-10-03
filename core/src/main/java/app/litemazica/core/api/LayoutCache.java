package app.litemazica.core.api;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The plugin's own copy of each placed maze's layout: the {@code .litematic} the
 * API generated for a share code's own seed, and the placement details that
 * came with it. With it, a "same layout" reset (or placing a maze again) needs
 * no API call at all, which keeps the hosted API's work, and so its running
 * costs, down. Fresh-layout resets still ask the API, since each needs a new
 * maze generated.
 *
 * <p>Files live in one folder, named by a hash of the share code:
 * {@code <hash>.litematic} and {@code <hash>.properties}. Each store drops the
 * entries of share codes no placed maze uses any more. Anything unreadable is
 * treated as missing, so the worst a damaged cache can do is cost an API call.
 */
public final class LayoutCache
{
    /** Header names (lower case) are stored with this prefix in the properties file. */
    private static final String HEADER = "header.";

    private final File dir;
    private final Supplier<Set<String>> liveCodes;
    private final Logger logger;

    /**
     * @param liveCodes the share codes placed mazes use now; entries for any
     *                  other code are deleted on the next store.
     */
    public LayoutCache(File dir, Supplier<Set<String>> liveCodes, Logger logger)
    {
        this.dir = dir;
        this.liveCodes = liveCodes;
        this.logger = logger;
    }

    /** The cached layout for a share code, or null if there isn't a good one. */
    public synchronized RawLayout get(String code)
    {
        String key = key(code);
        File body = new File(dir, key + ".litematic");
        File meta = new File(dir, key + ".properties");

        if (!body.isFile() || !meta.isFile())
        {
            return null;
        }

        try
        {
            Properties props = new Properties();

            try (Reader in = Files.newBufferedReader(meta.toPath(), StandardCharsets.UTF_8))
            {
                props.load(in);
            }

            // A different code that happens to share the hash's prefix isn't ours.
            if (!code.equals(props.getProperty("code")))
            {
                return null;
            }

            Map<String, String> headers = new TreeMap<>();

            for (String name : props.stringPropertyNames())
            {
                if (name.startsWith(HEADER))
                {
                    headers.put(name.substring(HEADER.length()), props.getProperty(name));
                }
            }

            return new RawLayout(Files.readAllBytes(body.toPath()), headers);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** Keeps a layout for its share code, replacing any earlier one. Failures are logged, never thrown. */
    public synchronized void put(String code, RawLayout layout)
    {
        String key = key(code);

        try
        {
            Files.createDirectories(dir.toPath());
            Properties props = new Properties();
            props.setProperty("code", code);
            props.setProperty("cached-at", Long.toString(System.currentTimeMillis()));
            layout.headers().forEach((name, value) -> props.setProperty(HEADER + name, value));

            // The details are written last: a store cut short leaves no
            // properties file, so get() treats it as missing.
            File meta = new File(dir, key + ".properties");
            Files.deleteIfExists(meta.toPath());
            File body = new File(dir, key + ".litematic");
            File temp = new File(dir, key + ".litematic.tmp");
            Files.write(temp.toPath(), layout.body());
            Files.move(temp.toPath(), body.toPath(), StandardCopyOption.REPLACE_EXISTING);

            try (Writer out = Files.newBufferedWriter(meta.toPath(), StandardCharsets.UTF_8))
            {
                props.store(out, "Litemazica layout cache: delete this folder to clear it");
            }
        }
        catch (IOException e)
        {
            logger.warning("Could not cache the layout for a maze: " + e.getMessage());
            return;
        }

        prune(code);
    }

    /** Deletes entries for share codes no placed maze uses, keeping {@code keep} (one being placed now). */
    private void prune(String keep)
    {
        File[] files = dir.listFiles();

        if (files == null)
        {
            return;
        }

        Set<String> wanted = new HashSet<>();
        wanted.add(key(keep));

        for (String code : liveCodes.get())
        {
            wanted.add(key(code));
        }

        for (File file : files)
        {
            String name = file.getName();
            int dot = name.indexOf('.');
            String key = dot < 0 ? name : name.substring(0, dot);

            if (!wanted.contains(key) && !file.delete())
            {
                logger.fine("Could not delete stale cached layout " + name);
            }
        }
    }

    /** A file-name-safe key for a share code: the start of its SHA-256. */
    static String key(String code)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
