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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P6 surface-family tests (studio / compute.llms / realtime / org
 * default-LLM). Routes verified
 * against a JDK stdlib HttpServer mock gateway.
 */
class P6Test {

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
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** Capture request + return a fixed JSON envelope body. */
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

    /** Capture request + return an SSE body. */
    void mockSse(String path, String sse) {
        server.createContext(path, ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastPath.set(ex.getRequestURI().toString());
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    K9o.K9oClient projectClient() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build());
    }

    @Test
    void workflowsTriggerAndRunCancel() {
        mockJson("/@portal/workflows/wfl_x/runs", 200,
            "{\"data\":{\"id\":\"wfr_1\",\"workflowId\":\"wfl_x\",\"projectId\":\"p\",\"status\":\"pending\"},\"meta\":{}}");
        mockJson("/@portal/workflows/wfl_x/runs/wfr_1/cancel", 200,
            "{\"data\":{\"id\":\"wfr_1\",\"workflowId\":\"wfl_x\",\"projectId\":\"p\",\"status\":\"cancelled\"},\"meta\":{}}");

        var c = projectClient();
        var run = c.studio().workflows().trigger("wfl_x", Map.of("foo", "bar")).data();
        assertEquals("pending", run.status());
        assertTrue(lastBody.get().contains("bar"));

        var cancelled = c.studio().workflows().runs().cancel("wfl_x", "wfr_1").data();
        assertEquals("cancelled", cancelled.status());
        assertEquals("POST", lastMethod.get());
    }

    @Test
    void dashboardsPin() {
        mockJson("/@portal/dashboards/dsh_x/pin", 200,
            "{\"data\":{\"id\":\"dsh_x\",\"projectId\":\"p\",\"name\":\"ops\",\"status\":\"active\",\"pinnedToWorkspace\":true},\"meta\":{}}");

        var c = projectClient();
        var pinned = c.studio().dashboards().pin("dsh_x").data();
        assertTrue(pinned.pinnedToWorkspace());
    }

    @Test
    void llmsInvoke() {
        mockJson("/@portal/compute/llms/mistral-7b/invoke", 200,
            "{\"data\":{\"modelId\":\"mistral-7b\",\"content\":\"hi\",\"finishReason\":\"stop\",\"usage\":{\"promptTokens\":1,\"completionTokens\":1,\"totalTokens\":2},\"usageType\":\"sdk.direct\"},\"meta\":{}}");
        var c = projectClient();
        var resp = c.compute().llms().invoke("mistral-7b",
            new Compute.LLMInvokeInput("hello", null)).data();
        assertEquals("hi", resp.content());
        assertEquals(2L, resp.usage().totalTokens());
    }

    @Test
    void llmsInvokeStreamYieldsChunks() {
        String sse =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":1}}\n\n" +
            "data: [DONE]\n\n";
        mockSse("/@portal/compute/llms/gpt-4o/invoke-stream", sse);
        var c = projectClient();
        StringBuilder acc = new StringBuilder();
        boolean lastDone = false;
        try (var stream = c.compute().llms().invokeStream("gpt-4o",
            new Compute.LLMInvokeInput("hi", null))) {
            for (var chunk : stream) {
                acc.append(chunk.text());
                if ("stop".equals(chunk.choices().get(0).finishReason())) lastDone = true;
            }
        }
        assertEquals("Hello", acc.toString());
        assertTrue(lastDone);
    }

    @Test
    void orgDefaultLLMGetAndSet() {
        mockJson("/default-llm", 200, "{\"data\":{\"orgId\":\"org_x\",\"defaultModelId\":\"mistral-7b\"},\"meta\":{}}");
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        // GET
        var got = c.org().defaultLLM().get();
        assertEquals("mistral-7b", got.data().defaultModelId());
        assertEquals("GET", lastMethod.get());
        // PATCH (same context — HttpServer routes by path, both verbs go through)
        c.org().defaultLLM().set("claude-3-5-sonnet");
        assertEquals("PATCH", lastMethod.get());
        assertTrue(lastBody.get().contains("claude-3-5-sonnet"));
    }

    @Test
    void realtimeTopicBuilders() {
        assertEquals("collections.orders.*", Realtime.Topic.collection("orders"));
        assertEquals("studio.workflows.runs.*", Realtime.Topic.workflowRuns());
        assertEquals("studio.dashboards.refresh.dsh_x", Realtime.Topic.dashboardRefresh("dsh_x"));
        assertEquals("forge.functions.invocations.fn_x", Realtime.Topic.functionInvocations("fn_x"));
        assertEquals("audit.org.role_granted", Realtime.Topic.auditOrg("role_granted"));
    }

    @Test
    void realtimeSubscribeSseYieldsEvents() {
        String sse =
            "id: 1\nevent: row_inserted\ndata: {\"topic\":\"collections.orders.*\",\"eventType\":\"row_inserted\",\"payload\":{},\"occurredAt\":\"t1\"}\n\n" +
            "event: resync\ndata: {\"reason\":\"cursor-evicted\",\"head\":\"x\",\"action\":\"reread-source\"}\n\n" +
            "id: 2\nevent: row_updated\ndata: {\"topic\":\"collections.orders.*\",\"eventType\":\"row_updated\",\"payload\":{},\"occurredAt\":\"t2\"}\n\n";
        mockSse("/@portal/realtime/subscribe", sse);
        var c = projectClient();
        List<String> types = new ArrayList<>();
        try (var stream = c.realtime().subscribe(Realtime.Topic.collection("orders"), null)) {
            for (var ev : stream) types.add(ev.eventType());
        }
        assertEquals(List.of("row_inserted", "row_updated"), types);
    }
}
