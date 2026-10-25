package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Realtime Subscriptions namespace — {@code client.realtime()}
 * (ALL-LIVE primitive).
 *
 * <p>Java idiom: {@link #subscribe} returns an
 * {@link EventStream}{@code <RealtimeEvent>} iterated via
 * {@code for (var event : stream)}. Per
 * the SDK picks the transport; SPLIT (polling) is internal. The JDK
 * stdlib SDK uses SSE primary (no stdlib WebSocket); poll fallback
 * available via {@link #subscribePoll}. The consumer's iteration is
 * transport-agnostic.
 *
 * <p>{@link #replay} is the one-shot historical catch-up.
 */
public final class Realtime {

    private final HttpTransport.Config config;

    Realtime(HttpTransport.Config config) { this.config = config; }

    /** Typed builders for the 6 closed topic families. */
    public static final class Topic {
        private Topic() {}
        public static String collection(String collection) { return "collections." + collection + ".*"; }
        public static String workflowRuns() { return "studio.workflows.runs.*"; }
        public static String queryExecuted(String queryId) { return "studio.queries.executed." + queryId; }
        public static String dashboardRefresh(String dashboardId) { return "studio.dashboards.refresh." + dashboardId; }
        public static String functionInvocations(String functionId) { return "forge.functions.invocations." + functionId; }
        public static String auditOrg(String eventFamily) { return "audit.org." + eventFamily; }
    }

    /** A single realtime event delivered on a subscription. */
    public record RealtimeEvent(
        @JsonProperty("topic") String topic,
        @JsonProperty("type") String type,
        @JsonProperty("sequence") Long sequence,
        @JsonProperty("payload") Object payload,
        @JsonProperty("occurredAt") String occurredAt
    ) {}

    /** Input for {@link #replay}. */
    public record ReplayInput(
        @JsonProperty("topic") String topic,
        @JsonProperty("fromSequence") long fromSequence,
        @JsonProperty("filter") Map<String, Object> filter,
        @JsonProperty("untilSequence") Long untilSequence,
        @JsonProperty("limit") Integer limit
    ) {}

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT",
                "realtime operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /**
     * Subscribe via the SSE transport (primary). Iterate
     * via {@code for (var event : stream)}; close with try-with-resources.
     */
    public EventStream<RealtimeEvent> subscribe(String topic, Map<String, Object> filter) {
        String slug = requireProjectSlug();
        Map<String, String> q = new HashMap<>();
        q.put("topic", topic);
        if (filter != null) {
            try {
                q.put("filter", HttpTransport.JSON.writeValueAsString(filter));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new KError("K9O_INVALID_FILTER", "failed to encode filter: " + e.getMessage());
            }
        }
        return new EventStream<>(
            HttpTransport.openSseLines(config, "GET",
                UrlGrammar.projectPath(slug, "realtime/subscribe"), null, q),
            new TypeReference<RealtimeEvent>() {}
        );
    }

    /**
     * One-shot historical replay (capped 10k server-side).
     * Use the highest observed {@code sequence} as the next
     * {@code fromSequence} for cursor pagination.
     */
    public Envelope<List<RealtimeEvent>> replay(ReplayInput input) {
        String slug = requireProjectSlug();
        if (input.fromSequence() < 0) {
            throw new KError(
                "K9O_INVALID_FILTER",
                "replay fromSequence must be a non-negative integer",
                Map.of("fromSequence", input.fromSequence()),
                "Pass the highest sequence number you have already observed (or 0 to replay from the start).",
                null, null
            );
        }
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(slug, "realtime/replay"), input, null,
            new TypeReference<Envelope<List<RealtimeEvent>>>() {});
    }

    /**
     * Poll-transport fallback (SDK-internal SPLIT). Returns a
     * single batch of pending events via the replay endpoint. Caller can
     * loop, tracking the highest observed {@code sequence}. Use when an
     * SSE proxy or environment blocks streaming responses.
     */
    public Envelope<List<RealtimeEvent>> subscribePoll(String topic, long fromSequence, Map<String, Object> filter) {
        String slug = requireProjectSlug();
        ReplayInput body = new ReplayInput(topic, fromSequence, filter, null, null);
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(slug, "realtime/replay"), body, null,
            new TypeReference<Envelope<List<RealtimeEvent>>>() {});
    }
}
