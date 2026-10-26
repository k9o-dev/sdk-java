package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * <p>camelCase + Envelope return + throws KError. {@code invokeStream}
 * returns an {@link EventStream} iterated via {@code for (var chunk : stream)}.
 * External-provider models answer 501 {@code K9O_NOT_IMPLEMENTED} until the provider client is built;
 * K9O_LLM_PROVIDER_UNAVAILABLE (503) is the other model-specific code.
 */
public final class Compute {

    /** Curated closed set of model ids. */
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

    /** Model metadata, as the gateway lists it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LLMModel(
        @JsonProperty("id") String id,
        @JsonProperty("provider") String provider,
        @JsonProperty("modality") String modality,
        @JsonProperty("minTier") String minTier,
        @JsonProperty("inputCostPerMillion") Double inputCostPerMillion,
        @JsonProperty("outputCostPerMillion") Double outputCostPerMillion
    ) {}

    /** Token counts the model server reported for a call. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LLMUsage(
        @JsonProperty("promptTokens") Long promptTokens,
        @JsonProperty("completionTokens") Long completionTokens,
        @JsonProperty("totalTokens") Long totalTokens
    ) {}

    /** Synchronous inference result from {@code /invoke}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LLMResponse(
        @JsonProperty("modelId") String modelId,
        @JsonProperty("content") String content,
        @JsonProperty("finishReason") String finishReason,
        @JsonProperty("usage") LLMUsage usage,
        @JsonProperty("usageType") String usageType
    ) {}

    /**
     * One chunk of the {@code /invoke-stream} response: the model server's OpenAI-style chat-completion chunk,
     * passed through unchanged. Incremental text is {@code choices[0].delta.content}; the final chunk may carry
     * {@code usage}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LLMStreamChunk(
        @JsonProperty("id") String id,
        @JsonProperty("model") String model,
        @JsonProperty("choices") List<Choice> choices,
        @JsonProperty("usage") Map<String, Object> usage
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Choice(
            @JsonProperty("index") Integer index,
            @JsonProperty("delta") Delta delta,
            @JsonProperty("finish_reason") String finishReason
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Delta(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content
        ) {}

        /** The text carried by this chunk, or an empty string. */
        public String text() {
            if (choices == null || choices.isEmpty() || choices.get(0).delta() == null) return "";
            String c = choices.get(0).delta().content();
            return c == null ? "" : c;
        }
    }

    /**
     * Input for invoke / invokeStream. The gateway reads {@code prompt} and, flat in the same body, the optional
     * {@code maxTokens} (1 to 32000), {@code temperature} (0 to 2) and {@code usageType}; {@code params} carries
     * those optional keys and is merged into the body, so a key the gateway does not read is the caller's error.
     */
    public record LLMInvokeInput(
        String prompt,
        Map<String, Object> params
    ) {
        public LLMInvokeInput(String prompt) {
            this(prompt, null);
        }

        Map<String, Object> toBody() {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("prompt", prompt);
            if (params != null) body.putAll(params);
            return body;
        }
    }

    public static final class LLMs {
        private final HttpTransport.Config config;

        LLMs(HttpTransport.Config config) { this.config = config; }

        /** List the models available to the project tier. {@code GET /@<slug>/compute/llms} (the route takes no filters). */
        public Envelope<List<LLMModel>> list() {
            String slug = requireProjectSlug(config);
            return HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "compute/llms"), null, null,
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
                input.toBody(), null, new TypeReference<Envelope<LLMResponse>>() {});
        }

        /**
         * Streaming inference (SSE; the model server's OpenAI-style chunks).
         * Iterate via {@code for (var chunk : stream)}; close with
         * try-with-resources to release the connection.
         */
        public EventStream<LLMStreamChunk> invokeStream(String modelId, LLMInvokeInput input) {
            String slug = requireProjectSlug(config);
            return new EventStream<>(
                HttpTransport.openSseLines(config, "POST",
                    UrlGrammar.projectPath(slug, "compute/llms/" + enc(modelId) + "/invoke-stream"),
                    input.toBody(), null),
                new TypeReference<LLMStreamChunk>() {}
            );
        }
    }
}
