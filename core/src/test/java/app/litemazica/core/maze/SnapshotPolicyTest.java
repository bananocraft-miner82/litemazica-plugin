package app.litemazica.core.maze;

import app.litemazica.core.platform.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The snapshot policy: defaults, config overrides, and where a maze's snapshot
 * folder lands.
 */
class SnapshotPolicyTest
{
    @Test
    void defaultsMatchTheDocumentedGuarantee(@TempDir File data)
    {
        SnapshotPolicy policy = new SnapshotPolicy(config(Map.of(), Map.of()), data);

        assertEquals(true, policy.enabled(), "snapshots default on");
        // The default must equal the largest buildable maze, so everything is
        // restorable unless the admin lowers it.
        assertEquals(6_000_000L, policy.maxVolume());
    }

    @Test
    void configOverridesTheDefaults(@TempDir File data)
    {
        SnapshotPolicy policy = new SnapshotPolicy(
                config(Map.of("snapshot", false), Map.of("snapshot-max-volume", 1_000L)), data);

        assertFalse(policy.enabled());
        assertEquals(1_000L, policy.maxVolume());
    }

    @Test
    void snapshotFolderIsUnderTheDataFolder(@TempDir File data)
    {
        SnapshotPolicy policy = new SnapshotPolicy(config(Map.of(), Map.of()), data);

        assertEquals(new File(new File(data, "snapshots"), "arena"), policy.dir("arena"));
    }

    @Test
    void hasIsFalseWhenDisabledOrWhenNoSnapshotWasWritten(@TempDir File data)
    {
        assertFalse(new SnapshotPolicy(config(Map.of(), Map.of()), data).has("arena"),
                "no folder written yet");
        assertFalse(new SnapshotPolicy(config(Map.of("snapshot", false), Map.of()), data).has("arena"),
                "off, so never reports a snapshot");
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private static ConfigSource config(Map<String, Boolean> bools, Map<String, Long> longs)
    {
        Map<String, Boolean> b = new HashMap<>(bools);
        Map<String, Long> l = new HashMap<>(longs);
        return new ConfigSource()
        {
            @Override
            public String getString(String key, String fallback)
            {
                return fallback;
            }

            @Override
            public int getInt(String key, int fallback)
            {
                return fallback;
            }

            @Override
            public long getLong(String key, long fallback)
            {
                return l.getOrDefault(key, fallback);
            }

            @Override
            public boolean getBoolean(String key, boolean fallback)
            {
                return b.getOrDefault(key, fallback);
            }
        };
    }
}
