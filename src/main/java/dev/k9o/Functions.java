package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionRecord(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("runtime") String runtime,
        @JsonProperty("status") String status,
        @JsonProperty("tier") String tier,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt
    ) {}

    /**
     * Deploy input; property names are the gateway's. The function's bytes are uploaded first
     * ({@link #uploadArtifact} returns the {@code artifactKey}); deploy references that artifact.
     * {@code runtime} is one of bun, python, go, java, kotlin, dotnet, swift, php, ruby, rust, tinygo, zig,
     * assemblyscript. {@code tier} is {@code gateway} (WebAssembly in the gateway) or {@code sandbox}
     * (an OCI image archive). {@code version} is a label for this deploy (1 to 64 characters).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeployFunctionInput(
        @JsonProperty("name") String name,
        @JsonProperty("runtime") String runtime,
        @JsonProperty("tier") String tier,
        @JsonProperty("artifactKey") String artifactKey,
        @JsonProperty("version") String version,
        @JsonProperty("description") String description
    ) {
        public DeployFunctionInput(String name, String runtime, String tier, String artifactKey, String version) {
            this(name, runtime, tier, artifactKey, version, null);
        }
    }

    /** Optional invoke controls; property names are the gateway's. Null fields are not sent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvokeOptions(
        @JsonProperty("background") Boolean background,
        @JsonProperty("onBehalfOf") String onBehalfOf,
        @JsonProperty("confinedTo") String confinedTo
    ) {}

    /** Result of {@link #uploadArtifact}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artifact(@JsonProperty("artifactKey") String artifactKey) {}

    /**
     * Upload a function artifact and get back its content-addressed {@code artifactKey}, to pass to {@link #deploy}.
     * {@code POST /@<projectSlug>/functions/artifacts} with the raw bytes: a WebAssembly module for the gateway tier
     * ({@code kind} "wasm", up to 16 MiB) or an OCI image archive for the sandbox tier ({@code kind} "tar", up to
     * 256 MiB). Uploading the same bytes again returns the same key.
     */
    public Envelope<Artifact> uploadArtifact(byte[] bytes, String kind) {
        String slug = requireProjectSlug();
        String contentType;
        if ("wasm".equals(kind)) contentType = "application/wasm";
        else if ("tar".equals(kind)) contentType = "application/x-tar";
        else throw new KError("K9O_INVALID_FIELD_VALUE", "kind must be \"wasm\" or \"tar\"",
            Map.of("field", "kind", "reason", "unsupported"), null, null, null);
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "functions/artifacts"),
            new HttpTransport.RawBody(bytes, contentType), null, new TypeReference<Envelope<Artifact>>() {});
    }

    /** Deploy or redeploy a function from an uploaded artifact. Requires PROJECT_ADMIN. */
    public Envelope<FunctionRecord> deploy(DeployFunctionInput input) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "functions"),
            input, null, new TypeReference<Envelope<FunctionRecord>>() {});
    }

    /** List functions, optionally filtered by status. */
    public Envelope<java.util.List<FunctionRecord>> list(String status) {
        return list(status, null, null);
    }

    /** List functions (cursor pagination); null arguments are not sent. */
    public Envelope<java.util.List<FunctionRecord>> list(String status, Integer limit, String cursor) {
        String slug = requireProjectSlug();
        Map<String, String> q = new java.util.LinkedHashMap<>();
        if (status != null) q.put("status", status);
        if (limit != null) q.put("limit", String.valueOf(limit));
        if (cursor != null) q.put("cursor", cursor);
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

    /** Invoke a function; {@code payload} is sent as the {@code payload} field of the body and reaches the runtime verbatim. */
    public Envelope<Map<String, Object>> invoke(String name, Object payload) {
        return invoke(name, payload, null);
    }

    /** As {@link #invoke(String, Object)} with the gateway's invoke controls. */
    public Envelope<Map<String, Object>> invoke(String name, Object payload, InvokeOptions options) {
        String slug = requireProjectSlug();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (payload != null) body.put("payload", payload);
        if (options != null) {
            if (options.background() != null) body.put("background", options.background());
            if (options.onBehalfOf() != null) body.put("onBehalfOf", options.onBehalfOf());
            if (options.confinedTo() != null) body.put("confinedTo", options.confinedTo());
        }
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "functions/" + enc(name) + "/invoke"),
            body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }
}
