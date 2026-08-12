package app.litemazica.core.maze;

import app.litemazica.core.platform.ConfigSource;

import java.util.HashMap;
import java.util.Map;

/** A map-backed {@link ConfigSource} for tests; unset keys take the caller's fallback. */
final class FakeConfig implements ConfigSource
{
    private final Map<String, String> values = new HashMap<>();

    FakeConfig set(String key, Object value)
    {
        values.put(key, String.valueOf(value));
        return this;
    }

    @Override
    public String getString(String key, String fallback)
    {
        return values.getOrDefault(key, fallback);
    }

    @Override
    public int getInt(String key, int fallback)
    {
        try
        {
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    @Override
    public long getLong(String key, long fallback)
    {
        try
        {
            return values.containsKey(key) ? Long.parseLong(values.get(key)) : fallback;
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean fallback)
    {
        return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : fallback;
    }
}
