package app.litemazica.core.util;

public final class ParseUtils
{
    private ParseUtils()
    {
    }

    /** Parses {@code s} as an int, or returns null if it isn't one. */
    public static Integer parseInt(String s)
    {
        try
        {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
