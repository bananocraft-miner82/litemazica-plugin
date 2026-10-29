package app.litemazica.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Share codes as people paste them: bare, or inside a share link. */
class ShareCodesTest
{
    @Test
    void takesTheCodeOutOfAShareLink()
    {
        assertEquals("1abc_DEF-2", ShareCodes.extract("https://litemazica.app/?s=1abc_DEF-2"));
        assertEquals("1abc", ShareCodes.extract("  https://litemazica.app/?ref=x&s=1abc&utm=y  "));
        assertEquals("1abc", ShareCodes.extract("https://litemazica.app/#s=1abc"), "the web app's own address bar");
        assertEquals("1a+b", ShareCodes.extract("https://litemazica.app/?s=1a%2Bb"), "percent-encoded, as some chat apps send it");
    }

    @Test
    void leavesABareCodeAsItIs()
    {
        assertEquals("1abcDEF", ShareCodes.extract(" 1abcDEF "));
        assertEquals("", ShareCodes.extract(null));
        assertEquals("", ShareCodes.extract("   "));
        assertEquals("https://litemazica.app/", ShareCodes.extract("https://litemazica.app/"), "no code to take");
    }
}
