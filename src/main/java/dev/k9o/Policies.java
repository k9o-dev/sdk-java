package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Policy bundle lifecycle namespace — {@code client.policies()}.
 */
public final class Policies {

    private final HttpTransport.Config config;

    Policies(HttpTransport.Config config) {
        this.config = config;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "policies operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /** Policy bundle record. */
    public record PolicyBundle(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("version") int version,
        @JsonProperty("status") String status,
        @JsonProperty("rules") Map<String, Object> rules,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("publishedAt") String publishedAt,
        @JsonProperty("createdBy") String createdBy
    ) {}

    /** Read the currently-active policy bundle (data null when none active). */
    public Envelope<PolicyBundle> current() {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "policy"),
            null, null, new TypeReference<Envelope<PolicyBundle>>() {});
    }

    public Envelope<java.util.List<PolicyBundle>> list(String status) {
        String slug = requireProjectSlug();
        Map<String, String> q = status != null ? Map.of("status", status) : null;
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "policies"),
            null, q, new TypeReference<Envelope<java.util.List<PolicyBundle>>>() {});
    }

    public Envelope<PolicyBundle> create(String name, Map<String, Object> rules) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "policies"),
            Map.of("name", name, "rules", rules), null, new TypeReference<Envelope<PolicyBundle>>() {});
    }

    /** Validate a rules bundle without persisting it. */
    public Envelope<Map<String, Object>> validate(Map<String, Object> rules) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "policies/validate"),
            Map.of("rules", rules), null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    public Envelope<PolicyBundle> get(String bundleId) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "policies/" + enc(bundleId)),
            null, null, new TypeReference<Envelope<PolicyBundle>>() {});
    }

    public Envelope<PolicyBundle> update(String bundleId, Map<String, Object> patch) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "PATCH", UrlGrammar.projectPath(slug, "policies/" + enc(bundleId)),
            patch, null, new TypeReference<Envelope<PolicyBundle>>() {});
    }

    public Envelope<Map<String, Object>> delete(String bundleId) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "DELETE", UrlGrammar.projectPath(slug, "policies/" + enc(bundleId)),
            null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    public Envelope<PolicyBundle> publish(String bundleId) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "policies/" + enc(bundleId) + "/publish"),
            null, null, new TypeReference<Envelope<PolicyBundle>>() {});
    }
}
