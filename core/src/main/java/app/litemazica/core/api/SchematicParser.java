package app.litemazica.core.api;

import app.litemazica.core.nbt.NbtReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Decodes the raw NBT of a {@code .litematic} — whether it arrived from the
 * Litemazica API or was dropped on disk by a server admin — into the facts a
 * region actually records: its size, block palette, packed block states, and
 * block entities, plus the handful of metadata fields the format itself stores.
 *
 * <p>The fields a raw file doesn't carry (paste origin, entrance, command-block
 * count) are the caller's to supply — the API reads them from response headers,
 * a file source defaults them from the geometry — so this class stops at the
 * {@link ParsedRegion} and leaves assembling a {@link MazeSchematic} to them.
 *
 * <p>Deliberately dependency-free: pairs with {@link NbtReader} so the plugin
 * needs no shaded NBT library.
 */
public final class SchematicParser
{
    private SchematicParser()
    {
    }

    /**
     * What a single {@code .litematic} region records. Everything here comes from
     * the file; the name/dataVersion/totalBlocks fields may be absent (null / 0),
     * in which case the caller supplies a fallback.
     */
    public record ParsedRegion(
            String name, int dataVersion, int totalBlocks,
            int sizeX, int sizeY, int sizeZ,
            List<String> palette, long[] blockStates, List<Map<String, Object>> tileEntities,
            int regionCount)
    {
    }

    /**
     * Parses (optionally gzipped) {@code .litematic} bytes into the first
     * region's facts. Litematica always gzips, but an admin who decompressed a
     * file by hand still gets a clean parse — the gzip magic is sniffed, not
     * assumed.
     */
    public static ParsedRegion parse(byte[] bytes) throws IOException
    {
        Object rootTag = new NbtReader(isGzip(bytes) ? gunzip(bytes) : bytes).readRoot();

        if (!(rootTag instanceof Map))
        {
            throw new IOException("unexpected .litematic content (root is not a compound)");
        }

        Map<?, ?> root = (Map<?, ?>) rootTag;
        Map<?, ?> regions = asCompound(root.get("Regions"), "Regions");

        if (regions.isEmpty())
        {
            throw new IOException(".litematic has no regions");
        }

        Map<?, ?> region = asCompound(regions.values().iterator().next(), "region");

        Map<?, ?> size = asCompound(region.get("Size"), "Size");
        // Litematica records a size as a signed vector: a region built in the
        // -x/-y/-z direction stores a negative extent. The voxel count is the
        // magnitude, so normalise here rather than let a negative size reach the
        // MazeSchematic volume checks.
        int sizeX = Math.abs(asInt(size.get("x")));
        int sizeY = Math.abs(asInt(size.get("y")));
        int sizeZ = Math.abs(asInt(size.get("z")));

        List<String> palette = new ArrayList<>();

        for (Object entry : asList(region.get("BlockStatePalette"), "BlockStatePalette"))
        {
            palette.add(toBlockData(asCompound(entry, "palette entry")));
        }

        Object blockStatesTag = region.get("BlockStates");

        if (!(blockStatesTag instanceof long[]))
        {
            throw new IOException("BlockStates missing or not a long array");
        }

        long[] blockStates = (long[]) blockStatesTag;

        List<Map<String, Object>> tileEntities = new ArrayList<>();

        for (Object te : asList(region.get("TileEntities"), "TileEntities"))
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> teMap = (Map<String, Object>) asCompound(te, "tile entity");
            tileEntities.add(teMap);
        }

        return new ParsedRegion(
                metaName(root), asInt(root.get("MinecraftDataVersion")), metaTotalBlocks(root),
                sizeX, sizeY, sizeZ,
                palette, blockStates, tileEntities, regions.size());
    }

    // ── palette compound -> Bukkit block-data string ───────────────────────

    private static String toBlockData(Map<?, ?> state)
    {
        String name = String.valueOf(state.get("Name"));
        Object propsTag = state.get("Properties");

        if (!(propsTag instanceof Map) || ((Map<?, ?>) propsTag).isEmpty())
        {
            return name;
        }

        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;

        for (Map.Entry<?, ?> e : ((Map<?, ?>) propsTag).entrySet())
        {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }

        return sb.append(']').toString();
    }

    // ── small tag helpers ───────────────────────────────────────────────────

    private static Map<?, ?> asCompound(Object tag, String what) throws IOException
    {
        if (tag instanceof Map)
        {
            return (Map<?, ?>) tag;
        }

        throw new IOException("expected compound for " + what);
    }

    private static List<?> asList(Object tag, String what) throws IOException
    {
        if (tag instanceof List)
        {
            return (List<?>) tag;
        }

        throw new IOException("expected list for " + what);
    }

    private static int asInt(Object tag)
    {
        return tag instanceof Number ? ((Number) tag).intValue() : 0;
    }

    private static String metaName(Map<?, ?> root)
    {
        Object meta = root.get("Metadata");

        if (meta instanceof Map)
        {
            Object n = ((Map<?, ?>) meta).get("Name");

            if (n != null)
            {
                return String.valueOf(n);
            }
        }

        return null;
    }

    private static int metaTotalBlocks(Map<?, ?> root)
    {
        Object meta = root.get("Metadata");

        if (meta instanceof Map)
        {
            Object t = ((Map<?, ?>) meta).get("TotalBlocks");

            if (t instanceof Number)
            {
                return ((Number) t).intValue();
            }
        }

        return 0;
    }

    // ── decompression ──────────────────────────────────────────────────────

    /**
     * Inflates with a ceiling. {@code readAllBytes} would happily expand a few
     * kilobytes into gigabytes and take the server with it — and the maze's own
     * size limit isn't checked until after parsing, far too late to help. Well
     * above any real maze: the largest the API will build is a few megabytes.
     */
    static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;

    private static boolean isGzip(byte[] b)
    {
        return b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }

    static byte[] gunzip(byte[] gz) throws IOException
    {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz)))
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, Math.min(gz.length * 4, 1 << 20)));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;

            while ((read = in.read(buffer)) > 0)
            {
                total += read;

                if (total > MAX_DECOMPRESSED_BYTES)
                {
                    throw new IOException("response expanded past " + MAX_DECOMPRESSED_BYTES
                            + " bytes — refusing to decompress further");
                }

                out.write(buffer, 0, read);
            }

            return out.toByteArray();
        }
    }
}
