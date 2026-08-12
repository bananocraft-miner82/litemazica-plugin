package app.litemazica.core.platform;

/**
 * Somewhere to send a message. Core never knows whether that's a player, the
 * console, or nobody at all — scheduled regeneration has no one to talk to, and
 * uses {@link #NONE}.
 */
public interface Audience
{
    void send(MessageStyle style, String text);

    /**
     * Sends {@code label} as a link that opens {@code url} when clicked. The
     * default shows both as plain text — clients auto-link some URLs, but not
     * all (a bare IP often isn't), so platforms should override this with a real
     * clickable component where they can. Keeping it a default method leaves
     * {@link #send} the only abstract one, so {@link #NONE} stays a lambda.
     */
    default void sendLink(MessageStyle style, String label, String url)
    {
        send(style, label.equals(url) ? url : label + ": " + url);
    }

    /** Discards everything. Used by work that nobody asked for interactively. */
    Audience NONE = (style, text) ->
    {
    };
}
