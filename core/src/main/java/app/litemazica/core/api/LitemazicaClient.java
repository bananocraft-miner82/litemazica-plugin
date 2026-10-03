package app.litemazica.core.api;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
 * <p>With a {@link LayoutCache} attached ({@link #useLayoutCache}), a share
 * code's own layout is fetched once and then read from disk, so only fresh
 * layouts cost an API call.
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
    private volatile LayoutCache layouts;

    public LitemazicaClient(String baseUrl, int timeoutSeconds)
    {
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(this.timeout);

        // Over plain http (a local test API), ask for HTTP/1.1 outright. Otherwise
        // Java asks to upgrade to HTTP/2 ("Upgrade: h2c"), which Cloudflare's local
        // Workers runtime rejects (it crashed the Vite dev server outright). Over
        // https, HTTP/2 is negotiated without that header, so it's left alone.
        if (this.baseUrl.startsWith("http://"))
        {
            builder.version(HttpClient.Version.HTTP_1_1);
        }

        this.http = builder.build();
    }

    public String baseUrl()
    {
        return baseUrl;
    }

    /** Keep each share code's own layout on disk from now on (null stops caching). */
    public void useLayoutCache(LayoutCache cache)
    {
        this.layouts = cache;
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
        // The code's own layout never changes, so it can come from the cache.
        boolean ownLayout = seedOverride == null || seedOverride.isBlank();
        LayoutCache cache = ownLayout ? layouts : null;

        if (cache != null)
        {
            RawLayout cached = cache.get(shareCode);

            if (cached != null)
            {
                try
                {
                    return parse(cached);
                }
                catch (IOException unreadable)
                {
                    // Fall through and fetch it again (which replaces the bad copy).
                }
            }
        }

        RawLayout raw = download(shareCode, ownLayout ? null : seedOverride);
        MazeSchematic maze = parse(raw);

        if (cache != null)
        {
            cache.put(shareCode, raw);
        }

        return maze;
    }

    /** The API's maze for a share code (and optional seed), unparsed. */
    private RawLayout download(String shareCode, String seedOverride) throws IOException, InterruptedException
    {
        String url = baseUrl + "/api/generate?s=" + URLEncoder.encode(shareCode, StandardCharsets.UTF_8) + "&reset=off";

        if (seedOverride != null)
        {
            url += "&seed=" + URLEncoder.encode(seedOverride, StandardCharsets.UTF_8);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "Litemaziplugin")
                .GET()
                .build();

        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200)
        {
            throw new IOException(describeFailure(response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8), baseUrl));
        }

        Map<String, String> headers = new TreeMap<>();
        response.headers().map().forEach((name, values) ->
        {
            String lower = name.toLowerCase(Locale.ROOT);

            if (lower.startsWith("x-litemazica-") && !values.isEmpty())
            {
                headers.put(lower, values.get(0));
            }
        });
        return new RawLayout(response.body(), headers);
    }

    /**
     * Sends a request, turning "couldn't connect" and "took too long" into
     * messages an operator can act on (a refused connection otherwise surfaces
     * as a bare "ConnectException", with no message of its own).
     */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) throws IOException, InterruptedException
    {
        try
        {
            return http.send(request, body);
        }
        catch (HttpTimeoutException e)
        {
            throw new IOException("The Litemazica API at " + baseUrl + " took too long to answer. Try again later.", e);
        }
        catch (ConnectException | UnknownHostException e)
        {
            throw new IOException("Couldn't reach the Litemazica API at " + baseUrl
                    + ". Check api-base-url in the config, and that this server can reach it.", e);
        }
    }

    /**
     * What to tell an operator when the API turned a maze request down: the
     * API's own explanation where it gave one (its messages are written for
     * people), otherwise a plain description of the HTTP status. Cloudflare
     * answers a request that ran out of time with error 1102, so that gets its
     * own advice.
     */
    static String describeFailure(int status, String body, String baseUrl)
    {
        String text = body == null ? "" : body;
        String apiError = jsonString(text, "error");

        if (status == 429)
        {
            return "The Litemazica API has had too many requests from this server. Try again in a few minutes.";
        }

        if (text.contains("1102") || text.contains("exceeded resource limits"))
        {
            return "The Litemazica API ran out of time building this maze; it may be too large to build on the server."
                    + " Try again later, or download it from the web app and place the file with /litemazica place.";
        }

        if (apiError != null && !apiError.isBlank())
        {
            return apiError;
        }

        if (status == 404)
        {
            return "There's no Litemazica API at " + baseUrl + ". Check api-base-url in the config.";
        }

        if (status >= 500)
        {
            return "The Litemazica API is having trouble right now (HTTP " + status + "). Try again later.";
        }

        return "The Litemazica API turned that request down (HTTP " + status + ").";
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

        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
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

        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());

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

    private MazeSchematic parse(RawLayout response) throws IOException
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
        // Custom builds the design names that the server no longer has.
        int skippedBuilds = headerInt(response, "x-litemazica-skipped-builds", 0);

        try
        {
            return new MazeSchematic(
                    name, dataVersion, region.sizeX(), region.sizeY(), region.sizeZ(), originY,
                    entrance[0], entrance[1], entrance[2], blockCount, commandBlocks,
                    region.palette(), region.blockStates(), region.tileEntities(), region.entities(), clearAbove, ceilingBlock,
                    skippedBuilds);
        }
        catch (IllegalArgumentException e)
        {
            // Turn a malformed response into the normal fetch-failure path, so
            // the player gets a message instead of an unchecked exception.
            throw new IOException("malformed .litematic: " + e.getMessage(), e);
        }
    }

    // ── header helpers ──────────────────────────────────────────────────────

    private static String header(RawLayout response, String name, String fallback)
    {
        return response.header(name, fallback);
    }

    private static int headerInt(RawLayout response, String name, int fallback)
    {
        try
        {
            return Integer.parseInt(response.header(name, "").trim());
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

    static String stripTrailingSlash(String url)
    {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
