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
    static String reason(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
