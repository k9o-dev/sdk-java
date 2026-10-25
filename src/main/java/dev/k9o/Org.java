package dev.k9o;

import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Org-plane bound namespace — {@code client.org()}.
 *
 * <p>Singular bound-org namespace (distinct from {@code client.orgs()}
 * collection): members / groups / smtp / auditLog / gate (roles +
 * idpProviders) + defaultLLM. Org-plane URL grammar
 * routes against the org subdomain without the {@code @<projectSlug>/} prefix.
 */
public final class Org {

    private final HttpTransport.Config config;
    private final Members members;
    private final Groups groups;
    private final Smtp smtp;
    private final OrgAuditLog auditLog;
    private final Gate gate;
    private final DefaultLLM defaultLLM;

    Org(HttpTransport.Config config) {
        this.config = config;
        this.members = new Members(config);
        this.groups = new Groups(config);
        this.smtp = new Smtp(config);
        this.auditLog = new OrgAuditLog(config);
        this.gate = new Gate(config);
        this.defaultLLM = new DefaultLLM(config);
    }

    public Members members() { return members; }
    public Groups groups() { return groups; }
    public Smtp smtp() { return smtp; }
    public OrgAuditLog auditLog() { return auditLog; }
    public Gate gate() { return gate; }
    public DefaultLLM defaultLLM() { return defaultLLM; }

    /** Read the current org (slug + name + status + settings). */
    public Envelope<Map<String, Object>> get() {
        return HttpTransport.request(config, "GET", UrlGrammar.orgPath("org"),
            null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static final class Members {
        private final HttpTransport.Config config;
        Members(HttpTransport.Config config) { this.config = config; }

        public Envelope<List<Map<String, Object>>> list(String status) {
            Map<String, String> q = status != null ? Map.of("status", status) : null;
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("members"),
                null, q, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
        }

        public Envelope<Map<String, Object>> invite(String email, String role) {
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("members"),
                Map.of("email", email, "role", role), null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> get(String userId) {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("members/" + enc(userId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> remove(String userId) {
            return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("members/" + enc(userId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class Groups {
        private final HttpTransport.Config config;
        Groups(HttpTransport.Config config) { this.config = config; }

        public Envelope<List<Map<String, Object>>> list() {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("groups"),
                null, null, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
        }

        public Envelope<Map<String, Object>> create(String name) {
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("groups"),
                Map.of("name", name), null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> get(String groupId) {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("groups/" + enc(groupId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> delete(String groupId) {
            return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("groups/" + enc(groupId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class OrgAuditLog {
        private final HttpTransport.Config config;
        OrgAuditLog(HttpTransport.Config config) { this.config = config; }

        public Envelope<List<Map<String, Object>>> query(Audit.QueryOpts opts) {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("audit-log"),
                null, opts.toQuery(), new TypeReference<Envelope<List<Map<String, Object>>>>() {});
        }
    }

    public static final class Gate {
        private final Roles roles;
        private final IdpProviders idpProviders;

        Gate(HttpTransport.Config config) {
            this.roles = new Roles(config);
            this.idpProviders = new IdpProviders(config);
        }

        public Roles roles() { return roles; }
        public IdpProviders idpProviders() { return idpProviders; }

        public static final class Roles {
            private final HttpTransport.Config config;
            Roles(HttpTransport.Config config) { this.config = config; }

            public Envelope<List<Map<String, Object>>> list(String userId, String role) {
                Map<String, String> q = new HashMap<>();
                if (userId != null) q.put("userId", userId);
                if (role != null) q.put("role", role);
                return HttpTransport.request(config, "GET", UrlGrammar.orgPath("org-role-grants"),
                    null, q, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
            }

            public Envelope<Map<String, Object>> grant(String userId, String role) {
                return HttpTransport.request(config, "POST", UrlGrammar.orgPath("org-role-grants"),
                    Map.of("userId", userId, "role", role), null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            public Envelope<Map<String, Object>> get(String grantId) {
                return HttpTransport.request(config, "GET", UrlGrammar.orgPath("org-role-grants/" + enc(grantId)),
                    null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            public Envelope<Map<String, Object>> revoke(String grantId) {
                return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("org-role-grants/" + enc(grantId)),
                    null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }
        }

        public static final class IdpProviders {
            private final HttpTransport.Config config;
            IdpProviders(HttpTransport.Config config) { this.config = config; }

            public Envelope<List<Map<String, Object>>> list() {
                return HttpTransport.request(config, "GET", UrlGrammar.orgPath("idp-providers"),
                    null, null, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
            }

            public Envelope<Map<String, Object>> create(String name, String kind, Map<String, Object> idpConfig) {
                return HttpTransport.request(config, "POST", UrlGrammar.orgPath("idp-providers"),
                    Map.of("name", name, "kind", kind, "config", idpConfig), null,
                    new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            public Envelope<Map<String, Object>> get(String idpId) {
                return HttpTransport.request(config, "GET", UrlGrammar.orgPath("idp-providers/" + enc(idpId)),
                    null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            public Envelope<Map<String, Object>> configure(String idpId, Map<String, Object> patch) {
                return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("idp-providers/" + enc(idpId)),
                    patch, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            public Envelope<Map<String, Object>> delete(String idpId) {
                return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("idp-providers/" + enc(idpId)),
                    null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }
        }
    }

    public static final class Smtp {
        private final HttpTransport.Config config;
        Smtp(HttpTransport.Config config) { this.config = config; }

        /** SMTP config input (password routed via Kibo enclave). */
        public record SetSmtpInput(
            String host, int port, String username, String password,
            String fromEmail, String fromName, String encryption, String replyToEmail
        ) {
            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();
                m.put("host", host);
                m.put("port", port);
                m.put("username", username);
                m.put("password", password);
                m.put("fromEmail", fromEmail);
                m.put("fromName", fromName);
                m.put("encryption", encryption);
                if (replyToEmail != null) m.put("replyToEmail", replyToEmail);
                return m;
            }
        }

        public Envelope<Map<String, Object>> get() {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("org/smtp"),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> set(SetSmtpInput input) {
            return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("org"),
                Map.of("smtpConfig", input.toMap()), null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> test(String to, String fromOverride) {
            Map<String, Object> body = new HashMap<>();
            body.put("to", to);
            if (fromOverride != null) body.put("fromOverride", fromOverride);
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("org/smtp/test-email"),
                body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> unset() {
            Map<String, Object> body = new HashMap<>();
            body.put("smtpConfig", null);
            return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("org"),
                body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class DefaultLLM {
        private final HttpTransport.Config config;
        DefaultLLM(HttpTransport.Config config) { this.config = config; }

        /**
         * Read the org-default LLM model id (null when unset → gateway
         * defaults to {@code mistral-7b}). ORG_ADMIN; tier-gated.
         */
        public Envelope<String> get() {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("org/default-llm"),
                null, null, new TypeReference<Envelope<String>>() {});
        }

        /** Set the org-default LLM (ORG_ADMIN; tier-gated). */
        public Envelope<String> set(String modelId) {
            return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("org/default-llm"),
                Map.of("modelId", modelId), null, new TypeReference<Envelope<String>>() {});
        }
    }
}
