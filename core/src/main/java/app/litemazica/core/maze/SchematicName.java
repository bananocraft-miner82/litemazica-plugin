package app.litemazica.core.maze;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rules for a {@code .litematic} filename an admin drops in the schematics
 * folder. The name is typed in a command and turned into a path under the data
 * folder, so — like {@link MazeName} — it has to be conservative enough that it
 * can never escape that folder or arrive with a shape the shell mangled.
 */
public final class SchematicName
{
    public static final String EXTENSION = ".litematic";
    public static final int MAX_LENGTH = 64;

    private SchematicName()
    {
    }

    /**
     * @return null if the name is usable, otherwise a player-facing reason why not.
     *         Validate the raw argument (before {@link #normalize}); the checks
     *         reject the separators and {@code ..} that a normalize could hide.
     */
    public static String validate(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return "Give the name of a .litematic file in the schematics folder.";
        }

        if (raw.indexOf('/') >= 0 || raw.indexOf('\\') >= 0 || raw.contains(".."))
        {
            return "A schematic name can't contain a path — just the file name.";
        }

        String base = stripExtension(raw);

        if (base.isEmpty())
        {
            return "A schematic name cannot be only an extension.";
        }

        if (normalize(raw).length() > MAX_LENGTH)
        {
            return "A schematic name can be at most " + MAX_LENGTH + " characters.";
        }

        for (int i = 0; i < base.length(); i++)
        {
            char c = base.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';

            if (!allowed)
            {
                return "A schematic name may only contain letters, digits, dots, hyphens and underscores"
                        + " (rename the file if it has spaces).";
            }
        }

        return null;
    }

    /** Appends {@code .litematic} if the admin left it off. Case-insensitive on the extension. */
    public static String normalize(String raw)
    {
        String trimmed = raw.trim();
        return hasExtension(trimmed) ? trimmed : trimmed + EXTENSION;
    }

    /** The {@code .litematic} files in {@code dir}, by name, sorted. Empty if the folder is absent. */
    public static List<String> list(File dir)
    {
        File[] files = dir.listFiles((d, name) -> hasExtension(name));
        List<String> out = new ArrayList<>();

        if (files != null)
        {
            for (File f : files)
            {
                if (f.isFile())
                {
                    out.add(f.getName());
                }
            }
        }

        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static boolean hasExtension(String name)
    {
        return name.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    private static String stripExtension(String name)
    {
        return hasExtension(name) ? name.substring(0, name.length() - EXTENSION.length()) : name;
    }
}
