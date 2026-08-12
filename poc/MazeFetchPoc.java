// Feasibility spike (Phase 1) — proves the Java side of the shared Litemazica
// API: fetch a maze by share code from the Worker's /api/generate endpoint and
// fully parse the returned .litematic (gzipped Java NBT) into palette + packed
// block data + block entities + placement metadata. This is deliberately a
// single self-contained file with a hand-rolled NBT reader so it runs with
// `java MazeFetchPoc.java` and no dependencies — the real plugin will lift this
// reader (or swap in a maintained NBT lib) and add block placement on top.
//
// Usage:  java MazeFetchPoc.java "<shareCode>" [baseUrl]
//   baseUrl defaults to http://localhost:8787

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class MazeFetchPoc {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java MazeFetchPoc.java <shareCode> [baseUrl]");
            System.exit(2);
        }
        String code = args[0];
        String baseUrl = args.length > 1 ? args[1] : "http://localhost:8787";
        String url = baseUrl + "/api/generate?s=" + code;

        System.out.println("GET " + url);
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        System.out.println("HTTP " + resp.statusCode());
        if (resp.statusCode() != 200) {
            System.out.println("body: " + new String(resp.body()));
            System.exit(1);
        }

        // --- Placement metadata (what the plugin needs to anchor the maze) ---
        System.out.println("\n== metadata headers ==");
        printHeader(resp, "x-litemazica-name");
        printHeader(resp, "x-litemazica-data-version");
        printHeader(resp, "x-litemazica-size");
        printHeader(resp, "x-litemazica-origin-y");
        printHeader(resp, "x-litemazica-entrance");
        printHeader(resp, "x-litemazica-blocks");
        printHeader(resp, "x-litemazica-command-blocks");

        byte[] body = resp.body();
        System.out.println("\nbody bytes: " + body.length
                + " (gzip magic " + String.format("%02x %02x", body[0] & 0xff, body[1] & 0xff) + ")");

        // --- Parse the .litematic (gunzip -> NBT) ---
        byte[] nbtBytes = gunzip(body);
        Object root = new NbtReader(nbtBytes).readRoot();
        Map<?, ?> rootC = (Map<?, ?>) root;

        System.out.println("\n== litematic root ==");
        System.out.println("Version           = " + rootC.get("Version"));
        System.out.println("MinecraftDataVer  = " + rootC.get("MinecraftDataVersion"));

        Map<?, ?> meta = (Map<?, ?>) rootC.get("Metadata");
        System.out.println("Metadata.Name     = " + meta.get("Name"));
        System.out.println("Metadata.TotalBlocks = " + meta.get("TotalBlocks"));
        System.out.println("Metadata.TotalVolume = " + meta.get("TotalVolume"));

        Map<?, ?> regions = (Map<?, ?>) rootC.get("Regions");
        String regionName = regions.keySet().iterator().next().toString();
        Map<?, ?> region = (Map<?, ?>) regions.get(regionName);
        System.out.println("\n== region \"" + regionName + "\" ==");

        Map<?, ?> size = (Map<?, ?>) region.get("Size");
        int sx = (Integer) size.get("x"), sy = (Integer) size.get("y"), sz = (Integer) size.get("z");
        System.out.println("Size              = " + sx + " x " + sy + " x " + sz + "  (volume " + (long) sx * sy * sz + ")");

        List<?> palette = (List<?>) region.get("BlockStatePalette");
        System.out.println("Palette size      = " + palette.size());
        int show = Math.min(10, palette.size());
        for (int i = 0; i < show; i++) {
            Map<?, ?> ps = (Map<?, ?>) palette.get(i);
            Object props = ps.get("Properties");
            System.out.println("  [" + i + "] " + ps.get("Name") + (props != null ? " " + props : ""));
        }

        List<?> tileEntities = (List<?>) region.get("TileEntities");
        System.out.println("TileEntities      = " + tileEntities.size());
        for (Object teO : tileEntities) {
            Map<?, ?> te = (Map<?, ?>) teO;
            System.out.println("  @(" + te.get("x") + "," + te.get("y") + "," + te.get("z") + ") id=" + te.get("id"));
        }

        // --- Prove the packed block data is decodable: unpack & sample voxels ---
        long[] packed = (long[]) region.get("BlockStates");
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
        long volume = (long) sx * sy * sz;
        System.out.println("\n== block data ==");
        System.out.println("BlockStates longs = " + packed.length + "  (" + bits + " bits/block)");

        long nonAir = 0;
        List<String> sample = new ArrayList<>();
        for (long i = 0; i < volume; i++) {
            int idx = unpack(packed, bits, i);
            if (idx != 0) { // palette index 0 is always air
                nonAir++;
                if (sample.size() < 6) {
                    int x = (int) (i % sx);
                    int z = (int) ((i / sx) % sz);
                    int y = (int) (i / ((long) sx * sz));
                    Map<?, ?> ps = (Map<?, ?>) palette.get(idx);
                    sample.add("(" + x + "," + y + "," + z + ")=" + ps.get("Name"));
                }
            }
        }
        System.out.println("non-air voxels    = " + nonAir + "  (header said " + resp.headers().firstValue("x-litemazica-blocks").orElse("?") + ")");
        System.out.println("first placements  = " + sample);

        boolean match = String.valueOf(nonAir).equals(resp.headers().firstValue("x-litemazica-blocks").orElse(""));
        System.out.println("\nRESULT: round-trip " + (match ? "VERIFIED - decoded block count matches the header" : "MISMATCH - decoded count != header"));
        System.exit(match ? 0 : 1);
    }

    private static void printHeader(HttpResponse<?> resp, String name) {
        System.out.println("  " + name + " = " + resp.headers().firstValue(name).orElse("(absent)"));
    }

    /** Litematica bit-packing: fixed bits/block, values may straddle two longs. */
    private static int unpack(long[] data, int bits, long index) {
        long bitPos = index * bits;
        int startLong = (int) (bitPos >> 6);
        int startBit = (int) (bitPos & 63);
        int endLong = (int) (((index + 1) * bits - 1) >> 6);
        long mask = (1L << bits) - 1;
        if (startLong == endLong) {
            return (int) ((data[startLong] >>> startBit) & mask);
        }
        int bitsFromStart = 64 - startBit;
        return (int) (((data[startLong] >>> startBit) | (data[endLong] << bitsFromStart)) & mask);
    }

    private static byte[] gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    /** Minimal big-endian Java-NBT reader covering every tag the writer emits. */
    static final class NbtReader {
        private final DataInputStream in;

        NbtReader(byte[] bytes) {
            this.in = new DataInputStream(new ByteArrayInputStream(bytes));
        }

        Object readRoot() throws Exception {
            int type = in.readByte();
            if (type == 0) return null;
            in.readUTF(); // root name (empty)
            return readPayload(type);
        }

        private Object readPayload(int type) throws Exception {
            switch (type) {
                case 1: return in.readByte();
                case 2: return in.readShort();
                case 3: return in.readInt();
                case 4: return in.readLong();
                case 5: return in.readFloat();
                case 6: return in.readDouble();
                case 7: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
                case 8: return in.readUTF();
                case 9: { // list
                    int elem = in.readByte();
                    int len = in.readInt();
                    List<Object> list = new ArrayList<>(Math.max(0, len));
                    for (int i = 0; i < len; i++) list.add(readPayload(elem));
                    return list;
                }
                case 10: { // compound
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (;;) {
                        int t = in.readByte();
                        if (t == 0) break; // TAG_End
                        String name = in.readUTF();
                        map.put(name, readPayload(t));
                    }
                    return map;
                }
                case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
                case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
                default: throw new IllegalStateException("unknown NBT tag id " + type);
            }
        }
    }
}
