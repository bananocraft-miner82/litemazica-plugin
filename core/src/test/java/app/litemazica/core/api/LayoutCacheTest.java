package app.litemazica.core.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The on-disk copy of each maze's own layout: what it keeps, what it refuses, and what it clears out. */
class LayoutCacheTest
{
    private static final Logger LOG = Logger.getLogger("test");

    private static RawLayout layout(String marker)
    {
        return new RawLayout(new byte[]{1, 2, 3, (byte) marker.length()},
                Map.of("x-litemazica-entrance", "4,0,0", "x-litemazica-name", marker));
    }

    @Test
    void keepsALayoutAndItsHeadersForItsShareCode(@TempDir File dir)
    {
        LayoutCache cache = new LayoutCache(dir, Set::of, LOG);
        cache.put("CODE-A", layout("Alpha"));

        RawLayout back = cache.get("CODE-A");
        assertNotNull(back);
        assertArrayEquals(new byte[]{1, 2, 3, 5}, back.body());
        assertEquals("4,0,0", back.header("x-litemazica-entrance", ""));
        assertEquals("Alpha", back.header("x-litemazica-name", ""));
        assertNull(cache.get("CODE-B"), "another code has nothing cached");
    }

    @Test
    void treatsAHalfWrittenOrDamagedEntryAsMissing(@TempDir File dir) throws Exception
    {
        LayoutCache cache = new LayoutCache(dir, Set::of, LOG);
        cache.put("CODE-A", layout("Alpha"));
        String key = LayoutCache.key("CODE-A");

        // Without its details file (a store cut short), the layout isn't used.
        Files.delete(new File(dir, key + ".properties").toPath());
        assertNull(cache.get("CODE-A"));

        // Details naming a different code (a hash clash) aren't used either.
        cache.put("CODE-A", layout("Alpha"));
        File meta = new File(dir, key + ".properties");
        Files.writeString(meta.toPath(), Files.readString(meta.toPath()).replace("code=CODE-A", "code=SOMETHING-ELSE"));
        assertNull(cache.get("CODE-A"));
    }

    @Test
    void clearsOutLayoutsNoPlacedMazeUses(@TempDir File dir)
    {
        AtomicReference<Set<String>> live = new AtomicReference<>(Set.of());
        LayoutCache cache = new LayoutCache(dir, live::get, LOG);

        cache.put("OLD", layout("Old"));
        // The maze being placed isn't registered yet, but its own entry stays.
        assertNotNull(cache.get("OLD"));

        live.set(Set.of("KEPT"));
        cache.put("KEPT", layout("Kept"));
        assertNull(cache.get("OLD"), "a code no maze uses is cleared on the next store");
        assertNotNull(cache.get("KEPT"));

        live.set(Set.of("KEPT"));
        cache.put("NEW", layout("New"));
        assertNotNull(cache.get("KEPT"), "codes placed mazes use are kept");
        assertNotNull(cache.get("NEW"));
    }
}
