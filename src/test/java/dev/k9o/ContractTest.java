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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact wire contract: for each call the SDK sends, the HTTP method, path (with query) and JSON body the gateway's
 * route reads (gateway OpenAPI and route handlers under services/gateway/src/routes). A JDK HttpServer records the
 * request and answers with an empty envelope.
 */
class ContractTest {

    static final ObjectMapper M = new ObjectMapper();

    HttpServer server;
    String baseUrl;
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<String> target = new AtomicReference<>();
    final AtomicReference<String> contentType = new AtomicReference<>();
    final AtomicReference<byte[]> bodyBytes = new AtomicReference<>();
    volatile String responseJson = "{\"data\":null,\"meta\":{}}";
    volatile String responseType = "application/json";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            method.set(ex.getRequestMethod());
            target.set(ex.getRequestURI().toString());
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            bodyBytes.set(ex.getRequestBody().readAllBytes());
            byte[] b = responseJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", responseType);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    K9o.K9oClient project() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build());
    }

    K9o.K9oClient org() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
    }

    /** Path plus the query as an unordered map, so parameter order is not part of the contract. */
    static List<Object> splitQuery(String target) {
        int i = target.indexOf('?');
        Map<String, String> q = new java.util.TreeMap<>();
        if (i >= 0) {
            for (String kv : target.substring(i + 1).split("&")) {
                String[] p = kv.split("=", 2);
                q.put(p[0], p.length > 1 ? p[1] : "");
            }
        }
        return List.of(i >= 0 ? target.substring(0, i) : target, q);
    }

    void expect(String m, String path, String json) throws IOException {
        assertEquals(m, method.get());
        assertEquals(splitQuery(path), splitQuery(target.get()), "request target");
        if (json == null) {
            assertEquals(0, bodyBytes.get().length, "expected no body, got " + new String(bodyBytes.get(), StandardCharsets.UTF_8));
        } else {
            assertEquals(M.readTree(json), M.readTree(bodyBytes.get()), new String(bodyBytes.get(), StandardCharsets.UTF_8));
        }
    }

    // ---- projects ----

    @Test
    void projectsCreateSendsDisplayNameAndOnlySetFields() throws IOException {
        org().projects().create(new Projects.CreateInput("Demo", "demo", "de01", "PLATFORM_MANAGED", null, null));
        expect("POST", "/projects", "{\"displayName\":\"Demo\",\"slug\":\"demo\",\"region\":\"de01\",\"custodyMode\":\"PLATFORM_MANAGED\"}");
        org().projects().create(new Projects.CreateInput("Only a name"));
        expect("POST", "/projects", "{\"displayName\":\"Only a name\"}");
    }

    @Test
    void projectsGetAndUpdateUseTheProjectsRoute() throws IOException {
        org().projects().get("demo");
        expect("GET", "/projects/demo", null);
        org().projects().update("demo", new Projects.UpdateInput("Renamed", null, null, null));
        expect("PATCH", "/projects/demo", "{\"displayName\":\"Renamed\"}");
        org().projects().list();
        expect("GET", "/projects", null);
    }

    @Test
    void projectRecordReadsDisplayName() {
        responseJson = "{\"data\":{\"id\":\"proj_1\",\"slug\":\"demo\",\"displayName\":\"Demo\",\"custodyMode\":\"PLATFORM_MANAGED\",\"orgId\":\"org_1\",\"extra\":1},\"meta\":{}}";
        Projects.Project p = org().projects().get("demo").data();
        assertEquals("Demo", p.displayName());
    }

    // ---- functions ----

    @Test
    void functionsDeploySendsArtifactKeyVersionAndTier() throws IOException {
        project().functions().deploy(new Functions.DeployFunctionInput("hello", "bun", "sandbox", "functions/p/abc.tar", "1"));
        expect("POST", "/@portal/functions",
            "{\"name\":\"hello\",\"runtime\":\"bun\",\"tier\":\"sandbox\",\"artifactKey\":\"functions/p/abc.tar\",\"version\":\"1\"}");
    }

    @Test
    void functionsUploadArtifactSendsRawBytes() throws IOException {
        byte[] wasm = {0, 97, 115, 109};
        project().functions().uploadArtifact(wasm, "wasm");
        assertEquals("POST", method.get());
        assertEquals("/@portal/functions/artifacts", target.get());
        assertEquals("application/wasm", contentType.get());
        assertArrayEquals(wasm, bodyBytes.get());
        project().functions().uploadArtifact(wasm, "tar");
        assertEquals("application/x-tar", contentType.get());
        assertThrows(KError.class, () -> project().functions().uploadArtifact(wasm, "zip"));
    }

    @Test
    void functionsListAndInvoke() throws IOException {
        project().functions().list("active", 10, "c1");
        expect("GET", "/@portal/functions?status=active&limit=10&cursor=c1", null);
        project().functions().invoke("hello", Map.of("a", 1));
        expect("POST", "/@portal/functions/hello/invoke", "{\"payload\":{\"a\":1}}");
        project().functions().invoke("hello", null, new Functions.InvokeOptions(true, null, null));
        expect("POST", "/@portal/functions/hello/invoke", "{\"background\":true}");
        project().functions().get("hello");
        expect("GET", "/@portal/functions/hello", null);
        project().functions().delete("hello");
        expect("DELETE", "/@portal/functions/hello", null);
    }

    // ---- policies ----

    @Test
    void policiesSendThePolicyDocument() throws IOException {
        Map<String, Object> doc = Map.of("collections", Map.of());
        project().policies().create(new Policies.PolicyInput(doc, null, "json"));
        expect("POST", "/@portal/policies", "{\"policy\":{\"collections\":{}},\"editorMode\":\"json\"}");
        project().policies().validate(new Policies.PolicyInput(doc));
        expect("POST", "/@portal/policies/validate", "{\"policy\":{\"collections\":{}}}");
        project().policies().update("pol_1", new Policies.PolicyInput(doc));
        expect("PATCH", "/@portal/policies/pol_1", "{\"policy\":{\"collections\":{}}}");
        project().policies().publish("pol_1");
        expect("POST", "/@portal/policies/pol_1/publish", null);
        project().policies().list("published");
        expect("GET", "/@portal/policies?status=published", null);
        project().policies().current();
        expect("GET", "/@portal/policy", null);
        project().policies().delete("pol_1");
        expect("DELETE", "/@portal/policies/pol_1", null);
    }

    // ---- audit ----

    @Test
    void auditQueryUsesTheGatewayFilterNames() throws IOException {
        project().audit().query(new Audit.QueryOpts("auth.sign_in", "u_1", "2026-01-01", "2026-02-01", 10, "cur"));
        assertEquals("GET", method.get());
        java.net.URI u = java.net.URI.create(target.get());
        assertEquals("/@portal/audit-log", u.getPath());
        Map<String, String> q = new LinkedHashMap<>();
        for (String kv : u.getRawQuery().split("&")) {
            String[] p = kv.split("=", 2);
            q.put(p[0], java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        assertEquals(Map.of("eventType", "auth.sign_in", "actorId", "u_1", "after", "2026-01-01", "before", "2026-02-01",
            "limit", "10", "cursor", "cur"), q);
    }

    // ---- org plane ----

    @Test
    void orgMembersGroupsAudit() throws IOException {
        org().org().members().list("u_1", "ORG_MEMBER", "accepted", "active", null);
        assertEquals("GET", method.get());
        assertTrue(target.get().startsWith("/members?"), target.get());
        for (String part : List.of("userId=u_1", "role=ORG_MEMBER", "acceptanceStatus=accepted", "status=active")) {
            assertTrue(target.get().contains(part), target.get());
        }
        org().org().members().invite("a@b.co", "ORG_MEMBER");
        expect("POST", "/members", "{\"email\":\"a@b.co\",\"role\":\"ORG_MEMBER\"}");
        org().org().members().remove("u_1");
        expect("DELETE", "/members/u_1", null);
        org().org().groups().list("u_1");
        expect("GET", "/groups?userId=u_1", null);
        org().org().groups().get("g_1");
        expect("GET", "/groups/g_1", null);
        org().org().auditLog().query("data.write.org", 10);
        assertEquals("GET", method.get());
        assertTrue(target.get().startsWith("/audit-log?"), target.get());
        assertTrue(target.get().contains("type=data.write.org") && target.get().contains("limit=10"), target.get());
        org().org().get();
        expect("GET", "/org", null);
    }

    @Test
    void orgRoleGrants() throws IOException {
        org().org().gate().roles().list();
        expect("GET", "/role-grants", null);
        org().org().gate().roles().grant("u_1", "ORG_ADMIN");
        expect("POST", "/role-grants", "{\"userId\":\"u_1\",\"role\":\"ORG_ADMIN\"}");
        org().org().gate().roles().grant("u_1", "ORG_ADMIN", "audit");
        expect("POST", "/role-grants", "{\"userId\":\"u_1\",\"role\":\"ORG_ADMIN\",\"decisionReason\":\"audit\"}");
        org().org().gate().roles().get("g_1");
        expect("GET", "/role-grants/g_1", null);
        org().org().gate().roles().revoke("g_1");
        expect("DELETE", "/role-grants/g_1", null);
    }

    @Test
    void orgIdentityProviders() throws IOException {
        org().org().gate().idpProviders().list();
        expect("GET", "/identity-providers", null);
        org().org().gate().idpProviders().create(
            new Org.Gate.IdpProviders.CreateIdpInput("saml", "Okta", "cid", "ref_1", "free_tier_ok"));
        expect("POST", "/identity-providers",
            "{\"idpProvider\":\"saml\",\"displayName\":\"Okta\",\"clientId\":\"cid\",\"clientSecretRef\":\"ref_1\",\"tierGating\":\"free_tier_ok\"}");
        org().org().gate().idpProviders().get("idp_1");
        expect("GET", "/identity-providers/idp_1", null);
        org().org().gate().idpProviders().configure("idp_1", Map.of("enabled", true));
        expect("PATCH", "/identity-providers/idp_1", "{\"enabled\":true}");
        org().org().gate().idpProviders().delete("idp_1");
        expect("DELETE", "/identity-providers/idp_1", null);
    }

    @Test
    void orgSmtpSendsPasswordRefAndFromAddress() throws IOException {
        org().org().smtp().set(new Org.Smtp.SetSmtpInput("smtp.x.io", 587, "u", "ref_1", "a@b.co", "A"));
        expect("PATCH", "/org",
            "{\"smtpConfig\":{\"host\":\"smtp.x.io\",\"port\":587,\"username\":\"u\",\"passwordRef\":\"ref_1\",\"fromAddress\":\"a@b.co\",\"fromName\":\"A\"}}");
        org().org().smtp().set(new Org.Smtp.SetSmtpInput("smtp.x.io", 587, "u", "ref_1", "a@b.co", null));
        expect("PATCH", "/org",
            "{\"smtpConfig\":{\"host\":\"smtp.x.io\",\"port\":587,\"username\":\"u\",\"passwordRef\":\"ref_1\",\"fromAddress\":\"a@b.co\"}}");
        org().org().smtp().test("a@b.co", null);
        expect("POST", "/org/smtp/test-email", "{\"to\":\"a@b.co\"}");
        org().org().smtp().test("a@b.co", "Hi");
        expect("POST", "/org/smtp/test-email", "{\"to\":\"a@b.co\",\"subject\":\"Hi\"}");
        org().org().smtp().unset();
        expect("PATCH", "/org", "{\"smtpConfig\":null}");
    }

    @Test
    void orgDefaultLlmUsesDefaultLlmRoute() throws IOException {
        org().org().defaultLLM().get();
        expect("GET", "/default-llm", null);
        org().org().defaultLLM().set("llama-3-8b");
        expect("PATCH", "/default-llm", "{\"modelId\":\"llama-3-8b\"}");
    }

    @Test
    void orgsRoutes() throws IOException {
        org().orgs().list();
        expect("GET", "/orgs", null);
        org().orgs().get("acme");
        expect("GET", "/orgs/acme", null);
        org().orgs().restore("org_1");
        expect("POST", "/orgs/org_1/restore", null);
        org().orgs().current();
        expect("GET", "/org", null);
    }

    // ---- me ----

    @Test
    void meSessionsAndTokens() throws IOException {
        org().me().profile().get();
        expect("GET", "/me", null);
        org().me().profile().update("A");
        expect("PATCH", "/me", "{\"displayName\":\"A\"}");
        org().me().sessions().list();
        expect("GET", "/me/sessions", null);
        org().me().sessions().revoke("s_1");
        expect("POST", "/auth/sessions/s_1/revoke", null);
        org().me().sessions().revokeAll();
        expect("POST", "/me/sessions/revoke-all", null);
        org().me().apiTokens().list();
        expect("GET", "/me/api-tokens", null);
        org().me().apiTokens().create("ci",
            new Me.ApiTokens.ScopeManifest(List.of("read"), List.of("portal"), null, null, null), 3600);
        expect("POST", "/me/api-tokens",
            "{\"label\":\"ci\",\"scopeManifest\":{\"operations\":[\"read\"],\"projects\":[\"portal\"],\"resourceClasses\":null,\"collections\":null,\"cidrs\":null},\"ttlSeconds\":3600}");
        org().me().apiTokens().delete("pat_1");
        expect("DELETE", "/me/api-tokens/pat_1", null);
    }

    // ---- compute / ai ----

    @Test
    void computeLlmsSendFlatParams() throws IOException {
        project().compute().llms().list();
        expect("GET", "/@portal/compute/llms", null);
        project().compute().llms().read("llama-3-8b");
        expect("GET", "/@portal/compute/llms/llama-3-8b", null);
        project().compute().llms().invoke("llama-3-8b",
            new Compute.LLMInvokeInput("hi", Map.of("maxTokens", 10, "temperature", 0.2)));
        expect("POST", "/@portal/compute/llms/llama-3-8b/invoke", "{\"prompt\":\"hi\",\"maxTokens\":10,\"temperature\":0.2}");
        project().compute().llms().invoke("llama-3-8b", new Compute.LLMInvokeInput("hi"));
        expect("POST", "/@portal/compute/llms/llama-3-8b/invoke", "{\"prompt\":\"hi\"}");
    }

    @Test
    void computeLlmsInvokeReadsContentAndUsage() {
        responseJson = "{\"data\":{\"modelId\":\"llama-3-8b\",\"content\":\"hello\",\"finishReason\":\"stop\",\"usage\":{\"promptTokens\":1,\"completionTokens\":2,\"totalTokens\":3},\"usageType\":\"sdk.direct\"},\"meta\":{}}";
        Compute.LLMResponse r = project().compute().llms().invoke("llama-3-8b", new Compute.LLMInvokeInput("hi")).data();
        assertEquals("hello", r.content());
        assertEquals(3L, r.usage().totalTokens());
    }

    @Test
    void computeLlmsStreamBodyIsFlat() throws IOException {
        responseType = "text/event-stream";
        responseJson = "data: [DONE]\n\n";
        try (var s = project().compute().llms().invokeStream("llama-3-8b",
            new Compute.LLMInvokeInput("hi", Map.of("maxTokens", 5)))) {
            for (var ignored : s) { /* no chunks */ }
        }
        expect("POST", "/@portal/compute/llms/llama-3-8b/invoke-stream", "{\"prompt\":\"hi\",\"maxTokens\":5}");
    }

    @Test
    void aiEmbedBody() throws IOException {
        project().ai().embed("m", List.of("a", "b"));
        expect("POST", "/@portal/ai/embeddings", "{\"model\":\"m\",\"input\":[\"a\",\"b\"]}");
    }

    @Test
    void aiGenerateBody() throws IOException {
        project().ai().generate("hi", new Ai.GenerateOptions("llama-3-8b", 10, 0.2, null));
        expect("POST", "/@portal/ai/generate", "{\"prompt\":\"hi\",\"model\":\"llama-3-8b\",\"maxTokens\":10,\"temperature\":0.2}");
    }

    @Test
    void queryBody() throws IOException {
        project().query("SELECT * FROM t WHERE a = :a", Map.of("a", 1));
        expect("POST", "/@portal/query", "{\"sql\":\"SELECT * FROM t WHERE a = $1\",\"params\":[1]}");
        project().query("SELECT 1");
        expect("POST", "/@portal/query", "{\"sql\":\"SELECT 1\"}");
    }

    @Test
    void searchQuery() throws IOException {
        project().search("docs", Search.Input.of("body", "hi"));
        expect("GET", "/@portal/data/docs?textSearch=body%3Ahi", null);
    }

    /**
     * Routes the SDK already calls that are not in services/gateway/openapi.json yet. They are exempt from the
     * OpenAPI route check until the route lands; {@link #pendingRoutesAreStillPending} fails once one appears
     * in the OpenAPI, and the entry must then be removed.
     */
    static final Map<String, String> PENDING_ROUTES = Map.of();

    @Test
    void pendingRoutesAreStillPending() throws IOException {
        java.nio.file.Path file = java.nio.file.Path.of("..", "..", "services", "gateway", "openapi.json");
        if (!java.nio.file.Files.exists(file)) return; // the gateway OpenAPI is not next to this package
        JsonNode paths = M.readTree(file.toFile()).get("paths");
        for (String key : PENDING_ROUTES.keySet()) {
            String[] parts = key.split(" ", 2);
            assertTrue(paths.path(parts[1]).path(parts[0].toLowerCase()).isMissingNode(),
                key + " is in the OpenAPI now: remove it from PENDING_ROUTES");
        }
    }

    // ---- studio ----

    @Test
    void workflowsWireShapes() throws IOException {
        project().studio().workflows().trigger("wfl_1", Map.of("k", "v"));
        expect("POST", "/@portal/workflows/wfl_1/runs", "{\"triggerContext\":{\"k\":\"v\"}}");
        project().studio().workflows().trigger("wfl_1", null);
        expect("POST", "/@portal/workflows/wfl_1/runs", "{\"triggerContext\":{}}");
        project().studio().workflows().list(new Studio.WorkflowListFilters("active", 5, "c"));
        expect("GET", "/@portal/workflows?status=active&limit=5&cursor=c", null);
        project().studio().workflows().runs().list("wfl_1", 5, "c");
        expect("GET", "/@portal/workflows/wfl_1/runs?limit=5&cursor=c", null);
        project().studio().workflows().create(new Studio.CreateWorkflowInput("w", null, null, "manual", Map.of(),
            List.of(Map.of("index", 0, "action_type", "branch", "config", Map.of())), null));
        expect("POST", "/@portal/workflows",
            "{\"name\":\"w\",\"triggerType\":\"manual\",\"triggerConfig\":{},\"actions\":[{\"index\":0,\"action_type\":\"branch\",\"config\":{}}]}");
    }

    @Test
    void dashboardsWireShapes() throws IOException {
        project().studio().dashboards().list("active", true, 5, "c");
        expect("GET", "/@portal/dashboards?status=active&pinnedOnly=true&limit=5&cursor=c", null);
        project().studio().dashboards().create(new Studio.CreateDashboardInput("d", null, null, null, null, null, null));
        expect("POST", "/@portal/dashboards", "{\"name\":\"d\"}");
    }

    // ---- realtime ----

    @Test
    void realtimeSubscribeIsASseGetWithTopicAndFilter() throws IOException {
        responseType = "text/event-stream";
        responseJson = "event: resync\ndata: {\"reason\":\"cursor-evicted\"}\n\nevent: x\ndata: {\"topic\":\"t\",\"eventType\":\"x\",\"payload\":{},\"occurredAt\":\"now\"}\n\n";
        List<String> types = new java.util.ArrayList<>();
        try (var s = project().realtime().subscribe("collections.orders.*", Map.of("status", "open"))) {
            for (var ev : s) types.add(ev.eventType());
        }
        assertEquals(List.of("x"), types);
        assertEquals("GET", method.get());
        java.net.URI u = java.net.URI.create(target.get());
        assertEquals("/@portal/realtime/subscribe", u.getPath());
        assertTrue(u.getQuery().contains("topic=collections.orders.*"), u.getQuery());
        assertTrue(u.getQuery().contains("filter={\"status\":\"open\"}"), u.getQuery());
    }

    // ---- streams ----

    @Test
    void streamsControlAndDataPlane() throws IOException {
        project().streams().create("Orders", 1073741824L, 86400L);
        expect("POST", "/@portal/data-streams/user", "{\"displayName\":\"Orders\",\"maxBytes\":1073741824,\"retentionSeconds\":86400}");
        project().streams().list();
        expect("GET", "/@portal/data-streams/user", null);
        project().streams().get("orders");
        expect("GET", "/@portal/data-streams/user/orders", null);
        project().streams().delete("orders");
        expect("DELETE", "/@portal/data-streams/user/orders", null);
        project().streams().publish("orders", Map.of("id", 1), "eu.new");
        expect("POST", "/@portal/data-streams/user/orders/publish", "{\"data\":{\"id\":1},\"subject\":\"eu.new\"}");
        project().streams().replay("orders", 10L, 50L, 25);
        expect("POST", "/@portal/data-streams/user/orders/replay", "{\"fromSequence\":10,\"untilSequence\":50,\"limit\":25}");
    }

    @Test
    void streamsSubscribeIsASseGetWithFromSequence() throws IOException {
        responseType = "text/event-stream";
        responseJson = "";
        // The first connection is opened by subscribe; close before it reconnects.
        project().streams().subscribe("orders", "5").close();
        expect("GET", "/@portal/data-streams/user/orders/subscribe?fromSequence=5", null);
    }

    // ---- migrations / gate / storage bodies ----

    @Test
    void migrationsBodies() throws IOException {
        var m = new Migrations.Migration(1, "init", List.of(), List.of());
        project().migrations().apply(List.of(m));
        expect("POST", "/@portal/migrations/apply", "{\"migrations\":[{\"version\":1,\"slug\":\"init\",\"up\":[],\"down\":[]}]}");
        project().migrations().queue(List.of(m));
        expect("POST", "/@portal/migrations/queue", "{\"migrations\":[{\"version\":1,\"slug\":\"init\",\"up\":[],\"down\":[]}]}");
        project().migrations().rejectPending("pm_1", "no");
        expect("POST", "/@portal/migrations/pending/pm_1/reject", "{\"reason\":\"no\"}");
        project().migrations().rejectPending("pm_1", null);
        expect("POST", "/@portal/migrations/pending/pm_1/reject", null);
        project().migrations().listPending();
        expect("GET", "/@portal/migrations/pending", null);
        project().migrations().deletePending("pm_1");
        expect("DELETE", "/@portal/migrations/pending/pm_1", null);
        project().migrations().get("1");
        expect("GET", "/@portal/migrations/1", null);
    }

    @Test
    void gateBodies() throws IOException {
        project().gate().signIn(new Gate.SignInInput("a@b.co", "pw"));
        expect("POST", "/@portal/gate/signin", "{\"email\":\"a@b.co\",\"password\":\"pw\"}");
        project().gate().signUp(new Gate.SignUpInput("a@b.co", "pw", null));
        expect("POST", "/@portal/gate/signup", "{\"email\":\"a@b.co\",\"password\":\"pw\"}");
        project().gate().signOut(new Gate.SignOutInput("rt"));
        expect("POST", "/@portal/gate/signout", "{\"refreshToken\":\"rt\"}");
        project().gate().refreshToken(new Gate.RefreshTokenInput("rt"));
        expect("POST", "/@portal/gate/refresh", "{\"refreshToken\":\"rt\"}");
        project().gate().whoami();
        expect("GET", "/@portal/gate/whoami", null);
    }

    @Test
    void storageBodies() throws IOException {
        project().storage().createBucket("docs", Map.of("public", false));
        expect("POST", "/@portal/storage/buckets", "{\"name\":\"docs\",\"public\":false}");
        project().storage().updateBucket("docs", Map.of("public", true));
        expect("PATCH", "/@portal/storage/buckets/docs", "{\"public\":true}");
        project().storage().from("docs").createSignedUrl("a.txt", 600);
        expect("POST", "/@portal/storage/buckets/docs/objects/sign", "{\"path\":\"a.txt\",\"expiresIn\":600}");
        project().storage().from("docs").remove("a.txt");
        expect("DELETE", "/@portal/storage/buckets/docs/objects?path=a.txt", null);
    }

    @Test
    void vaultSecretBodies() throws IOException {
        project().kibo().vault().secrets().update("db/pw", "de01", new Kibo.UpdateSecretInput("y"));
        assertEquals("PUT", method.get());
        assertEquals(splitQuery("/@portal/vault/secrets/db%2Fpw?region=de01"), splitQuery(target.get()));
        assertEquals(M.readTree("{\"value\":\"eQ==\"}"), M.readTree(bodyBytes.get()));
        project().kibo().vault().secrets().move("db/pw", "de01", "db/new");
        assertEquals("POST", method.get());
        assertEquals(splitQuery("/@portal/vault/secrets/db%2Fpw?region=de01&op=move"), splitQuery(target.get()));
        assertEquals(M.readTree("{\"newPath\":\"db/new\"}"), M.readTree(bodyBytes.get()));
    }

    // ---- approvals, approval policies, schema collections ----

    @Test
    void approvalsWireShapes() throws IOException {
        project().approvals().list();
        expect("GET", "/@portal/pending-approvals", null);
        project().approvals().list("pending_approval", 25);
        expect("GET", "/@portal/pending-approvals?status=pending_approval&limit=25", null);
        project().approvals().get("apr_1");
        expect("GET", "/@portal/pending-approvals/apr_1", null);
        project().approvals().approve("apr_1");
        expect("POST", "/@portal/pending-approvals/apr_1/approve", "{}");
        project().approvals().approve("apr_1", "looks right");
        expect("POST", "/@portal/pending-approvals/apr_1/approve", "{\"reason\":\"looks right\"}");
        project().approvals().reject("apr_1", "not now");
        expect("POST", "/@portal/pending-approvals/apr_1/reject", "{\"reason\":\"not now\"}");
        project().approvals().bulk("approve", Map.of("eq", List.of("change.table", "orders")), null);
        expect("POST", "/@portal/pending-approvals/bulk", "{\"action\":\"approve\",\"criteria\":{\"eq\":[\"change.table\",\"orders\"]}}");
        project().approvals().bulk("reject", Map.of("eq", List.of("change.table", "orders")), "stale");
        expect("POST", "/@portal/pending-approvals/bulk", "{\"action\":\"reject\",\"criteria\":{\"eq\":[\"change.table\",\"orders\"]},\"reason\":\"stale\"}");
    }

    @Test
    void approvalAnswersAreParsed() {
        responseJson = "{\"data\":{\"id\":\"apr_1\",\"status\":\"pending_approval\",\"collection\":\"orders\",\"operation\":\"update\","
            + "\"makerId\":\"usr_1\",\"proposedPayload\":{\"a\":1},\"targetRecordId\":null,\"approverId\":null,\"decidedAt\":null,"
            + "\"decisionReason\":null,\"createdAt\":\"t\",\"expiresAt\":null},\"meta\":{}}";
        Approvals.PendingChange c = project().approvals().approve("apr_1").data();
        assertEquals("pending_approval", c.status());
        assertEquals("usr_1", c.makerId());
        responseJson = "{\"data\":{\"attestationId\":\"apr_x\",\"action\":\"approve\",\"affectedCount\":2,\"affectedIds\":[\"apr_a\",\"apr_b\"],\"reason\":null},\"meta\":{}}";
        Approvals.BulkResult r = project().approvals().bulk("approve", Map.of("eq", List.of("change.table", "orders")), null).data();
        assertEquals(List.of("apr_a", "apr_b"), r.affectedIds());
        assertEquals(2, r.affectedCount());
    }

    @Test
    void approvalPoliciesWireShapes() throws IOException {
        project().approvalPolicies().list();
        expect("GET", "/@portal/approval-policies", null);
        project().approvalPolicies().create(new ApprovalPolicies.CreateInput("orders", "update", "queue",
            Map.of("gt", List.of("change.row.amount", 1000))));
        expect("POST", "/@portal/approval-policies",
            "{\"collection\":\"orders\",\"operation\":\"update\",\"action\":\"queue\",\"predicate\":{\"gt\":[\"change.row.amount\",1000]}}");
        project().approvalPolicies().delete("apo_1");
        expect("DELETE", "/@portal/approval-policies/apo_1", null);
    }

    @Test
    void schemaCollectionsWireShapes() throws IOException {
        project().schema().collections().list();
        expect("GET", "/@portal/schema/collections", null);
        project().schema().collections().list(10, "b3JkZXJz");
        expect("GET", "/@portal/schema/collections?limit=10&cursor=b3JkZXJz", null);
    }

    @Test
    void schemaCollectionsAnswerIsParsed() {
        responseJson = "{\"data\":[{\"name\":\"orders\",\"rowCount\":120,\"rowCountIsExact\":true,\"columnCount\":8,\"indexCount\":3,"
            + "\"lastMigrationSlug\":null,\"lastMigrationAppliedAt\":null}],\"meta\":{\"cursor\":{\"next\":\"b3JkZXJz\",\"totalCount\":null,\"countMethod\":\"none\"}}}";
        var page = project().schema().collections().list();
        assertEquals("orders", page.data().get(0).name());
        assertEquals(120L, page.data().get(0).rowCount());
        assertEquals("b3JkZXJz", page.meta().cursor().next());
    }
}
