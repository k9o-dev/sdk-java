package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.HashMap;
import java.util.Map;

/**
 * Realtime subscriptions namespace — {@code client.realtime()}.
 *
 * <p>{@link #subscribe} returns an {@link EventStream}{@code <RealtimeEvent>} iterated via
 * {@code for (var event : stream)}. The transport is SSE over {@code GET /@<slug>/realtime/subscribe}. The
 * gateway serves no WebSocket upgrade and no durable replay, so neither is offered; after an {@code event: resync}
 * frame the caller re-reads current state from the data plane and resubscribes. {@code resync} control frames are
 * not events and are skipped by the stream.
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

    /** A single realtime event, as the gateway frames it (wire spelling): {@code topic, eventType, payload, occurredAt}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RealtimeEvent(
        @JsonProperty("topic") String topic,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("payload") Object payload,
        @JsonProperty("occurredAt") String occurredAt
    ) {}

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT",
                "realtime operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /**
     * Subscribe over SSE. Iterate via {@code for (var event : stream)}; close with try-with-resources.
     * {@code filter} (nullable) narrows the topic and composes with row-level security.
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
            new TypeReference<RealtimeEvent>() {},
            e -> e.eventType() != null
        );
    }
}
