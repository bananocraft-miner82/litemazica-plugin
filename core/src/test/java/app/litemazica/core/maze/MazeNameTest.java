package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Name rules. A name becomes the maze id, which is typed in commands and used as
 * a snapshot filename, so the character set is deliberately narrow.
 */
class MazeNameTest
{
    @Test
    void acceptsOrdinaryNames()
    {
        assertNull(MazeName.validate("arena"));
        assertNull(MazeName.validate("Arena"));
        assertNull(MazeName.validate("spawn-maze"));
        assertNull(MazeName.validate("event_2"));
        assertNull(MazeName.validate("m1"));
        assertNull(MazeName.validate("a"));
    }

    @Test
    void rejectsEmptyOrOverlongNames()
    {
        assertNotNull(MazeName.validate(null));
        assertNotNull(MazeName.validate(""));
        assertNull(MazeName.validate("a".repeat(MazeName.MAX_LENGTH)));
        assertNotNull(MazeName.validate("a".repeat(MazeName.MAX_LENGTH + 1)));
    }

    @Test
    void rejectsCharactersThatWouldBreakCommandsOrFilenames()
    {
        assertNotNull(MazeName.validate("my maze"));
        assertNotNull(MazeName.validate("maze/1"));
        assertNotNull(MazeName.validate("maze\\1"));
        assertNotNull(MazeName.validate(".."));
        assertNotNull(MazeName.validate("maze.dat"));
        assertNotNull(MazeName.validate("maze:1"));
        assertNotNull(MazeName.validate("café"));
    }

    @Test
    void rejectsAllDigitNamesBecauseTheyLookLikeCoordinates()
    {
        assertNotNull(MazeName.validate("100"));
        assertNotNull(MazeName.validate("64"));
        // Digits are fine as long as they aren't the whole name.
        assertNull(MazeName.validate("100a"));
        assertNull(MazeName.validate("a100"));
    }

    @Test
    void checkAvailablePassesANullNameSoAnIdCanBeAutoAssigned()
    {
        assertNull(MazeName.checkAvailable(null, new MazeRegistry()));
    }

    @Test
    void checkAvailableRejectsAMalformedName()
    {
        assertNotNull(MazeName.checkAvailable("my maze", new MazeRegistry()));
    }

    @Test
    void checkAvailableRejectsANameAlreadyTaken()
    {
        MazeRegistry registry = new MazeRegistry();
        registry.put(new PlacedMaze("arena", "world", MazeSource.TYPE_API, "CODE", 0, 64, 0, 0f,
                new Region(0, 60, 0, 1, 61, 1), 0, false, 0L, 0L));

        // Clash is case-insensitive, matching the registry lookup.
        assertNotNull(MazeName.checkAvailable("arena", registry));
        assertNotNull(MazeName.checkAvailable("ARENA", registry));
        assertNull(MazeName.checkAvailable("other", registry));
    }

    @Test
    void detectsCoordinateArguments()
    {
        assertTrue(MazeName.isAllDigits("100"));
        assertTrue(MazeName.isAllDigits("0"));
        assertTrue(MazeName.isAllDigits("-64"), "negative coordinates are common below sea level");

        assertFalse(MazeName.isAllDigits("arena"));
        assertFalse(MazeName.isAllDigits("100a"));
        assertFalse(MazeName.isAllDigits("-"));
        assertFalse(MazeName.isAllDigits(""));
        assertFalse(MazeName.isAllDigits(null));
    }
}
