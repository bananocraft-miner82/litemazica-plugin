package app.litemazica.core.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the Litemazica API ({@code GET /api/generate?s=<code>}), which runs
 * the same maze generator as the web app and returns a gzipped .litematic plus
 * placement metadata in {@code x-litemazica-*} headers. This class fetches,
 * gunzips, and parses that into a ready-to-place {@link MazeSchematic}.
 *
 * <p>{@link #fetch(String)} blocks on network I/O — always call it off the main
 * server thread (e.g. from an async scheduler task).
 *
 * <p>Non-final only so tests can subclass it to stand in for the network (there
 * is no other implementation in production); its methods are the seam the maze
 * services talk to.
 */
public class LitemazicaClient
{
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;

    public LitemazicaClient(String baseUrl, int timeoutSeconds)
    {
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public String baseUrl()
    {
        return baseUrl;
    }

    /** Fetches and parses a maze by share code. Blocking — call off the main thread. */
    public MazeSchematic fetch(String shareCode) throws IOException, InterruptedException
    {
        return fetch(shareCode, null);
    }

    /**
     * As {@link #fetch(String)}, but overriding the maze seed — pass a fresh
     * random seed to get a new layout in the same footprint (scheduled regen).
     * Always requests {@code reset=off}: the plugin regenerates mazes itself, so
     * the app's command-block reset station would just be dead blocks.
     */
    public MazeSchematic fetch(String shareCode, String seedOverride) throws IOException, InterruptedException
    {
        String url = baseUrl + "/api/generate?s=" + URLEncoder.encode(shareCode, StandardCharsets.UTF_8) + "&reset=off";

        if (seedOverride != null && !seedOverride.isBlank())
        {
            url += "&seed=" + URLEncoder.encode(seedOverride, StandardCharsets.UTF_8);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "Litemaziplugin")
                .GET()
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200)
        {
            throw new IOException("API returned HTTP " + response.statusCode() + ": " + snippet(response.body()));
        }

        return parse(response);
    }

    // ── editor handshake ───────────────────────────────────────────────────

    /** A minted editor session: the token to poll on, and the URL to send the player to. */
    public record EditorSession(String token, String url, int expiresInSeconds)
    {
    }

    /** A poll result: status is "pending", "ready" (with code), or "expired". */
    public record EditorPoll(String status, String code)
    {
    }

    /** Opens an empty editor session on the server. Blocking — call off the main thread. */
    public EditorSession newEditorSession() throws IOException, InterruptedException
    {
        return newEditorSession(null);
    }

    /**
     * Opens an editor session, optionally seeded with an existing maze's design
     * code so the browser opens on that maze for re-editing. Blocking — call off
     * the main thread.
     *
     * @param seedCode a share code to preload, or null/blank for a blank editor.
     */
    public EditorSession newEditorSession(String seedCode) throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/editor/new"))
                .timeout(timeout)
                .header("User-Agent", "Litemaziplugin");

        if (seedCode == null || seedCode.isBlank())
        {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        else
        {
            // Share codes are base64url (no quote/backslash), so this hand-built
            // JSON needs no escaping — same assumption jsonString relies on.
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"seed\":\"" + seedCode + "\"}"));
        }

        HttpRequest request = builder.build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        if (response.statusCode() != 200)
        {
            throw new IOException(firstNonBlank(jsonString(body, "error"),
                    "editor session request returned HTTP " + response.statusCode()));
        }

        String token = jsonString(body, "token");

        if (token == null)
        {
            throw new IOException("editor session response had no token");
        }

        return new EditorSession(token, jsonString(body, "url"), jsonInt(body, "expiresInSeconds", 3600));
    }

    /** Polls an editor session once. Blocking — call off the main thread. */
    public EditorPoll pollEditor(String token) throws IOException, InterruptedException
    {
        String url = baseUrl + "/api/editor/poll?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "Litemaziplugin")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        // A transient error (5xx, gateway hiccup) shouldn't kill the session —
        // report "pending" so the caller simply tries again next tick.
        if (response.statusCode() != 200)
        {
            return new EditorPoll("pending", null);
        }

        String status = firstNonBlank(jsonString(response.body(), "status"), "pending");
        return new EditorPoll(status, jsonString(response.body(), "code"));
    }

    /**
     * Extracts a top-level string field from a small, known JSON object. The
     * fields we read (token, url, status, code, error) never contain a quote or
     * backslash — share codes are base64url — so a full JSON parser (and a shaded
     * dependency for it) would be overkill.
     */
    static String jsonString(String json, String key)
    {
        if (json == null)
        {
            return null;
        }

        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static int jsonInt(String json, String key, int fallback)
    {
        if (json == null)
        {
            return fallback;
        }

        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private MazeSchematic parse(HttpResponse<byte[]> response) throws IOException
    {
        SchematicParser.ParsedRegion region = SchematicParser.parse(response.body());

        // Placement metadata that isn't in a standard region rides in headers;
        // name/dataVersion/blockCount prefer the file's own metadata when present.
        String name = firstNonBlank(region.name(), header(response, "x-litemazica-name", "Maze"));
        int dataVersion = region.dataVersion() != 0
                ? region.dataVersion()
                : headerInt(response, "x-litemazica-data-version", 0);
        int originY = headerInt(response, "x-litemazica-origin-y", 0);
        // Open-top mazes carry how far to blend into the terrain above them; the
        // editor only sends a non-zero value when no ceiling was selected.
        int clearAbove = headerInt(response, "x-litemazica-clear-above", 0);
        // The material a buried section is capped with — the maze's own ceiling.
        String ceilingBlock = header(response, "x-litemazica-ceiling", "minecraft:stone_bricks");
        int commandBlocks = headerInt(response, "x-litemazica-command-blocks", 0);
        int blockCount = region.totalBlocks() != 0
                ? region.totalBlocks()
                : headerInt(response, "x-litemazica-blocks", 0);
        int[] entrance = parseTriple(header(response, "x-litemazica-entrance", "0,0,0"));

        try
        {
            return new MazeSchematic(
                    name, dataVersion, region.sizeX(), region.sizeY(), region.sizeZ(), originY,
                    entrance[0], entrance[1], entrance[2], blockCount, commandBlocks,
                    region.palette(), region.blockStates(), region.tileEntities(), clearAbove, ceilingBlock);
        }
        catch (IllegalArgumentException e)
        {
            // Turn a malformed response into the normal fetch-failure path, so
            // the player gets a message instead of an unchecked exception.
            throw new IOException("malformed .litematic: " + e.getMessage(), e);
        }
    }

    // ── header helpers ──────────────────────────────────────────────────────

    private static String header(HttpResponse<?> response, String name, String fallback)
    {
        return response.headers().firstValue(name).orElse(fallback);
    }

    private static int headerInt(HttpResponse<?> response, String name, int fallback)
    {
        try
        {
            return Integer.parseInt(response.headers().firstValue(name).orElse("").trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    static int[] parseTriple(String csv)
    {
        String[] parts = csv.split(",");
        int[] out = new int[3];

        for (int i = 0; i < 3 && i < parts.length; i++)
        {
            try
            {
                out[i] = Integer.parseInt(parts[i].trim());
            }
            catch (NumberFormatException ignored)
            {
                out[i] = 0;
            }
        }

        return out;
    }

    private static String firstNonBlank(String a, String b)
    {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String snippet(byte[] body)
    {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    static String stripTrailingSlash(String url)
    {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
