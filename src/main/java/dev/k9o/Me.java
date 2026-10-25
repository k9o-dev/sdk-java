package dev.k9o;

import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Operator-self namespace — {@code client.me()}.
 * Cross-org; org-plane routes under {@code /me}. Sub-namespaces:
 * profile / sessions / apiTokens / notifications / auditLog / dataExport.
 */
public final class Me {

    private final HttpTransport.Config config;
    private final Profile profile;
    private final Sessions sessions;
    private final ApiTokens apiTokens;
    private final Notifications notifications;
    private final MeAuditLog auditLog;
    private final DataExport dataExport;

    Me(HttpTransport.Config config) {
        this.config = config;
        this.profile = new Profile(config);
        this.sessions = new Sessions(config);
        this.apiTokens = new ApiTokens(config);
        this.notifications = new Notifications(config);
        this.auditLog = new MeAuditLog(config);
        this.dataExport = new DataExport(config);
    }

    public Profile profile() { return profile; }
    public Sessions sessions() { return sessions; }
    public ApiTokens apiTokens() { return apiTokens; }
    public Notifications notifications() { return notifications; }
    public MeAuditLog auditLog() { return auditLog; }
    public DataExport dataExport() { return dataExport; }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static final class Profile {
        private final HttpTransport.Config config;
        Profile(HttpTransport.Config config) { this.config = config; }

        public Envelope<Map<String, Object>> get() {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("me"),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> update(String displayName) {
            Map<String, Object> body = new HashMap<>();
            body.put("displayName", displayName);
            return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("me"),
                body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class Sessions {
        private final HttpTransport.Config config;
        Sessions(HttpTransport.Config config) { this.config = config; }

        public Envelope<java.util.List<Map<String, Object>>> list() {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("me/sessions"),
                null, null, new TypeReference<Envelope<java.util.List<Map<String, Object>>>>() {});
        }

        public Envelope<Map<String, Object>> revoke(String sessionId) {
            return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("me/sessions/" + enc(sessionId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> revokeAll() {
            return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("me/sessions"),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class ApiTokens {
        private final HttpTransport.Config config;
        ApiTokens(HttpTransport.Config config) { this.config = config; }

        public Envelope<java.util.List<Map<String, Object>>> list() {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("me/api-tokens"),
                null, null, new TypeReference<Envelope<java.util.List<Map<String, Object>>>>() {});
        }

        public Envelope<Map<String, Object>> create(String name, Integer expiresInDays, String scope) {
            Map<String, Object> body = new HashMap<>();
            body.put("name", name);
            if (expiresInDays != null) body.put("expiresInDays", expiresInDays);
            if (scope != null) body.put("scope", scope);
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("me/api-tokens"),
                body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> delete(String tokenId) {
            return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("me/api-tokens/" + enc(tokenId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class Notifications {
        private final HttpTransport.Config config;
        Notifications(HttpTransport.Config config) { this.config = config; }

        public Envelope<java.util.List<Map<String, Object>>> list(boolean unreadOnly, Integer limit) {
            Map<String, String> q = new HashMap<>();
            if (unreadOnly) q.put("unreadOnly", "true");
            if (limit != null) q.put("limit", String.valueOf(limit));
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("me/notifications"),
                null, q, new TypeReference<Envelope<java.util.List<Map<String, Object>>>>() {});
        }

        public Envelope<Map<String, Object>> markRead(String notificationId) {
            return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("me/notifications/" + enc(notificationId)),
                Map.of("read", true), null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> markAllRead() {
            return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("me/notifications"),
                Map.of("read", true), null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }

    public static final class MeAuditLog {
        private final HttpTransport.Config config;
        MeAuditLog(HttpTransport.Config config) { this.config = config; }

        public Envelope<java.util.List<Map<String, Object>>> query(Audit.QueryOpts opts) {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("me/audit-log"),
                null, opts.toQuery(), new TypeReference<Envelope<java.util.List<Map<String, Object>>>>() {});
        }
    }

    public static final class DataExport {
        private final HttpTransport.Config config;
        DataExport(HttpTransport.Config config) { this.config = config; }

        public Envelope<Map<String, Object>> request(String format) {
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("me/data-export"),
                Map.of("format", format == null ? "json" : format), null,
                new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> status(String jobId) {
            return HttpTransport.request(config, "GET", UrlGrammar.orgPath("me/data-export/" + enc(jobId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }
}
