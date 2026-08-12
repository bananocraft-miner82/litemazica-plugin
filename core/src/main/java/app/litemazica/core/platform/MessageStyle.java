package app.litemazica.core.platform;

/**
 * Intent of a message, not its colour. Each platform maps these to whatever it
 * uses — legacy chat colours on Bukkit, text components on Fabric/NeoForge.
 */
public enum MessageStyle
{
    /** Progress and neutral detail. */
    INFO,
    /** The thing worked. */
    SUCCESS,
    /** Refused, or deferred, but nothing is broken. */
    WARNING,
    /** Refused because something is wrong. */
    ERROR,
    /** A list header. */
    HEADING
}
