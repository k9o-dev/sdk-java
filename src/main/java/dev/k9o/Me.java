package dev.k9o;

import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Operator-self namespace — {@code client.me()}.
 * Cross-org; routes under {@code /me}. Sub-namespaces:
 * profile / sessions / apiTokens.
 */
public final class Me {

    private final HttpTransport.Config config;
    private final Profile profile;
    private final Sessions sessions;
    private final ApiTokens apiTokens;

    Me(HttpTransport.Config config) {
        this.config = config;
        this.profile = new Profile(config);
        this.sessions = new Sessions(config);
        this.apiTokens = new ApiTokens(config);
    }

    public Profile profile() { return profile; }
    public Sessions sessions() { return sessions; }
    public ApiTokens apiTokens() { return apiTokens; }

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

        /** Revoke one session: {@code POST /auth/sessions/<sessionId>/revoke}. */
        public Envelope<Map<String, Object>> revoke(String sessionId) {
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("auth/sessions/" + enc(sessionId) + "/revoke"),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        /** Revoke all of the operator's sessions: {@code POST /me/sessions/revoke-all}. */
        public Envelope<Map<String, Object>> revokeAll() {
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("me/sessions/revoke-all"),
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

        /**
         * What a token may do; each field narrows the token and {@code null} leaves it unrestricted. {@code operations}
         * entries are read, write or admin. The field names are the gateway's.
         */
        public record ScopeManifest(
            @com.fasterxml.jackson.annotation.JsonProperty("operations") java.util.List<String> operations,
            @com.fasterxml.jackson.annotation.JsonProperty("projects") java.util.List<String> projects,
            @com.fasterxml.jackson.annotation.JsonProperty("resourceClasses") java.util.List<String> resourceClasses,
            @com.fasterxml.jackson.annotation.JsonProperty("collections") Map<String, Object> collections,
            @com.fasterxml.jackson.annotation.JsonProperty("cidrs") java.util.List<String> cidrs
        ) {}

        /**
         * Create a personal access token: {@code POST /me/api-tokens} with {@code {label, scopeManifest, ttlSeconds?}}.
         * {@code ttlSeconds} (nullable) is 60 to 31536000. The response carries the plaintext token once.
         */
        public Envelope<Map<String, Object>> create(String label, ScopeManifest scopeManifest, Integer ttlSeconds) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("label", label);
            body.put("scopeManifest", scopeManifest);
            if (ttlSeconds != null) body.put("ttlSeconds", ttlSeconds);
            return HttpTransport.request(config, "POST", UrlGrammar.orgPath("me/api-tokens"),
                body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Map<String, Object>> delete(String tokenId) {
            return HttpTransport.request(config, "DELETE", UrlGrammar.orgPath("me/api-tokens/" + enc(tokenId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }
    }
}
