package dev.k9o;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-shape tests for {@code client.kibo().vault().secrets()} — the full family: list /
 * get / create / revealChallenge / reveal / update / rotate / move / delete. Mirrors
 * {@code packages/kibo/test/secrets.test.ts} (the TypeScript reference this Java surface was ported
 * from) — verifies URL/query/method/body/header, including:
 *  - {@code region} optional on every named-secret op (sent only when given)
 *  - {@code get} reads the list route, never {@code .../vault/secrets/<name>}
 *  - {@code value} base64-encoded on write, base64-decoded on reveal
 *  - the {@code X-K9O-Reveal-Challenge} header on reveal
 *
 * All requests hit a loopback JDK {@link HttpServer} mock (same harness as {@code RetryTest} /
 * {@code ParityTest}) — no real network calls.
 */
class KiboSecretsTest {

    HttpServer server;
    String baseUrl;
    final AtomicReference<String> lastMethod = new AtomicReference<>();
    final AtomicReference<String> lastPath = new AtomicReference<>();
    final AtomicReference<String> lastBody = new AtomicReference<>();
    final List<String> paths = new ArrayList<>();
    final AtomicReference<String> lastChallengeHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** Registers a 200-JSON mock at {@code path} (prefix-matches sub-paths, e.g. named-secret URLs). */
    void mock(String path, String json) {
        server.createContext(path, ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastPath.set(ex.getRequestURI().toString());
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastChallengeHeader.set(ex.getRequestHeaders().getFirst("X-K9O-Reveal-Challenge"));
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    /** Registers a mock answering successive requests with {@code bodies} in order (the last repeats),
     *  recording every request URI in {@link #paths}. */
    void mockSeq(String path, String... bodies) {
        final int[] n = {0};
        server.createContext(path, ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastPath.set(ex.getRequestURI().toString());
            paths.add(ex.getRequestURI().toString());
            ex.getRequestBody().readAllBytes();
            byte[] b = bodies[Math.min(n[0]++, bodies.length - 1)].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    static String page(String secretsJson, String cursor) {
        return "{\"data\":{\"secrets\":" + secretsJson + ",\"nextCursor\":" +
            (cursor == null ? "null" : "\"" + cursor + "\"") + "},\"meta\":{}}";
    }

    static String named(String name) {
        return "{\"id\":\"sec_abc\",\"name\":\"" + name + "\",\"version\":1}";
    }

    /** Registers a 204-empty-body mock (delete). */
    void mock204(String path) {
        server.createContext(path, ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastPath.set(ex.getRequestURI().toString());
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
    }

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("member-portal").build());
    }

    static final String SUMMARY_JSON =
        "{\"data\":{\"id\":\"sec_abc\",\"name\":\"db/pw\",\"ownerId\":\"usr_1\"," +
        "\"visibility\":{\"kind\":\"private\"},\"version\":1,\"kid\":\"vkey_1\"," +
        "\"createdAt\":\"t\",\"createdBy\":\"usr_1\",\"updatedAt\":\"t\",\"updatedBy\":\"usr_1\"," +
        "\"rotatedAt\":null,\"retiredAt\":null,\"metadata\":{}},\"meta\":{}}";

    @Test
    void listGetsSecretsWithPrefixCursorLimitNoRegion() {
        mock("/@member-portal/vault/secrets",
            "{\"data\":{\"secrets\":[{\"id\":\"sec_abc\",\"name\":\"db/pw\",\"ownerId\":\"usr_1\"," +
            "\"visibility\":{\"kind\":\"private\"},\"version\":1,\"kid\":\"vkey_1\",\"createdAt\":\"t\"," +
            "\"createdBy\":\"usr_1\",\"updatedAt\":\"t\",\"updatedBy\":\"usr_1\",\"rotatedAt\":null," +
            "\"retiredAt\":null,\"metadata\":{}}],\"nextCursor\":null},\"meta\":{}}");
        var env = client().kibo().vault().secrets()
            .list(new Kibo.Secrets.ListOpts("db/", null, 10));
        assertEquals("GET", lastMethod.get());
        assertFalse(lastPath.get().contains("region="), "list must NOT send region");
        assertTrue(lastPath.get().contains("prefix=db%2F"));
        assertTrue(lastPath.get().contains("limit=10"));
        assertEquals("sec_abc", env.data().secrets().get(0).id());
    }

    @Test
    void getReadsMetadataFromTheListRouteExactName() {
        mockSeq("/@member-portal/vault/secrets", page("[" + named("db/pw-old") + "," + named("db/pw") + "]", null));
        var env = client().kibo().vault().secrets().get("db/pw");
        assertEquals(1, paths.size());
        assertEquals("GET", lastMethod.get());
        assertEquals("/@member-portal/vault/secrets?prefix=db%2Fpw&limit=100", paths.get(0));
        assertEquals("db/pw", env.data().name());
        assertTrue(paths.stream().noneMatch(p -> p.startsWith("/@member-portal/vault/secrets/")));
    }

    @Test
    void getDoesNotSendRegionEvenWhenGiven() {
        mockSeq("/@member-portal/vault/secrets", page("[" + named("db/pw") + "]", null));
        client().kibo().vault().secrets().get("db/pw", "us01");
        assertFalse(lastPath.get().contains("region"));
    }

    @Test
    void getFollowsNextCursor() {
        mockSeq("/@member-portal/vault/secrets",
            page("[" + named("db/pw-old") + "]", "cur1"), page("[" + named("db/pw") + "]", null));
        var env = client().kibo().vault().secrets().get("db/pw");
        assertEquals(2, paths.size());
        assertTrue(paths.get(1).startsWith("/@member-portal/vault/secrets?"));
        assertTrue(paths.get(1).contains("prefix=db%2Fpw"));
        assertTrue(paths.get(1).contains("cursor=cur1"));
        assertTrue(paths.get(1).contains("limit=100"));
        assertEquals("db/pw", env.data().name());
    }

    @Test
    void getNotFoundIs404() {
        mockSeq("/@member-portal/vault/secrets", page("[" + named("db/pw-old") + "]", null));
        KError e = assertThrows(KError.class, () -> client().kibo().vault().secrets().get("db/pw"));
        assertEquals("K9O_NOT_FOUND", e.getCode());
        assertEquals(404, e.getStatus());
        assertEquals("No secret named 'db/pw' in this project.", e.getMessage());
        assertEquals(1, paths.size());
    }

    @Test
    void getStopsAfterTwentyPages() {
        mockSeq("/@member-portal/vault/secrets", page("[]", "again"));
        KError e = assertThrows(KError.class, () -> client().kibo().vault().secrets().get("db/pw"));
        assertEquals("K9O_NOT_FOUND", e.getCode());
        assertEquals(20, paths.size());
    }

    @Test
    void namedOperationsOmitRegionWhenNotGiven() {
        mockSeq("/@member-portal/vault/secrets",
            "{\"data\":{\"id\":\"sec_abc\",\"name\":\"db/pw\",\"version\":1,\"value\":\"eA==\"},\"meta\":{}}");
        var s = client().kibo().vault().secrets();
        String base = "/@member-portal/vault/secrets/db%2Fpw";
        s.create("db/pw", new Kibo.CreateSecretInput("x", null, null));
        assertEquals(base, lastPath.get());
        s.update("db/pw", new Kibo.UpdateSecretInput("x"));
        assertEquals(base, lastPath.get());
        s.revealChallenge("db/pw");
        assertEquals(base + "?op=reveal-challenge", lastPath.get());
        s.reveal("db/pw", "rvc_1");
        assertEquals(base, lastPath.get());
        s.rotate("db/pw");
        assertEquals(base + "?op=rotate", lastPath.get());
        s.move("db/pw", "z");
        assertEquals(base + "?op=move", lastPath.get());
        s.delete("db/pw");
        assertEquals("DELETE", lastMethod.get());
        assertEquals(base, lastPath.get());
    }

    @Test
    void deleteAcceptsAny2xx() {
        mockSeq("/@member-portal/vault/secrets", "{\"data\":null,\"meta\":{}}");
        var env = client().kibo().vault().secrets().delete("db/pw");
        assertNull(env.data());
    }

    @Test
    void createPostsWithRegionAndBase64Value() {
        mock("/@member-portal/vault/secrets", SUMMARY_JSON);
        var env = client().kibo().vault().secrets().create("db/pw", "us01",
            new Kibo.CreateSecretInput("hunter2", Kibo.SecretVisibility.privateVisibility(), null));
        assertEquals("POST", lastMethod.get());
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?region=us01", lastPath.get());
        String expected = Base64.getEncoder().encodeToString("hunter2".getBytes(StandardCharsets.UTF_8));
        assertTrue(lastBody.get().contains("\"value\":\"" + expected + "\""));
        assertFalse(lastBody.get().contains("\"hunter2\""), "wire value must be base64, not raw plaintext");
        assertEquals("sec_abc", env.data().id());
    }

    @Test
    void revealChallengePostsOpAndRegion() {
        mock("/@member-portal/vault/secrets",
            "{\"data\":{\"challenge\":\"rvc_1\",\"expiresAt\":\"t\"},\"meta\":{}}");
        var env = client().kibo().vault().secrets().revealChallenge("db/pw", "us01");
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().contains("op=reveal-challenge"));
        assertTrue(lastPath.get().contains("region=us01"));
        assertEquals("rvc_1", env.data().challenge());
    }

    @Test
    void revealGetsWithChallengeHeaderAndDecodesBase64() {
        String wireValue = Base64.getEncoder().encodeToString("hunter2".getBytes(StandardCharsets.UTF_8));
        mock("/@member-portal/vault/secrets",
            "{\"data\":{\"id\":\"sec_abc\",\"name\":\"db/pw\",\"version\":1,\"value\":\"" + wireValue + "\"," +
            "\"revealedAt\":\"t\",\"attestationTier\":{\"urn\":\"u\",\"operator\":\"platform\",\"verified\":true}}," +
            "\"meta\":{}}");
        var env = client().kibo().vault().secrets().reveal("db/pw", "us01", "rvc_1");
        assertEquals("GET", lastMethod.get());
        assertEquals("rvc_1", lastChallengeHeader.get());
        assertEquals("hunter2", env.data().value(), "reveal() must base64-decode the wire value");
        assertEquals("platform", env.data().attestationTier().operator());
        assertTrue(env.data().attestationTier().verified());
    }

    @Test
    void updatePutsWithRegionAndBase64ReturnsNarrowShape() {
        mock("/@member-portal/vault/secrets",
            "{\"data\":{\"id\":\"sec_abc\",\"name\":\"db/pw\",\"version\":2},\"meta\":{}}");
        var env = client().kibo().vault().secrets().update("db/pw", "us01",
            new Kibo.UpdateSecretInput("newpw"));
        assertEquals("PUT", lastMethod.get());
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?region=us01", lastPath.get());
        String expected = Base64.getEncoder().encodeToString("newpw".getBytes(StandardCharsets.UTF_8));
        assertTrue(lastBody.get().contains("\"value\":\"" + expected + "\""));
        assertEquals(2, env.data().version());
    }

    @Test
    void rotatePostsOpRotateNoBody() {
        mock("/@member-portal/vault/secrets",
            "{\"data\":{\"id\":\"sec_abc\",\"name\":\"db/pw\",\"version\":2,\"rotatedAt\":\"t\"," +
            "\"ledgerSequenceNumber\":2},\"meta\":{}}");
        var env = client().kibo().vault().secrets().rotate("db/pw", "us01");
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().contains("op=rotate"));
        assertTrue(lastPath.get().contains("region=us01"));
        assertEquals("", lastBody.get(), "rotate sends no body");
        assertEquals(2, env.data().ledgerSequenceNumber());
    }

    @Test
    void movePostsOpMoveWithNewPath() {
        mock("/@member-portal/vault/secrets",
            "{\"data\":{\"id\":\"sec_abc\",\"name\":\"db/pw2\",\"event\":\"SECRET_PATH_CHANGED\"},\"meta\":{}}");
        var env = client().kibo().vault().secrets().move("db/pw", "us01", "db/pw2");
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().contains("op=move"));
        assertEquals("{\"newPath\":\"db/pw2\"}", lastBody.get());
        assertEquals("SECRET_PATH_CHANGED", env.data().event());
    }

    @Test
    void deleteHandlesRegionAnd204EmptyBody() {
        mock204("/@member-portal/vault/secrets");
        var env = client().kibo().vault().secrets().delete("db/pw", "us01");
        assertEquals("DELETE", lastMethod.get());
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?region=us01", lastPath.get());
        assertNull(env.data());
    }

    @Test
    void secretsRequireProjectSlug() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        KError e = assertThrows(KError.class, () -> c.kibo().vault().secrets().get("db/pw"));
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.getCode());
    }
}
