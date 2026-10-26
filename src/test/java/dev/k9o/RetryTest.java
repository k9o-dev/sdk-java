package dev.k9o;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Retry + backoff behaviour. Idempotent methods are retried on
 * transient failures (transport / 502 / 503 / 504 / 429); semantic 4xx and
 * non-idempotent methods are not. backoff="none" ⇒ no sleeps.
 */
class RetryTest {

    HttpServer server;
    String baseUrl;

    static final String DOWN = "{\"code\":\"K9O_UPSTREAM_UNAVAILABLE\",\"message\":\"down\"}";
    static final RetryConfig NO_SLEEP = new RetryConfig(2, "none", 0, 0);

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

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).retryConfig(NO_SLEEP).build());
    }

    /** Returns `failStatus`/`failBody` for the first `failCount` calls, then 200/`okBody`. */
    AtomicInteger mockSequence(String path, int failStatus, String failBody, int failCount, String okBody) {
        AtomicInteger calls = new AtomicInteger(0);
        server.createContext(path, ex -> {
            int n = calls.incrementAndGet();
            int status = n <= failCount ? failStatus : 200;
            String body = n <= failCount ? failBody : okBody;
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        return calls;
    }

    @Test
    void getRetriesTransientThenSucceeds() {
        AtomicInteger calls = mockSequence("/orgs", 503, DOWN, 2, "{\"data\":[],\"meta\":{}}");
        client().orgs().list();
        assertEquals(3, calls.get()); // 2 failures + 1 success
    }

    @Test
    void getExhaustsRetriesThenThrows() {
        AtomicInteger calls = mockSequence("/orgs", 503, DOWN, 999, "{}");
        assertThrows(KError.class, () -> client().orgs().list());
        assertEquals(3, calls.get()); // max_retries (2) + 1 initial
    }

    @Test
    void postNotRetried() {
        AtomicInteger calls = mockSequence("/projects", 503, DOWN, 999, "{}");
        assertThrows(KError.class,
            () -> client().projects().create(new Projects.CreateInput("X", "valid-slug", null, null, null, null)));
        assertEquals(1, calls.get());
    }

    @Test
    void getSemantic4xxNotRetried() {
        AtomicInteger calls = mockSequence("/orgs", 404, "{\"code\":\"K9O_NOT_FOUND\",\"message\":\"nope\"}", 999, "{}");
        assertThrows(KError.class, () -> client().orgs().list());
        assertEquals(1, calls.get());
    }
}
