package dev.k9o;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-coverage parity tests for the namespaces bringing Java to TS
 * surface parity: migrations / functions / policies / audit / orgs / me /
 * full org. Routes verified against a JDK stdlib HttpServer mock gateway.
 */
class ParityTest {

    HttpServer server;
    String baseUrl;
    final AtomicReference<String> lastMethod = new AtomicReference<>();
    final AtomicReference<String> lastPath = new AtomicReference<>();
    final AtomicReference<String> lastBody = new AtomicReference<>();

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

    void mock(String path, String json) {
        server.createContext(path, ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastPath.set(ex.getRequestURI().toString());
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    K9o.K9oClient projectClient() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build());
    }

    K9o.K9oClient orgClient() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
    }

    @Test
    void migrationsApplyAndPending() {
        mock("/@portal/migrations/apply", "{\"data\":{\"applied\":[]},\"meta\":{}}");
        mock("/@portal/migrations/pending/pend_x/apply",
            "{\"data\":{\"version\":1,\"slug\":\"init\",\"checksum\":\"c\",\"appliedAt\":\"t\"},\"meta\":{}}");
        var c = projectClient();
        var migration = new Migrations.Migration(
            1, "init",
            List.of(Migrations.Operation.createTable(new Migrations.CreateTableOp(
                "posts",
                List.of(
                    new Migrations.ColumnSpec("id", "uuid", true, null, null, null, null, null, null),
                    new Migrations.ColumnSpec("ssn", "text", null, true, null, null, null,
                        Migrations.Encrypted.of("vault", true), null)
                )
            ))),
            List.of(Migrations.Operation.dropTable("posts"))
        );
        c.migrations().apply(List.of(migration));
        assertEquals("POST", lastMethod.get());
        assertEquals("/@portal/migrations/apply", lastPath.get());
        var applied = c.migrations().applyPending("pend_x").data();
        assertEquals(1, applied.version());
    }

    @Test
    void functionsDeployAndInvoke() {
        mock("/@portal/functions",
            "{\"data\":{\"id\":\"fn_x\",\"name\":\"greet\",\"runtime\":\"bun\",\"status\":\"deploying\",\"tier\":\"low\",\"createdAt\":\"t\"},\"meta\":{}}");
        mock("/@portal/functions/greet/invoke", "{\"data\":{\"ok\":true},\"meta\":{}}");
        var c = projectClient();
        var f = c.functions().deploy(new Functions.DeployFunctionInput("greet", "bun", "...", null, null)).data();
        assertEquals("greet", f.name());
        c.functions().invoke("greet", Map.of("name", "World"));
        assertEquals("/@portal/functions/greet/invoke", lastPath.get());
    }

    @Test
    void policiesCreateAndPublish() {
        String bundle = "{\"data\":{\"id\":\"pol_x\",\"name\":\"rls\",\"version\":1,\"status\":\"draft\",\"rules\":{},\"createdAt\":\"t\",\"createdBy\":\"u\"},\"meta\":{}}";
        mock("/@portal/policies", bundle);
        mock("/@portal/policies/pol_x/publish", bundle);
        var c = projectClient();
        var created = c.policies().create("rls", Map.of("a", 1)).data();
        assertEquals("pol_x", created.id());
        c.policies().publish("pol_x");
        assertEquals("/@portal/policies/pol_x/publish", lastPath.get());
    }

    @Test
    void auditQuery() {
        mock("/@portal/audit-log", "{\"data\":[],\"meta\":{}}");
        var c = projectClient();
        c.audit().query(new Audit.QueryOpts(null, null, "data.write.access.insert", null, null, null, 50, null));
        assertTrue(lastPath.get().startsWith("/@portal/audit-log"));
        assertTrue(lastPath.get().contains("type=data.write.access.insert"));
    }

    @Test
    void orgsCollection() {
        mock("/orgs", "{\"data\":[],\"meta\":{}}");
        mock("/orgs/org_x/restore", "{\"data\":{\"id\":\"org_x\",\"slug\":\"acme\",\"name\":\"Acme\",\"status\":\"active\",\"createdAt\":\"t\"},\"meta\":{}}");
        var c = orgClient();
        c.orgs().list();
        assertEquals("/orgs", lastPath.get());
        var restored = c.orgs().restore("org_x").data();
        assertEquals("acme", restored.slug());
    }

    @Test
    void meNamespaces() {
        mock("/me", "{\"data\":{\"id\":\"u_x\"},\"meta\":{}}");
        mock("/me/sessions", "{\"data\":{\"revoked\":3},\"meta\":{}}");
        mock("/me/api-tokens", "{\"data\":{\"token\":\"k9o_x\"},\"meta\":{}}");
        var c = orgClient();
        c.me().profile().get();
        assertEquals("/me", lastPath.get());
        c.me().sessions().revokeAll();
        assertEquals("DELETE", lastMethod.get());
        c.me().apiTokens().create("ci", null, null);
        assertEquals("/me/api-tokens", lastPath.get());
    }

    @Test
    void orgFullSurfaces() {
        mock("/members", "{\"data\":{},\"meta\":{}}");
        mock("/org-role-grants", "{\"data\":{},\"meta\":{}}");
        mock("/org", "{\"data\":{},\"meta\":{}}");
        mock("/org/default-llm", "{\"data\":\"mistral-7b\",\"meta\":{}}");
        var c = orgClient();
        c.org().members().invite("x@y.com", "ORG_MEMBER");
        assertEquals("POST", lastMethod.get());
        assertEquals("/members", lastPath.get());
        c.org().gate().roles().grant("u_a", "ORG_ADMIN");
        assertEquals("/org-role-grants", lastPath.get());
        c.org().smtp().set(new Org.Smtp.SetSmtpInput("smtp.x.com", 587, "u", "p", "n@x.com", "X", "starttls", null));
        assertEquals("PATCH", lastMethod.get());
        assertTrue(lastBody.get().contains("smtpConfig"));
        var llm = c.org().defaultLLM().get().data();
        assertEquals("mistral-7b", llm);
    }
}
