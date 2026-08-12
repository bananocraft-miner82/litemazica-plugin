package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void emptyListReadsAsNobodyRatherThanThrowing()
    {
        // Callers all guard on isEmpty, but this used to throw
        // IndexOutOfBoundsException if one ever forgot.
        assertEquals("nobody is", MazeText.describe(List.of()));
    }
}
