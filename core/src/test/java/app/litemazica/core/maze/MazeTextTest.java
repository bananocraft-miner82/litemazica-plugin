package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The player-list phrasing used across the "someone is in the way" messages. */
class MazeTextTest
{
    @Test
    void namesOnePlayerWithASingularVerb()
    {
        assertEquals("Alice is", MazeText.describe(List.of("Alice")));
    }

    @Test
    void joinsTwoPlayersWithAnd()
    {
        assertEquals("Alice and Bob are", MazeText.describe(List.of("Alice", "Bob")));
    }

    @Test
    void commaSeparatesThreeOrMoreWithAndBeforeTheLast()
    {
        assertEquals("Alice, Bob and Carol are", MazeText.describe(List.of("Alice", "Bob", "Carol")));
    }

    @Test
    void saysHowManyCustomBuildsWereLeftOutOrNothingWhenNoneWere()
    {
        assertNull(MazeText.skippedBuildsNote(0));
        assertTrue(MazeText.skippedBuildsNote(1).startsWith("1 custom build in this maze's design is no longer stored"));
        assertTrue(MazeText.skippedBuildsNote(3).startsWith("3 custom builds in this maze's design are no longer stored"));
        assertTrue(MazeText.skippedBuildsNote(3).endsWith("to upload them."));
    }

    @Test
    void emptyListReadsAsNobodyRatherThanThrowing()
    {
        // Callers all guard on isEmpty, but this used to throw
        // IndexOutOfBoundsException if one ever forgot.
        assertEquals("nobody is", MazeText.describe(List.of()));
    }
}
