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

    /**
     * Policy create/update body. {@code policy} is the policy document (sections keyed by resource kind:
     * collections, storage, tools, functions, secrets, streams, analytics) and is required. {@code editorMode} is
     * {@code visual}, {@code json} or {@code unspecified}. Null fields are not sent.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record PolicyInput(
        @JsonProperty("policy") Map<String, Object> policy,
        @JsonProperty("rules") Object rules,
        @JsonProperty("editorMode") String editorMode
    ) {
        public PolicyInput(Map<String, Object> policy) {
            this(policy, null, null);
        }
    }

    /** Validation result for {@link #validate}. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidateResult(
        @JsonProperty("valid") boolean valid,
        @JsonProperty("errors") java.util.List<Map<String, Object>> errors,
        @JsonProperty("warnings") java.util.List<Map<String, Object>> warnings
    ) {}

    /** Create a draft policy bundle. */
    public Envelope<PolicyBundle> create(PolicyInput input) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "policies"),
            input, null, new TypeReference<Envelope<PolicyBundle>>() {});
    }

    /** Lint a candidate policy document without persisting it. */
    public Envelope<ValidateResult> validate(PolicyInput input) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "policies/validate"),
            input, null, new TypeReference<Envelope<ValidateResult>>() {});
    }

    public Envelope<PolicyBundle> get(String bundleId) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "policies/" + enc(bundleId)),
            null, null, new TypeReference<Envelope<PolicyBundle>>() {});
    }

    /** Replace a draft bundle's policy document. */
    public Envelope<PolicyBundle> update(String bundleId, PolicyInput patch) {
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
