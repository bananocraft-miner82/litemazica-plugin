package app.litemazica.core.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Share codes as people paste them. Pure — pinned by tests. */
public final class ShareCodes
{
    /** The code in a share link: {@code ?s=}, or the {@code #s=} the web app keeps in its address bar. */
    private static final Pattern IN_LINK = Pattern.compile("[?&#]s=([^&\\s]+)");

    private ShareCodes()
    {
    }

    /**
     * The share code in whatever was pasted: a share link
     * ({@code https://litemazica.app/?s=...}) or a bare code. The same rule as
     * the web app's own "open a code" box, so anything it opens works here too.
     */
    public static String extract(String input)
    {
        if (input == null)
        {
            return "";
        }

        String trimmed = input.trim();
        Matcher match = IN_LINK.matcher(trimmed);

        if (!match.find())
        {
            return trimmed;
        }

        try
        {
            return URLDecoder.decode(match.group(1), StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException e)
        {
            return match.group(1); // a stray '%': the code as it stands
        }
    }
}
