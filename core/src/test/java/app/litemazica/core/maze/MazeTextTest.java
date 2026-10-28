package app.litemazica.core.maze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void namesBlocksTheServerLacksPlainlyAndCountsTheRest()
    {
        assertNull(MazeText.unknownBlocksNote(List.of()));
        assertTrue(MazeText.unknownBlocksNote(List.of("minecraft:poplar_planks"))
                .startsWith("This server doesn't have poplar_planks, so it is placed as air."));
        // Block states don't make a block count twice.
        assertTrue(MazeText.unknownBlocksNote(List.of("minecraft:poplar_slab[type=top]", "minecraft:poplar_slab[type=bottom]", "minecraft:cinnabar"))
                .startsWith("This server doesn't have poplar_slab and cinnabar, so they are placed as air."));
        assertTrue(MazeText.unknownBlocksNote(List.of("minecraft:a", "minecraft:b", "minecraft:c", "minecraft:d", "minecraft:e"))
                .startsWith("This server doesn't have a, b, c and 2 more, so they are"));
    }

    @Test
    void knowsWhereSignTextChangedForm()
    {
        assertFalse(MazeText.textFormatsDiffer(3465, 3953), "1.20.1 and 1.21 store text alike");
        assertTrue(MazeText.textFormatsDiffer(3953, 4325), "JSON before 1.21.5, NBT from it");
        assertTrue(MazeText.textFormatsDiffer(5023, 3465), "a newer file on an older server too");
        assertTrue(MazeText.textFormatsDiffer(3337, 3465), "1.19 lines were Text1-4");
        assertFalse(MazeText.textFormatsDiffer(4325, 5023));
        assertFalse(MazeText.textFormatsDiffer(3465, 0), "an unknown server version never warns");

        assertNull(MazeText.textFormatNote(3953, 4325, false), "nothing to say without signs or names");
        assertTrue(MazeText.textFormatNote(3953, 4325, true).startsWith("This maze was made for an older Minecraft"));
        assertTrue(MazeText.textFormatNote(5023, 3465, true).startsWith("This maze was made for a newer Minecraft"));
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
