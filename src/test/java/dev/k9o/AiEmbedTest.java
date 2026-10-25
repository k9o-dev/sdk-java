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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@code client.ai().embed()} — URL, verb, body shape, bound-project guard, error never echoes input. */
class AiEmbedTest {

    static final String OK =
        "{\"data\":{\"embeddings\":[[0.1,0.2]],\"model\":\"nomic-embed-text-v1.5\",\"dimensions\":2},\"meta\":{}}";
    static final ObjectMapper M = new ObjectMapper();

    HttpServer server;
    String baseUrl;
    AtomicReference<String> lastMethod = new AtomicReference<>();
    AtomicReference<String> lastPath = new AtomicReference<>();
    AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    void mockJson(String path, int status, String json) {
        server.createContext(path, ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastPath.set(ex.getRequestURI().toString());
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("member-portal").build());
    }

    @Test
    void postsEmbeddingsAndReturnsVectors() throws Exception {
        mockJson("/@member-portal/ai/embeddings", 200, OK);
        var r = client().ai().embed("hello");
        assertEquals("POST", lastMethod.get());
        assertEquals("/@member-portal/ai/embeddings", lastPath.get());
        JsonNode body = M.readTree(lastBody.get());
        assertTrue(body.get("input").isTextual());
        assertEquals("hello", body.get("input").asText());
        assertEquals(List.of(List.of(0.1, 0.2)), r.data().embeddings());
        assertEquals(2, r.data().dimensions());
    }

    @Test
    void sendsListInputAndModelAsGiven() throws Exception {
        mockJson("/@member-portal/ai/embeddings", 200, OK);
        client().ai().embed("nomic-embed-text-v1.5", List.of("search_document: a", "search_document: b"));
        assertEquals(M.readTree(
            "{\"model\":\"nomic-embed-text-v1.5\",\"input\":[\"search_document: a\",\"search_document: b\"]}"),
            M.readTree(lastBody.get()));
    }

    @Test
    void omitsModelWhenNotGiven() throws Exception {
        mockJson("/@member-portal/ai/embeddings", 200, OK);
        client().ai().embed(List.of("x"));
        assertFalse(M.readTree(lastBody.get()).has("model"));
    }

    @Test
    void requiresBoundProject() {
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        var e = assertThrows(KError.class, () -> c.ai().embed("x"));
        assertTrue(e.getMessage().contains("projectSlug"));
    }

    @Test
    void surfacesRefusalAsKErrorWithoutInput() {
        mockJson("/@member-portal/ai/embeddings", 400,
            "{\"code\":\"K9O_MODEL_NOT_DECLARED\",\"message\":\"model is not a declared embedding model\","
                + "\"context\":{\"declared\":[\"nomic-embed-text-v1.5\"]},\"recommendation\":null}");
        String secret = "the-secret-document-body";
        var e = assertThrows(KError.class, () -> client().ai().embed("other", secret));
        assertEquals("K9O_MODEL_NOT_DECLARED", e.getCode());
        assertFalse(e.toString().contains(secret));
        assertFalse(e.getMessage().contains(secret));
    }
}
