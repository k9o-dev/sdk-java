package dev.k9o;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** User data streams: exact method, path and body per call; subscribe resume; the 503 mapping. */
class StreamsTest {

    static final ObjectMapper M = new ObjectMapper();

    record Call(String method, String target, String body) {}

    HttpServer server;
    String baseUrl;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile java.util.function.Function<Call, String[]> handler = c -> new String[] {"200", "application/json", "{\"data\":null,\"meta\":{}}"};

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            Call call = new Call(ex.getRequestMethod(), ex.getRequestURI().toString(),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(call);
            String[] r = handler.apply(call);
            byte[] b = r[2].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", r[1]);
            ex.sendResponseHeaders(Integer.parseInt(r[0]), b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build());
    }

    void expectLast(String method, String target, String json) throws IOException {
        Call c = calls.get(calls.size() - 1);
        assertEquals(method, c.method());
        assertEquals(target, c.target());
        if (json == null) assertEquals("", c.body());
        else assertEquals(M.readTree(json), M.readTree(c.body()), c.body());
    }

    static String frame(String seq) {
        return "id: " + seq + "\ndata: {\"sequence\":\"" + seq + "\",\"subject\":\"s\",\"data\":{\"n\":\"" + seq + "\"},\"publishedAt\":\"t\"}\n\n";
    }

    @Test
    void controlAndDataPlaneRequests() throws IOException {
        handler = c -> {
            String body;
            if (c.method().equals("DELETE")) body = "{\"data\":{\"slug\":\"orders\",\"status\":\"deleted\"},\"meta\":{}}";
            else if (c.target().endsWith("/publish")) body = "{\"data\":{\"streamSlug\":\"orders\",\"subject\":\"k9o.x\",\"bytes\":8,\"accepted\":true},\"meta\":{}}";
            else if (c.target().endsWith("/replay")) body = "{\"data\":{\"streamSlug\":\"orders\",\"events\":[{\"sequence\":\"11\",\"subject\":\"s\",\"data\":{\"id\":1},\"publishedAt\":\"t\"}],\"nextCursor\":\"11\",\"hasMore\":false},\"meta\":{}}";
            else if (c.method().equals("GET") && c.target().endsWith("/user")) body = "{\"data\":[{\"slug\":\"orders\",\"maxBytes\":1073741824}],\"meta\":{}}";
            else body = "{\"data\":{\"slug\":\"orders\",\"displayName\":\"Orders\",\"maxBytes\":null,\"retentionSeconds\":86400},\"meta\":{}}";
            return new String[] {"200", "application/json", body};
        };
        var s = client().streams();

        var created = s.create("Orders", 1073741824L, null).data();
        assertEquals("orders", created.slug());
        assertNull(created.maxBytes());
        expectLast("POST", "/@portal/data-streams/user", "{\"displayName\":\"Orders\",\"maxBytes\":1073741824}");
        s.create("Orders", 1073741824L, 86400L);
        expectLast("POST", "/@portal/data-streams/user", "{\"displayName\":\"Orders\",\"maxBytes\":1073741824,\"retentionSeconds\":86400}");
        assertEquals(1073741824L, s.list().data().get(0).maxBytes());
        expectLast("GET", "/@portal/data-streams/user", null);
        s.get("orders");
        expectLast("GET", "/@portal/data-streams/user/orders", null);
        assertEquals("deleted", s.delete("orders").data().status());
        expectLast("DELETE", "/@portal/data-streams/user/orders", null);
        assertTrue(s.publish("orders", java.util.Map.of("id", 1), "eu.new").data().accepted());
        expectLast("POST", "/@portal/data-streams/user/orders/publish", "{\"data\":{\"id\":1},\"subject\":\"eu.new\"}");
        s.publish("orders", 1, null);
        expectLast("POST", "/@portal/data-streams/user/orders/publish", "{\"data\":1}");
        var rep = s.replay("orders", 10L, 20L, 5).data();
        assertEquals("11", rep.events().get(0).sequence());
        assertEquals("11", rep.nextCursor());
        expectLast("POST", "/@portal/data-streams/user/orders/replay", "{\"fromSequence\":10,\"untilSequence\":20,\"limit\":5}");
        s.replay("orders", null, null, null);
        expectLast("POST", "/@portal/data-streams/user/orders/replay", "{}");
    }

    @Test
    void missingProjectIsTyped() {
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        KError e = assertThrows(KError.class, () -> c.streams().list());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.getCode());
    }

    @Test
    void unavailableMapsToKError() {
        handler = c -> new String[] {"503", "application/json",
            "{\"code\":\"K9O_STREAM_UNAVAILABLE\",\"message\":\"The Pulse Stream data plane is not yet available in this deployment.\",\"context\":{}}"};
        var s = client().streams();
        KError e = assertThrows(KError.class, () -> s.publish("orders", 1, null));
        assertEquals("K9O_STREAM_UNAVAILABLE", e.getCode());
        assertEquals(503, e.getStatus());
        e = assertThrows(KError.class, () -> s.subscribe("orders", null));
        assertEquals("K9O_STREAM_UNAVAILABLE", e.getCode());
        assertEquals(503, e.getStatus());
    }

    @Test
    void subscribeResumesFromLastSequenceAcrossServerClosedConnection() {
        handler = c -> {
            String q = c.target().contains("?") ? c.target().substring(c.target().indexOf('?') + 1) : "";
            String body = "retry: 1000\n:ok\n\n";
            if (q.isEmpty()) body += frame("1") + frame("2");
            else if (q.equals("fromSequence=2")) body += frame("3");
            return new String[] {"200", "text/event-stream", body};
        };
        List<String> seqs = new ArrayList<>();
        try (var sub = client().streams().subscribe("orders", null).reconnectDelay(Duration.ofMillis(1))) {
            for (var ev : sub) {
                seqs.add(ev.sequence());
                if (seqs.size() == 3) break;
            }
        }
        assertEquals(List.of("1", "2", "3"), seqs);
        assertTrue(calls.size() >= 2, calls.toString());
        assertEquals("GET", calls.get(0).method());
        assertEquals("/@portal/data-streams/user/orders/subscribe", calls.get(0).target());
        assertEquals("/@portal/data-streams/user/orders/subscribe?fromSequence=2", calls.get(1).target());
    }

    @Test
    void subscribeStartsFromGivenSequenceAndCloseStopsIt() {
        handler = c -> new String[] {"200", "text/event-stream", frame("43")};
        var sub = client().streams().subscribe("orders", "42");
        var it = sub.iterator();
        assertEquals("43", it.next().sequence());
        assertEquals("/@portal/data-streams/user/orders/subscribe?fromSequence=42", calls.get(0).target());
        sub.close();
        assertFalse(it.hasNext());
    }
}
