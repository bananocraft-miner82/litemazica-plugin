package app.litemazica.core.platform;

/** Read-only settings, however the platform stores them (YAML, JSON, TOML). */
public interface ConfigSource
{
    String getString(String key, String fallback);

    int getInt(String key, int fallback);

    long getLong(String key, long fallback);

    boolean getBoolean(String key, boolean fallback);
}
