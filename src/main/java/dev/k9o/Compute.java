package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Compute namespace — {@code client.compute()} (Compute
 * LLMs registry).
 *
 * <p>Java idiom: camelCase + Envelope return + throws
 * KError. {@code invokeStream} returns an {@link EventStream}
 * iterated via {@code for (var chunk : stream)}.
 *
 * <p>K9O_LLM_PROVIDER_UNAVAILABLE (503) coined; others reused.
 */
public final class Compute {

    /** Curated 9-model closed enum (extensible v2.x). */
    public static final List<String> LLM_MODEL_IDS = List.of(
        "mistral-7b", "mistral-8x7b", "llama-3-8b", "llama-3-70b",
        "claude-3-5-sonnet", "gpt-4o", "gpt-4o-mini",
        "text-embedding-3-small", "text-embedding-3-large"
    );

    private final HttpTransport.Config config;
    private final LLMs llms;

    Compute(HttpTransport.Config config) {
        this.config = config;
        this.llms = new LLMs(config);
    }

    public LLMs llms() { return llms; }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String requireProjectSlug(HttpTransport.Config config) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT",
                "compute.llms operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /** Model metadata +. */
    public record LLMModel(
        @JsonProperty("id") String id,
        @JsonProperty("providerClass") String providerClass,
        @JsonProperty("modality") String modality,
        @JsonProperty("tierGate") String tierGate,
        @JsonProperty("contextWindow") Long contextWindow,
        @JsonProperty("capabilities") List<String> capabilities,
        @JsonProperty("costPerMillionInputTokens") Double costPerMillionInputTokens,
        @JsonProperty("costPerMillionOutputTokens") Double costPerMillionOutputTokens
    ) {}

    /** Synchronous inference result. */
    public record LLMResponse(
        @JsonProperty("completion") String completion,
        @JsonProperty("tokensUsed") Long tokensUsed,
        @JsonProperty("latencyMs") Long latencyMs
    ) {}

    /** Streaming per-token chunk. */
    public record LLMStreamChunk(
        @JsonProperty("delta") String delta,
        @JsonProperty("done") Boolean done,
        @JsonProperty("tokensUsed") Long tokensUsed
    ) {}

    /** Input body for invoke / invokeStream. */
    public record LLMInvokeInput(
        @JsonProperty("prompt") String prompt,
        @JsonProperty("params") Map<String, Object> params
    ) {}

    public static final class LLMs {
        private final HttpTransport.Config config;

        LLMs(HttpTransport.Config config) { this.config = config; }

        public Envelope<List<LLMModel>> list(String modality) {
            String slug = requireProjectSlug(config);
            Map<String, String> q = modality == null ? null : Map.of("modality", modality);
            return HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "compute/llms"), null, q,
                new TypeReference<Envelope<List<LLMModel>>>() {});
        }

        public Envelope<LLMModel> read(String modelId) {
            String slug = requireProjectSlug(config);
            return HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "compute/llms/" + enc(modelId)),
                null, null, new TypeReference<Envelope<LLMModel>>() {});
        }

        /** Synchronous inference (PROJECT_AUTHOR/ADMIN). */
        public Envelope<LLMResponse> invoke(String modelId, LLMInvokeInput input) {
            String slug = requireProjectSlug(config);
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "compute/llms/" + enc(modelId) + "/invoke"),
                input, null, new TypeReference<Envelope<LLMResponse>>() {});
        }

        /**
         * Streaming inference (SSE per-token).
         * Iterate via {@code for (var chunk : stream)}; close with
         * try-with-resources to release the connection.
         */
        public EventStream<LLMStreamChunk> invokeStream(String modelId, LLMInvokeInput input) {
            String slug = requireProjectSlug(config);
            return new EventStream<>(
                HttpTransport.openSseLines(config, "POST",
                    UrlGrammar.projectPath(slug, "compute/llms/" + enc(modelId) + "/invoke-stream"),
                    input, null),
                new TypeReference<LLMStreamChunk>() {}
            );
        }
    }
}
