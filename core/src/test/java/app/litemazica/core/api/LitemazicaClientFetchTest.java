package app.litemazica.core.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fetching against a stand-in API on localhost: which requests reach the API
 * once a layout is cached, what the left-out-builds header becomes, and what
 * an operator is told when the API turns a maze down.
 */
class LitemazicaClientFetchTest
{
    private static final List<String> PALETTE = List.of("minecraft:air", "minecraft:stone_bricks");

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private volatile int status = 200;
    private volatile String errorBody = "";

    @BeforeEach
    void start() throws IOException
    {
        byte[] maze = LitematicFixture.gzipped("Test Maze", 3465, 4, 2, 1, 2, PALETTE,
                LitematicFixture.pack(new int[]{1, 1, 1, 1}, 2));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange ->
        {
            synchronized (requests)
            {
                requests.add(exchange.getRequestURI().getQuery());
            }
            byte[] body = status == 200 ? maze : errorBody.getBytes(StandardCharsets.UTF_8);
            if (status == 200)
            {
                exchange.getResponseHeaders().add("X-Litemazica-Entrance", "1,0,0");
                exchange.getResponseHeaders().add("X-Litemazica-Skipped-Builds", "2");
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop()
    {
        server.stop(0);
    }

    private LitemazicaClient client()
    {
        return new LitemazicaClient("http://127.0.0.1:" + server.getAddress().getPort(), 5);
    }

    @Test
    void fetchesAMazeOwnLayoutOnceThenReadsItFromTheCache(@TempDir File dir) throws Exception
    {
        LitemazicaClient client = client();
        client.useLayoutCache(new LayoutCache(dir, Set::of, Logger.getLogger("test")));

        MazeSchematic first = client.fetch("CODE");
        MazeSchematic again = client.fetch("CODE");

        assertEquals(1, requests.size(), "the second fetch of the same layout didn't reach the API");
        assertEquals(1, again.entranceX(), "headers come back from the cache too");
        assertEquals(first.blockCount(), again.blockCount());

        // A fresh seed is a new maze: always the API, and never cached.
        client.fetch("CODE", "fresh1");
        client.fetch("CODE", "fresh1");
        assertEquals(3, requests.size());
        assertTrue(requests.get(1).contains("seed=fresh1"));
    }

    @Test
    void withoutACacheEveryFetchAsksTheApi() throws Exception
    {
        LitemazicaClient client = client();
        client.fetch("CODE");
        client.fetch("CODE");
        assertEquals(2, requests.size());
    }

    @Test
    void reportsCustomBuildsTheServerLeftOut() throws Exception
    {
        assertEquals(2, client().fetch("CODE").skippedBuilds());
    }

    @Test
    void saysPlainlyWhenItCantReachTheApiAtAll() throws Exception
    {
        int closed;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0))
        {
            closed = probe.getLocalPort();
        }
        LitemazicaClient client = new LitemazicaClient("http://127.0.0.1:" + closed, 5);
        IOException e = assertThrows(IOException.class, () -> client.fetch("CODE"));
        assertEquals("Couldn't reach the Litemazica API at http://127.0.0.1:" + closed
                + ". Check api-base-url in the config, and that this server can reach it.", e.getMessage());
    }

    @Test
    void passesOnTheApisOwnExplanationWhenItTurnsAMazeDown()
    {
        status = 413;
        errorBody = "{\"error\":\"That maze is too large to build on the server.\"}";
        IOException e = assertThrows(IOException.class, () -> client().fetch("CODE"));
        assertEquals("That maze is too large to build on the server.", e.getMessage());
    }
}
