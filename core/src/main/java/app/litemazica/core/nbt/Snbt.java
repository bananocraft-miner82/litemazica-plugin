package app.litemazica.core.nbt;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes NBT values (as {@link NbtReader} produces them) back out as SNBT, the
 * text form vanilla commands take: {@code {Facing:3b,Item:{id:"minecraft:apple",Count:1b}}}.
 * Every numeric type keeps its suffix, so a value survives the round trip with
 * the type the game expects.
 *
 * <p>The text goes into a command line, so strings are always quoted and
 * escaped, and line breaks are flattened: nothing in a value can end the
 * argument it sits in.
 */
public final class Snbt
{
    /** Keys that may be written bare; anything else is quoted. */
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9._+-]+");

    private Snbt()
    {
    }

    public static String write(Object value)
    {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value)
    {
        if (value instanceof Map<?, ?> map)
        {
            sb.append('{');
            boolean first = true;

            for (Map.Entry<?, ?> e : map.entrySet())
            {
                if (!first) sb.append(',');
                first = false;
                String key = String.valueOf(e.getKey());
                if (BARE_KEY.matcher(key).matches()) sb.append(key);
                else quote(sb, key);
                sb.append(':');
                append(sb, e.getValue());
            }

            sb.append('}');
        }
        else if (value instanceof List<?> list)
        {
            sb.append('[');

            for (int i = 0; i < list.size(); i++)
            {
                if (i > 0) sb.append(',');
                append(sb, list.get(i));
            }

            sb.append(']');
        }
        else if (value instanceof String s)
        {
            quote(sb, s);
        }
        else if (value instanceof Byte b)
        {
            sb.append(b).append('b');
        }
        else if (value instanceof Short s)
        {
            sb.append(s).append('s');
        }
        else if (value instanceof Integer i)
        {
            sb.append(i);
        }
        else if (value instanceof Long l)
        {
            sb.append(l).append('L');
        }
        else if (value instanceof Float f)
        {
            sb.append(finite(f)).append('f');
        }
        else if (value instanceof Double d)
        {
            sb.append(finite(d)).append('d');
        }
        else if (value instanceof byte[] a)
        {
            sb.append("[B;");
            for (int i = 0; i < a.length; i++) sb.append(i > 0 ? "," : "").append(a[i]).append('b');
            sb.append(']');
        }
        else if (value instanceof int[] a)
        {
            sb.append("[I;");
            for (int i = 0; i < a.length; i++) sb.append(i > 0 ? "," : "").append(a[i]);
            sb.append(']');
        }
        else if (value instanceof long[] a)
        {
            sb.append("[L;");
            for (int i = 0; i < a.length; i++) sb.append(i > 0 ? "," : "").append(a[i]).append('L');
            sb.append(']');
        }
        else
        {
            // Nothing NbtReader produces lands here; an unknown value becomes an empty string.
            sb.append("\"\"");
        }
    }

    /** NaN and infinities have no SNBT spelling; they become 0. */
    private static String finite(double d)
    {
        return Double.isFinite(d) ? String.valueOf(d) : "0.0";
    }

    private static void quote(StringBuilder sb, String s)
    {
        sb.append('"');

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n', '\r' -> sb.append(' ');
                default -> sb.append(c);
            }
        }

        sb.append('"');
    }
}
