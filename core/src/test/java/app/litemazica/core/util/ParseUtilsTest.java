package app.litemazica.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Coordinate parsing: a valid int, or null so the caller can report a bad argument. */
class ParseUtilsTest
{
    @Test
    void parsesWholeNumbersIncludingNegatives()
    {
        assertEquals(5, ParseUtils.parseInt("5"));
        assertEquals(0, ParseUtils.parseInt("0"));
        assertEquals(-64, ParseUtils.parseInt("-64"));
    }

    @Test
    void returnsNullForNonNumbers()
    {
        assertNull(ParseUtils.parseInt("arena"));
        assertNull(ParseUtils.parseInt("64a"));
        assertNull(ParseUtils.parseInt(""));
        assertNull(ParseUtils.parseInt("1.5"));
    }
}
