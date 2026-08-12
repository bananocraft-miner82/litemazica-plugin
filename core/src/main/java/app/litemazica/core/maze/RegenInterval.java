package app.litemazica.core.maze;

import java.util.List;
import java.util.Locale;

/**
 * The reset intervals a maze can be put on. Stored as plain minutes on
 * {@link PlacedMaze}, so hand-edited or legacy values in {@code mazes.properties} keep
 * working — they just display as raw minutes rather than a preset name.
 */
public enum RegenInterval
{
    OFF("off", 0),
    HOURLY("hourly", 60),
    DAILY("daily", 60 * 24),
    WEEKLY("weekly", 60 * 24 * 7),
    /** A calendar month varies; 30 days is the useful approximation here. */
    MONTHLY("monthly", 60 * 24 * 30);

    private final String label;
    private final int minutes;

    RegenInterval(String label, int minutes)
    {
        this.label = label;
        this.minutes = minutes;
    }

    public String label()
    {
        return label;
    }

    public int minutes()
    {
        return minutes;
    }

    /** All preset names, in ascending order — for help text and tab completion. */
    public static List<String> labels()
    {
        return List.of(OFF.label, HOURLY.label, DAILY.label, WEEKLY.label, MONTHLY.label);
    }

    /** The preset with this name, or null if it isn't one. Case-insensitive. */
    public static RegenInterval fromLabel(String name)
    {
        if (name == null)
        {
            return null;
        }

        String needle = name.toLowerCase(Locale.ROOT);

        for (RegenInterval interval : values())
        {
            if (interval.label.equals(needle))
            {
                return interval;
            }
        }

        return null;
    }

    /** How to describe a stored interval: a preset name, or "<n>m" if it isn't one. */
    public static String labelFor(int minutes)
    {
        for (RegenInterval interval : values())
        {
            if (interval.minutes == minutes)
            {
                return interval.label;
            }
        }

        return minutes + "m";
    }
}
