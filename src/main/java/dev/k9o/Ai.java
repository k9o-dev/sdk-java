package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI namespace — {@code client.ai()} (platform AI primitives;
 * {@code ai.embed} is a platform primitive, not sugar over {@code context.embed}).
 *
 * <p>Wire mapping (project-plane):
 * {@code embed(model?, input)} → {@code POST /@<slug>/ai/embeddings} with body
 * {@code {model?, input: string | string[]}} →
 * {@code {data: {embeddings, model, dimensions}, meta}}.
 *
 * <p>Bounds (at most 64 inputs, each non-empty, 256 KiB per request, 4
 * requests in flight per project) are enforced by the gateway, not here. The
 * SDK sends the input exactly as given and never puts it in an error.
 */
public final class Ai {

    private final HttpTransport.Config config;

    Ai(HttpTransport.Config config) { this.config = config; }

    private static String requireProjectSlug(HttpTransport.Config config) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT",
                "ai operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /** Embedding result: one vector per input string, in input order. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedResult(
        @JsonProperty("embeddings") List<List<Double>> embeddings,
        @JsonProperty("model") String model,
        @JsonProperty("dimensions") Integer dimensions
    ) {}

    /** Embed one string with the server-default model. */
    public Envelope<EmbedResult> embed(String input) {
        return send(null, input);
    }

    /** Embed one string with an explicit model ({@code null} = server default). */
    public Envelope<EmbedResult> embed(String model, String input) {
        return send(model, input);
    }

    /** Embed a list of strings with the server-default model. */
    public Envelope<EmbedResult> embed(List<String> input) {
        return send(null, input);
    }

    /** Embed a list of strings with an explicit model ({@code null} = server default). */
    public Envelope<EmbedResult> embed(String model, List<String> input) {
        return send(model, input);
    }

    /** POST /@&lt;slug&gt;/ai/embeddings. */
    private Envelope<EmbedResult> send(String model, Object input) {
        String slug = requireProjectSlug(config);
        Map<String, Object> body = new LinkedHashMap<>();
        if (model != null) body.put("model", model);
        body.put("input", input);
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(slug, "ai/embeddings"), body, null,
            new TypeReference<Envelope<EmbedResult>>() {});
    }
}
