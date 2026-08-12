package app.litemazica.core.maze;

/**
 * Rules for a player-chosen maze name. The name becomes the maze's id, so it has
 * to survive being typed in a command, tab-completed, and used as a snapshot
 * filename — hence the conservative character set.
 */
public final class MazeName
{
    public static final int MAX_LENGTH = 32;

    private MazeName()
    {
    }

    /**
     * @return null if the name is usable, otherwise a player-facing reason why not.
     */
    public static String validate(String name)
    {
        if (name == null || name.isEmpty())
        {
            return "A maze name cannot be empty.";
        }

        if (name.length() > MAX_LENGTH)
        {
            return "A maze name can be at most " + MAX_LENGTH + " characters.";
        }

        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';

            if (!allowed)
            {
                return "A maze name may only contain letters, digits, hyphens and underscores.";
            }
        }

        if (isAllDigits(name))
        {
            // Otherwise "generate <code> 100 64 100" couldn't be told apart from
            // a maze named 100 placed at the player's feet.
            return "A maze name cannot be only digits — it would be read as coordinates.";
        }

        return null;
    }

    /**
     * The full pre-build name check a {@code generate}/{@code place} command runs:
     * the name is well-formed and not already taken. Folds {@link #validate} and the
     * registry clash into one call so every platform's command layer shares the
     * wording rather than repeating it.
     *
     * @param name     the requested name, or null to auto-assign (always allowed).
     * @param registry the placed mazes to check the name against.
     * @return null if the name may be used (including when it is null), otherwise a
     *         player-facing reason why not.
     */
    public static String checkAvailable(String name, MazeRegistry registry)
    {
        if (name == null)
        {
            return null; // no name given — an id will be auto-assigned
        }

        String problem = validate(name);

        if (problem != null)
        {
            return problem;
        }

        if (registry.exists(name))
        {
            return "A maze called '" + name + "' already exists. Pick another name.";
        }

        return null;
    }

    /** Used to tell a coordinate argument from a name while parsing. */
    public static boolean isAllDigits(String value)
    {
        if (value == null || value.isEmpty())
        {
            return false;
        }

        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);

            if ((c < '0' || c > '9') && !(i == 0 && c == '-' && value.length() > 1))
            {
                return false;
            }
        }

        return true;
    }
}
