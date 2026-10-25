package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Function lifecycle namespace — {@code client.functions()}.
 */
public final class Functions {

    private final HttpTransport.Config config;

    Functions(HttpTransport.Config config) {
        this.config = config;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "functions operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /** Function record returned by gateway endpoints. */
    public record FunctionRecord(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("runtime") String runtime,
        @JsonProperty("status") String status,
        @JsonProperty("tier") String tier,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt
    ) {}

    /** Deploy input. */
    public record DeployFunctionInput(
        @JsonProperty("name") String name,
        @JsonProperty("runtime") String runtime,
        @JsonProperty("code") String code,
        @JsonProperty("tier") String tier,
        @JsonProperty("config") Map<String, Object> config
    ) {}

    public Envelope<FunctionRecord> deploy(DeployFunctionInput input) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "functions"),
            input, null, new TypeReference<Envelope<FunctionRecord>>() {});
    }

    public Envelope<java.util.List<FunctionRecord>> list(String status) {
        String slug = requireProjectSlug();
        Map<String, String> q = status != null ? Map.of("status", status) : null;
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "functions"),
            null, q, new TypeReference<Envelope<java.util.List<FunctionRecord>>>() {});
    }

    public Envelope<FunctionRecord> get(String name) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "functions/" + enc(name)),
            null, null, new TypeReference<Envelope<FunctionRecord>>() {});
    }

    public Envelope<Map<String, Object>> delete(String name) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "DELETE", UrlGrammar.projectPath(slug, "functions/" + enc(name)),
            null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    /** Invoke a function; payload is passed to the runtime verbatim. */
    public Envelope<Map<String, Object>> invoke(String name, Object payload) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "functions/" + enc(name) + "/invoke"),
            payload, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }
}
