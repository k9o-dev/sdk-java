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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@code client.ai().generate()} — URL, verb, body (prompt first, unset fields omitted), result, 400 and 501. */
class AiGenerateTest {

    static final String OK = "{\"data\":{\"modelId\":\"llama-3-8b\",\"content\":\"Bonjour\",\"finishReason\":\"stop\","
        + "\"usage\":{\"promptTokens\":5,\"completionTokens\":2,\"totalTokens\":7},\"usageType\":\"chat\"},\"meta\":{}}";
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
    void postsPromptOnlyAndParsesResult() {
        mockJson("/@member-portal/ai/generate", 200, OK);
        var r = client().ai().generate("hi");
        assertEquals("POST", lastMethod.get());
        assertEquals("/@member-portal/ai/generate", lastPath.get());
        assertEquals("{\"prompt\":\"hi\"}", lastBody.get());
        assertEquals("llama-3-8b", r.data().modelId());
        assertEquals("Bonjour", r.data().content());
        assertEquals("stop", r.data().finishReason());
        assertEquals(7L, r.data().usage().totalTokens());
        assertEquals("chat", r.data().usageType());
    }

    @Test
    void sendsGivenOptionsPromptFirst() {
        mockJson("/@member-portal/ai/generate", 200, OK);
        client().ai().generate("hi", new Ai.GenerateOptions("m", 9, 0.0, "chat"));
        assertEquals("{\"prompt\":\"hi\",\"model\":\"m\",\"maxTokens\":9,\"temperature\":0.0,\"usageType\":\"chat\"}",
            lastBody.get());
    }

    @Test
    void requiresBoundProject() {
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        var e = assertThrows(KError.class, () -> c.ai().generate("x"));
        assertTrue(e.getMessage().contains("projectSlug"));
    }

    @Test
    void mapsBadRequest() {
        mockJson("/@member-portal/ai/generate", 400, "{\"code\":\"K9O_VALIDATION_FAILED\",\"message\":\"no\"}");
        var e = assertThrows(KError.class, () -> client().ai().generate("hi"));
        assertEquals("K9O_VALIDATION_FAILED", e.getCode());
        assertEquals(400, e.getStatus());
    }

    @Test
    void mapsNotImplemented() {
        mockJson("/@member-portal/ai/generate", 501, "{\"code\":\"K9O_NOT_IMPLEMENTED\",\"message\":\"no\"}");
        var e = assertThrows(KError.class, () -> client().ai().generate("hi", new Ai.GenerateOptions("gpt-4o", null, null, null)));
        assertEquals("K9O_NOT_IMPLEMENTED", e.getCode());
        assertEquals(501, e.getStatus());
    }
}
