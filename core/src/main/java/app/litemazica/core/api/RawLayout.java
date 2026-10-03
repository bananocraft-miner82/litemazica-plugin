package app.litemazica.core.api;

import java.util.Map;

/**
 * A maze as the API sent it, before parsing: the gzipped {@code .litematic}
 * and its {@code x-litemazica-*} headers (names in lower case). What the
 * {@link LayoutCache} keeps, so a cached maze parses exactly like a fresh one.
 */
public record RawLayout(byte[] body, Map<String, String> headers)
{
    public RawLayout
    {
        headers = Map.copyOf(headers);
    }

    /** A header's value, or {@code fallback} when it wasn't sent. */
    public String header(String name, String fallback)
    {
        String value = headers.get(name);
        return value != null ? value : fallback;
    }
}
