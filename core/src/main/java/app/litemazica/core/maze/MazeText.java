package app.litemazica.core.maze;

import java.util.List;

/** Small message helpers shared by the maze services. Pure — pinned by tests. */
final class MazeText
{
    private MazeText()
    {
    }

    /** "Alice is" / "Alice and Bob are" / "Alice, Bob and Carol are". */
    static String describe(List<String> names)
    {
        if (names.isEmpty())
        {
            // Callers all guard on isEmpty, but this used to throw an
            // IndexOutOfBoundsException rather than say something harmless.
            return "nobody is";
        }

        String joined = names.size() == 1
                ? names.get(0)
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
        return joined + (names.size() == 1 ? " is" : " are");
    }

    /**
     * A human-readable reason from a throwable. Some exceptions carry no message
     * (an EOF while decompressing, an interrupted request), and "reason: null"
     * tells nobody anything — fall back to the exception type.
     */
    /** What to say when a maze was built without some of its custom builds, or null when none were left out. */
    static String skippedBuildsNote(int skipped)
    {
        if (skipped <= 0)
        {
            return null;
        }

        return (skipped == 1 ? "1 custom build" : skipped + " custom builds")
                + " in this maze's design " + (skipped == 1 ? "is" : "are")
                + " no longer stored on the Litemazica server, so the maze was built without "
                + (skipped == 1 ? "it" : "them")
                + ". Open the maze in the web editor and apply or share it again to upload "
                + (skipped == 1 ? "it." : "them.");
    }

    /**
     * DataVersions at which sign text changed form: 1.20 moved a sign's lines
     * into {@code front_text}, and 1.21.5 stores text as NBT rather than JSON.
     */
    private static final int[] TEXT_FORMAT_CHANGES = {3463, 4325};

    /**
     * What to say when this server lacks some of a maze's blocks, which are
     * placed as air, or null when it has them all. Names a few (without the
     * {@code minecraft:} or block states) and counts the rest.
     */
    static String unknownBlocksNote(List<String> unknown)
    {
        List<String> names = unknown.stream().map(MazeText::blockName).distinct().toList();

        if (names.isEmpty())
        {
            return null;
        }

        String listed = names.size() <= 3
                ? joinWithAnd(names)
                : String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
        return "This server doesn't have " + listed + ", so " + (names.size() == 1 ? "it is" : "they are")
                + " placed as air. The maze uses blocks from a newer Minecraft than the server runs:"
                + " pick other blocks in the web app, or update the server.";
    }

    /** Whether text saved at one DataVersion is stored differently at another (0 means not known: never). */
    static boolean textFormatsDiffer(int saved, int server)
    {
        if (saved <= 0 || server <= 0)
        {
            return false;
        }

        for (int change : TEXT_FORMAT_CHANGES)
        {
            if ((saved < change) != (server < change))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * What to say when a maze with sign text or names was made for a Minecraft
     * that stores them differently from this server, or null when it reads the same.
     */
    static String textFormatNote(int saved, int server, boolean hasText)
    {
        if (!hasText || !textFormatsDiffer(saved, server))
        {
            return null;
        }

        return "This maze was made for " + (saved < server ? "an older" : "a newer")
                + " Minecraft than this server runs, which stores sign text differently,"
                + " so its signs and names may show code rather than words.";
    }

    /** {@code minecraft:oak_stairs[facing=east]} as {@code oak_stairs}. */
    private static String blockName(String blockState)
    {
        int bracket = blockState.indexOf('[');
        String id = bracket < 0 ? blockState : blockState.substring(0, bracket);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** "a" / "a and b" / "a, b and c". */
    private static String joinWithAnd(List<String> items)
    {
        return items.size() == 1
                ? items.get(0)
                : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    static String reason(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
