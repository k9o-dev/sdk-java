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
 * collection): members / groups (read) / smtp / auditLog / gate (roles +
 * idpProviders) + defaultLLM. Org-plane requests route against the org host
 * without the {@code @<projectSlug>/} prefix.
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

        /** List members filtered by status ({@code active} or {@code suspended}); null sends no filter. */
        public Envelope<List<Map<String, Object>>> list(String status) {
            return list(null, null, null, status, null);
        }

        /**
         * List members. The route is not paged; it filters by {@code userId}, {@code role},
         * {@code acceptanceStatus} ({@code accepted} or {@code pending}), {@code status} and {@code state}.
         * Null arguments are not sent.
         */
        public Envelope<List<Map<String, Object>>> list(String userId, String role, String acceptanceStatus, String status, String state) {
            Map<String, String> q = new HashMap<>();
            if (userId != null) q.put("userId", userId);
            if (role != null) q.put("role", role);
            if (acceptanceStatus != null) q.put("acceptanceStatus", acceptanceStatus);
            if (status != null) q.put("status", status);
            if (state != null) q.put("state", state);
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("members"),
                null, q, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
        }

        public Envelope<Map<String, Object>> invite(String email, String role) {
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("members"),
                Map.of("email", email, "role", role), null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> remove(String userId) {
            return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("members/" + enc(userId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class Groups {
        private final HttpTransport.Config config;
        Groups(HttpTransport.Config config) { this.config = config; }

        /** List groups; {@code userId} (nullable) narrows to the groups that user belongs to. */
        public Envelope<List<Map<String, Object>>> list(String userId) {
            Map<String, String> q = userId != null ? Map.of("userId", userId) : null;
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("groups"),
                null, q, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
        }

        public Envelope<List<Map<String, Object>>> list() {
            return list(null);
        }

        public Envelope<Map<String, Object>> get(String groupId) {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("groups/" + enc(groupId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class OrgAuditLog {
        private final HttpTransport.Config config;
        OrgAuditLog(HttpTransport.Config config) { this.config = config; }

        /** Org-plane audit events. The route reads only {@code type} (event-type prefix) and {@code limit}; null arguments are not sent. */
        public Envelope<List<Map<String, Object>>> query(String type, Integer limit) {
            Map<String, String> q = new HashMap<>();
            if (type != null) q.put("type", type);
            if (limit != null) q.put("limit", String.valueOf(limit));
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("audit-log"),
                null, q, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
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

            public Envelope<List<Map<String, Object>>> list() {
                return HttpTransport.request(config, "GET", UrlGrammar.orgPath("role-grants"),
                    null, null, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
            }

            public Envelope<Map<String, Object>> grant(String userId, String role) {
                return grant(userId, role, null);
            }

            /** Grant an org role; {@code decisionReason} (nullable) is recorded with the grant. */
            public Envelope<Map<String, Object>> grant(String userId, String role, String decisionReason) {
                Map<String, Object> body = new HashMap<>();
                body.put("userId", userId);
                body.put("role", role);
                if (decisionReason != null) body.put("decisionReason", decisionReason);
                return HttpTransport.request(config, "POST", UrlGrammar.orgPath("role-grants"),
                    body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            public Envelope<Map<String, Object>> get(String grantId) {
                return HttpTransport.request(config, "GET", UrlGrammar.orgPath("role-grants/" + enc(grantId)),
                    null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            /** Revoke a grant (soft delete). */
            public Envelope<Map<String, Object>> revoke(String grantId) {
                return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("role-grants/" + enc(grantId)),
                    null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }
        }

        public static final class IdpProviders {
            private final HttpTransport.Config config;
            IdpProviders(HttpTransport.Config config) { this.config = config; }

            public Envelope<List<Map<String, Object>>> list() {
                return HttpTransport.request(config, "GET", UrlGrammar.orgPath("identity-providers"),
                    null, null, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
            }

            /**
             * Body of {@code POST /identity-providers}. {@code idpProvider} is one of google, github, microsoft, oidc,
             * saml, scim, ldap, active_directory, apple, facebook, twitter. {@code clientSecretRef} is a reference to a
             * stored secret, never the secret itself. {@code tierGating} is {@code free_tier_ok} or {@code ridge_plus}.
             * Null fields are not sent.
             */
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            public record CreateIdpInput(
                @com.fasterxml.jackson.annotation.JsonProperty("idpProvider") String idpProvider,
                @com.fasterxml.jackson.annotation.JsonProperty("displayName") String displayName,
                @com.fasterxml.jackson.annotation.JsonProperty("clientId") String clientId,
                @com.fasterxml.jackson.annotation.JsonProperty("clientSecretRef") String clientSecretRef,
                @com.fasterxml.jackson.annotation.JsonProperty("tierGating") String tierGating,
                @com.fasterxml.jackson.annotation.JsonProperty("redirectUri") String redirectUri,
                @com.fasterxml.jackson.annotation.JsonProperty("metadataUrl") String metadataUrl,
                @com.fasterxml.jackson.annotation.JsonProperty("attributeMap") Map<String, Object> attributeMap,
                @com.fasterxml.jackson.annotation.JsonProperty("enabled") Boolean enabled
            ) {
                public CreateIdpInput(String idpProvider, String displayName, String clientId, String clientSecretRef, String tierGating) {
                    this(idpProvider, displayName, clientId, clientSecretRef, tierGating, null, null, null, null);
                }
            }

            public Envelope<Map<String, Object>> create(CreateIdpInput input) {
                return HttpTransport.request(config, "POST", UrlGrammar.orgPath("identity-providers"),
                    input, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            public Envelope<Map<String, Object>> get(String idpId) {
                return HttpTransport.request(config, "GET", UrlGrammar.orgPath("identity-providers/" + enc(idpId)),
                    null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            /** Patch an IdP: any of displayName, clientId, clientSecretRef, redirectUri, metadataUrl, attributeMap, enabled. */
            public Envelope<Map<String, Object>> configure(String idpId, Map<String, Object> patch) {
                return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("identity-providers/" + enc(idpId)),
                    patch, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }

            public Envelope<Map<String, Object>> delete(String idpId) {
                return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("identity-providers/" + enc(idpId)),
                    null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
            }
        }
    }

    public static final class Smtp {
        private final HttpTransport.Config config;
        Smtp(HttpTransport.Config config) { this.config = config; }

        /**
         * SMTP config, as the gateway takes it in the {@code smtpConfig} field of {@code PATCH /org}. The password is
         * never sent: {@code passwordRef} is a reference to a stored secret. {@code fromName} may be null.
         */
        public record SetSmtpInput(
            String host, int port, String username, String passwordRef, String fromAddress, String fromName
        ) {
            Map<String, Object> toMap() {
                Map<String, Object> m = new HashMap<>();
                m.put("host", host);
                m.put("port", port);
                m.put("username", username);
                m.put("passwordRef", passwordRef);
                m.put("fromAddress", fromAddress);
                if (fromName != null) m.put("fromName", fromName);
                return m;
            }
        }

        /** Set or update the org SMTP config: {@code PATCH /org} with {@code {smtpConfig}}. */
        public Envelope<Map<String, Object>> set(SetSmtpInput input) {
            return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("org"),
                Map.of("smtpConfig", input.toMap()), null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        /** Send a diagnostic email to verify the config ({@code subject} is optional). */
        public Envelope<Map<String, Object>> test(String to, String subject) {
            Map<String, Object> body = new HashMap<>();
            body.put("to", to);
            if (subject != null) body.put("subject", subject);
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("org/smtp/test-email"),
                body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        /** Clear the org-level SMTP config: {@code PATCH /org} with {@code {smtpConfig: null}}. */
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

        /** The org-default LLM: {@code {orgId, defaultModelId, updatedAt, updatedBy}}. */
        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public record DefaultLlmInfo(
            @com.fasterxml.jackson.annotation.JsonProperty("orgId") String orgId,
            @com.fasterxml.jackson.annotation.JsonProperty("defaultModelId") String defaultModelId,
            @com.fasterxml.jackson.annotation.JsonProperty("updatedAt") String updatedAt,
            @com.fasterxml.jackson.annotation.JsonProperty("updatedBy") String updatedBy
        ) {}

        /** Read the org-default LLM. {@code GET /default-llm}; any accepted member of the org may read it. */
        public Envelope<DefaultLlmInfo> get() {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("default-llm"),
                null, null, new TypeReference<Envelope<DefaultLlmInfo>>() {});
        }

        /** Set the org-default LLM ({@code PATCH /default-llm}; ORG_ADMIN or ORG_OWNER; tier-gated). */
        public Envelope<DefaultLlmInfo> set(String modelId) {
            return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("default-llm"),
                Map.of("modelId", modelId), null, new TypeReference<Envelope<DefaultLlmInfo>>() {});
        }
    }
}
