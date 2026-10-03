package app.litemazica.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure helpers behind the client: base-URL normalisation, the hand-rolled
 * JSON field extraction used for the editor handshake, and the entrance triple.
 * The network methods themselves are exercised by the manual checklist.
 */
class LitemazicaClientTest
{
    @Test
    void baseUrlIsTrimmedAndHasNoTrailingSlash()
    {
        assertEquals("https://litemazica.app", new LitemazicaClient("https://litemazica.app/", 20).baseUrl());
        assertEquals("https://litemazica.app", new LitemazicaClient("  https://litemazica.app  ", 20).baseUrl());
        assertEquals("", new LitemazicaClient(null, 20).baseUrl());
    }

    @Test
    void jsonStringPullsATopLevelField()
    {
        String body = "{\"token\":\"abc123\",\"url\":\"https://x/e/abc\",\"expiresInSeconds\":3600}";

        assertEquals("abc123", LitemazicaClient.jsonString(body, "token"));
        assertEquals("https://x/e/abc", LitemazicaClient.jsonString(body, "url"));
        assertNull(LitemazicaClient.jsonString(body, "missing"), "an absent field is null");
        assertNull(LitemazicaClient.jsonString(null, "token"), "null body is null");
    }

    @Test
    void jsonIntPullsANumberOrFallsBack()
    {
        String body = "{\"expiresInSeconds\": 900, \"status\":\"ready\"}";

        assertEquals(900, LitemazicaClient.jsonInt(body, "expiresInSeconds", 3600));
        assertEquals(3600, LitemazicaClient.jsonInt(body, "absent", 3600), "missing → fallback");
        assertEquals(42, LitemazicaClient.jsonInt("{\"expiresInSeconds\":\"nope\"}", "expiresInSeconds", 42),
                "a non-numeric value isn't matched, so the fallback stands");
    }

    @Test
    void parseTripleReadsEntranceCoordinatesAndDefaultsMissingOnesToZero()
    {
        assertArrayEquals(new int[]{1, 2, 3}, LitemazicaClient.parseTriple("1,2,3"));
        assertArrayEquals(new int[]{-4, 0, 5}, LitemazicaClient.parseTriple(" -4 , 0 , 5 "), "whitespace tolerated");
        assertArrayEquals(new int[]{7, 0, 0}, LitemazicaClient.parseTriple("7"), "short input pads with zeros");
        assertArrayEquals(new int[]{0, 0, 0}, LitemazicaClient.parseTriple("a,b,c"), "garbage → zeros, not a throw");
    }

    @Test
    void describesAFailedRequestInPlainWords()
    {
        String base = "https://litemazica.app";

        assertEquals("That maze is too large.",
                LitemazicaClient.describeFailure(413, "{\"error\":\"That maze is too large.\"}", base),
                "the API's own explanation is passed on");
        assertTrue(LitemazicaClient.describeFailure(429, "", base).contains("too many requests"));
        assertTrue(LitemazicaClient.describeFailure(503, "<html>Error 1102 Worker exceeded resource limits</html>", base)
                .contains("ran out of time"), "Cloudflare's out-of-CPU page gets its own advice");
        assertTrue(LitemazicaClient.describeFailure(404, "<html>not found</html>", base).contains("api-base-url"));
        assertTrue(LitemazicaClient.describeFailure(502, "", base).contains("HTTP 502"));
        assertTrue(LitemazicaClient.describeFailure(418, null, base).contains("HTTP 418"));
    }

    @Test
    void stripTrailingSlashOnlyTouchesATrailingSlash()
    {
        assertEquals("http://x", LitemazicaClient.stripTrailingSlash("http://x/"));
        assertEquals("http://x", LitemazicaClient.stripTrailingSlash("http://x"));
        assertEquals("http://x/a", LitemazicaClient.stripTrailingSlash("http://x/a"));
    }
}
