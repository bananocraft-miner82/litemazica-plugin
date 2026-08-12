package app.litemazica.core.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mod loaders' config: it writes documented defaults on first run, coerces
 * typed lookups, and — the subtle part — treats a blank value the same as a
 * missing one for every type, including booleans.
 */
class PropertiesConfigTest
{
    @Test
    void writesTheDefaultsOnFirstRunAndReadsThemBack(@TempDir File dir) throws Exception
    {
        File file = new File(dir, "config.properties");
        assertFalse(file.exists());

        PropertiesConfig config = new PropertiesConfig(file, PropertiesConfig.DEFAULTS);

        assertTrue(file.exists(), "the defaults file is written when absent");
        assertEquals("https://litemazica.app", config.getString("api-base-url", "fallback"));
        assertEquals(6_000_000L, config.getLong("max-volume", 0));
        assertTrue(config.getBoolean("snapshot", false));
    }

    @Test
    void coercesIntsAndLongsAndFallsBackOnGarbage(@TempDir File dir) throws Exception
    {
        PropertiesConfig config = write(dir, "regen-check-seconds=45\nmax-volume=notanumber\n");

        assertEquals(45, config.getInt("regen-check-seconds", 30));
        assertEquals(999L, config.getLong("max-volume", 999L), "unparseable → fallback, not a crash");
        assertEquals(7, config.getInt("absent-key", 7), "absent → fallback");
    }

    @Test
    void getStringTrimsAndTreatsBlankAsAbsent(@TempDir File dir) throws Exception
    {
        PropertiesConfig config = write(dir, "api-base-url=  https://self.host  \nblank-key=   \n");

        assertEquals("https://self.host", config.getString("api-base-url", "x"), "value is trimmed");
        assertEquals("fallback", config.getString("blank-key", "fallback"), "a blank value falls back");
    }

    @Test
    void blankBooleanFallsBackRatherThanReadingAsFalse(@TempDir File dir) throws Exception
    {
        // The regression this guards: "snapshot=" used to parse to false and
        // silently override the default. A blank value must defer to the fallback,
        // exactly like the numeric getters do.
        PropertiesConfig config = write(dir, "snapshot=\nregen-fresh-layout=false\n");

        assertTrue(config.getBoolean("snapshot", true), "blank boolean → fallback");
        assertFalse(config.getBoolean("regen-fresh-layout", true), "an explicit false is honoured");
        assertTrue(config.getBoolean("absent", true), "absent → fallback");
    }

    @Test
    void reloadPicksUpAnEditedFile(@TempDir File dir) throws Exception
    {
        File file = new File(dir, "config.properties");
        PropertiesConfig config = new PropertiesConfig(file, "max-volume=100\n");
        assertEquals(100, config.getInt("max-volume", 0));

        Files.writeString(file.toPath(), "max-volume=200\n");
        config.reload();

        assertEquals(200, config.getInt("max-volume", 0));
    }

    private static PropertiesConfig write(File dir, String contents) throws Exception
    {
        File file = new File(dir, "config.properties");
        Files.writeString(file.toPath(), contents);
        return new PropertiesConfig(file, contents);
    }
}
