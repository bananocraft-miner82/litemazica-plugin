package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The named reset intervals, and how stored minutes map back to a label. */
class RegenIntervalTest
{
    @Test
    void presetsConvertToMinutes()
    {
        assertEquals(0, RegenInterval.OFF.minutes());
        assertEquals(60, RegenInterval.HOURLY.minutes());
        assertEquals(60 * 24, RegenInterval.DAILY.minutes());
        assertEquals(60 * 24 * 7, RegenInterval.WEEKLY.minutes());
        assertEquals(60 * 24 * 30, RegenInterval.MONTHLY.minutes());
    }

    @Test
    void parsesLabelsCaseInsensitively()
    {
        assertSame(RegenInterval.DAILY, RegenInterval.fromLabel("daily"));
        assertSame(RegenInterval.DAILY, RegenInterval.fromLabel("DAILY"));
        assertSame(RegenInterval.WEEKLY, RegenInterval.fromLabel("Weekly"));
        assertSame(RegenInterval.OFF, RegenInterval.fromLabel("off"));
    }

    @Test
    void rejectsAnythingThatIsNotAPreset()
    {
        assertNull(RegenInterval.fromLabel("yearly"));
        assertNull(RegenInterval.fromLabel("60"));
        assertNull(RegenInterval.fromLabel(""));
        assertNull(RegenInterval.fromLabel(null));
        // "now" is handled by the command, not as an interval.
        assertNull(RegenInterval.fromLabel("now"));
    }

    @Test
    void namesTheIntervalForStoredMinutes()
    {
        assertEquals("off", RegenInterval.labelFor(0));
        assertEquals("hourly", RegenInterval.labelFor(60));
        assertEquals("daily", RegenInterval.labelFor(1440));
        assertEquals("weekly", RegenInterval.labelFor(10080));
        assertEquals("monthly", RegenInterval.labelFor(43200));
    }

    @Test
    void fallsBackToRawMinutesForNonPresetValues()
    {
        // Hand-edited mazes.yml, or a value set before the presets existed.
        assertEquals("5m", RegenInterval.labelFor(5));
        assertEquals("90m", RegenInterval.labelFor(90));
    }

    @Test
    void exposesItsLabelsForHelpAndTabCompletion()
    {
        assertEquals(List.of("off", "hourly", "daily", "weekly", "monthly"), RegenInterval.labels());
    }
}
