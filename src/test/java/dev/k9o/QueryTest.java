package dev.k9o;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@code query}, {@code search}, {@code textSearch} and {@code vectorSearch}: wire shape, shared vectors, validation. */
class QueryTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String ROWS = "{\"data\":[{\"id\":\"a\",\"title\":\"x\"}],\"meta\":{\"rawQuery\":{\"intent\":\"select\",\"rowCount\":1}}}";

    HttpServer server;
    String baseUrl;
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<String> target = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            method.set(ex.getRequestMethod());
            target.set(ex.getRequestURI().toString());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = ROWS.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build());
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    Map<String, String> queryParams() {
        Map<String, String> q = new HashMap<>();
        String raw = URI.create(target.get()).getRawQuery();
        if (raw == null) return q;
        for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            q.put(java.net.URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                java.net.URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8));
        }
        return q;
    }

    static void assertInvalid(Runnable r, String contains) {
        KError e = assertThrows(KError.class, r::run);
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        assertTrue(e.getMessage().contains(contains), e.getMessage());
    }

    // ---- shared vectors ----

    @Test
    void vector1() {
        var t = Query.translateNamedParams("SELECT * FROM users WHERE data->>'role' = :role", map("role", "admin"));
        assertEquals("SELECT * FROM users WHERE data->>'role' = $1", t.sql());
        assertEquals(Arrays.asList((Object) "admin"), t.params());
    }

    @Test
    void vector2() {
        var t = Query.translateNamedParams("SELECT :a, :b, :a", map("a", 1, "b", 2));
        assertEquals("SELECT $1, $2, $1", t.sql());
        assertEquals(List.of(1, 2), t.params());
    }

    @Test
    void vector3() {
        var t = Query.translateNamedParams("SELECT ':not_a_param', \":also_not\", x::int FROM t WHERE y = :y", map("y", true));
        assertEquals("SELECT ':not_a_param', \":also_not\", x::int FROM t WHERE y = $1", t.sql());
        assertEquals(List.of(true), t.params());
    }

    @Test
    void vector4() {
        var t = Query.translateNamedParams("SELECT 'it''s :no' , :v -- :comment\nFROM t", map("v", null));
        assertEquals("SELECT 'it''s :no' , $1 -- :comment\nFROM t", t.sql());
        assertEquals(Arrays.asList((Object) null), t.params());
    }

    @Test
    void vector5() {
        var t = Query.translateNamedParams("SELECT /* :c /* :d */ :e */ :f", map("f", 1));
        assertEquals("SELECT /* :c /* :d */ :e */ $1", t.sql());
        assertEquals(List.of(1), t.params());
    }

    @Test
    void vector6() {
        var t = Query.translateNamedParams("SELECT $$ :x $$, $q$ :y $q$, :z", map("z", "s"));
        assertEquals("SELECT $$ :x $$, $q$ :y $q$, $1", t.sql());
        assertEquals(List.of("s"), t.params());
    }

    @Test
    void vector7() {
        var t = Query.translateNamedParams("SELECT arr[1:2], a:b, :n", map("n", 5));
        assertEquals("SELECT arr[1:2], a:b, $1", t.sql());
        assertEquals(List.of(5), t.params());
    }

    @Test
    void vector8MissingValueFailsBeforeAnyRequest() {
        assertInvalid(() -> client().query("SELECT :missing", Map.<String, Object>of()), ":missing");
        assertEquals(0, calls.get());
    }

    @Test
    void vector9NonScalarValueFailsBeforeAnyRequest() {
        assertInvalid(() -> client().query("SELECT :a", map("a", new HashMap<>())), "serialise");
        assertInvalid(() -> client().query("SELECT $1", List.of(List.of(1))), "serialise");
        assertEquals(0, calls.get());
    }

    @Test
    void vector10ListParamsAreSentUnchanged() throws IOException {
        client().query("SELECT $1", List.of("x"));
        assertEquals(M.readTree("{\"sql\":\"SELECT $1\",\"params\":[\"x\"]}"), M.readTree(body.get()));
    }

    @Test
    void vector11NoParamsOmitsTheKey() throws IOException {
        client().query("SELECT 1");
        JsonNode b = M.readTree(body.get());
        assertEquals("SELECT 1", b.get("sql").asText());
        assertFalse(b.has("params"));
        client().query("SELECT 1", List.of());
        assertFalse(M.readTree(body.get()).has("params"));
    }

    @Test
    void extraKeysAreIgnoredAndEscapeStringsSkipped() {
        var t = Query.translateNamedParams("SELECT E'a\\' :x', :y", map("y", 1, "unused", 2));
        assertEquals("SELECT E'a\\' :x', $1", t.sql());
        assertEquals(List.of(1), t.params());
    }

    // ---- wire ----

    @Test
    void queryPostsNamedParamsAsPositionalAndParsesRows() throws IOException {
        var r = client().query("SELECT id FROM t WHERE a = :a AND b = :b", map("a", "x", "b", null));
        assertEquals("POST", method.get());
        assertEquals("/@portal/query", target.get());
        assertEquals(M.readTree("{\"sql\":\"SELECT id FROM t WHERE a = $1 AND b = $2\",\"params\":[\"x\",null]}"),
            M.readTree(body.get()));
        assertEquals("a", r.data().get(0).get("id"));
    }

    @Test
    void queryRequiresBoundProject() {
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.query("SELECT 1")).getCode());
    }

    // ---- textSearch / vectorSearch ----

    @Test
    void textSearchDefaultsSendOnlyTheQuery() {
        client().from("docs").select().textSearch("body", "red & green").execute(Map.class);
        assertEquals("GET", method.get());
        assertTrue(URI.create(target.get()).getRawQuery().contains("textSearch=body%3Ared+%26+green"), target.get());
        assertEquals(Map.of("textSearch", "body:red & green"), queryParams());
    }

    @Test
    void textSearchSendsLanguageAndTypeWhenNotDefault() {
        client().from("docs").select().eq("kind", "a").limit(5)
            .textSearch("body", "x", new dev.k9o.data.FromBuilder.TextSearchOptions("german", "websearch"))
            .execute(Map.class);
        var q = queryParams();
        assertEquals("body:x", q.get("textSearch"));
        assertEquals("german", q.get("textSearchLanguage"));
        assertEquals("websearch", q.get("textSearchType"));
        assertEquals("eq.a", q.get("kind"));
        assertEquals("5", q.get("limit"));
    }

    @Test
    void textSearchExplicitDefaultsAreOmitted() {
        client().from("docs").select()
            .textSearch("body", "x", new dev.k9o.data.FromBuilder.TextSearchOptions("english", "plain")).execute(Map.class);
        assertEquals(Map.of("textSearch", "body:x"), queryParams());
    }

    @Test
    void vectorSearchWire() {
        client().from("docs").select().vectorSearch("embedding", List.of(0.5, 1, -2)).execute(Map.class);
        assertEquals(Map.of("vectorSearch", "embedding:[0.5,1,-2]"), queryParams());
        client().from("docs").select()
            .vectorSearch("embedding", List.of(1, 2), new dev.k9o.data.FromBuilder.VectorSearchOptions("dot", 25))
            .execute(Map.class);
        var q = queryParams();
        assertEquals("embedding:[1,2]", q.get("vectorSearch"));
        assertEquals("dot", q.get("vectorMetric"));
        assertEquals("25", q.get("vectorLimit"));
    }

    @Test
    void searchValidationFailsBeforeAnyRequest() {
        var s = client().from("docs").select();
        assertInvalid(() -> s.textSearch("bad col", "x"), "column");
        assertInvalid(() -> s.textSearch("9col", "x"), "column");
        assertInvalid(() -> s.textSearch("a".repeat(64), "x"), "column");
        assertInvalid(() -> s.textSearch("body", ""), "non-empty");
        assertInvalid(() -> s.textSearch("body", "x", new dev.k9o.data.FromBuilder.TextSearchOptions("en glish", null)), "language");
        assertInvalid(() -> s.textSearch("body", "x", new dev.k9o.data.FromBuilder.TextSearchOptions(null, "fuzzy")), "type");
        assertInvalid(() -> s.vectorSearch("e;", List.of(1)), "column");
        assertInvalid(() -> s.vectorSearch("e", List.of()), "non-empty");
        assertInvalid(() -> s.vectorSearch("e", List.of(Double.NaN)), "non-empty");
        assertInvalid(() -> s.vectorSearch("e", List.of(1), new dev.k9o.data.FromBuilder.VectorSearchOptions("l2", null)), "metric");
        assertInvalid(() -> s.vectorSearch("e", List.of(1), new dev.k9o.data.FromBuilder.VectorSearchOptions(null, 0)), "limit");
        assertInvalid(() -> s.vectorSearch("e", List.of(1), new dev.k9o.data.FromBuilder.VectorSearchOptions(null, 1001)), "limit");
        assertEquals(0, calls.get());
    }

    // ---- root search ----

    @Test
    void searchIsTextSearchOnTheCollectionRoute() {
        var r = client().search("docs", new Search.Input("body", "hello", "french", "phrase", 3, "cur_1"));
        assertEquals("GET", method.get());
        assertEquals("/@portal/data/docs", URI.create(target.get()).getPath());
        var q = queryParams();
        assertEquals("body:hello", q.get("textSearch"));
        assertEquals("french", q.get("textSearchLanguage"));
        assertEquals("phrase", q.get("textSearchType"));
        assertEquals("3", q.get("limit"));
        assertEquals("cur_1", q.get("cursor"));
        assertEquals("a", r.data().get(0).get("id"));
    }

    @Test
    void searchRequiresColumnAndQuery() {
        assertInvalid(() -> client().search("docs", Search.Input.of(null, "x")), "column");
        assertInvalid(() -> client().search("docs", Search.Input.of("body", "")), "non-empty");
        assertEquals(0, calls.get());
    }
}
