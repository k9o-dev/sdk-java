package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Project-plane audit log namespace — {@code client.audit()}.
 * Distinct from {@code client.org().auditLog()}.
 */
public final class Audit {

    private final HttpTransport.Config config;

    Audit(HttpTransport.Config config) {
        this.config = config;
    }

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "audit operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /** Audit event record. */
    public record AuditEvent(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("actorId") String actorId,
        @JsonProperty("targetKind") String targetKind,
        @JsonProperty("targetId") String targetId,
        @JsonProperty("payload") Map<String, Object> payload,
        @JsonProperty("occurredAt") String occurredAt,
        @JsonProperty("requestId") String requestId,
        @JsonProperty("traceId") String traceId
    ) {}

    /**
     * Optional audit-query filters (null fields are omitted); names are the gateway's query parameters.
     * {@code eventType} is the audit-event name (for example {@code data.write.access.insert}); {@code after} and
     * {@code before} are ISO 8601 instants.
     */
    public record QueryOpts(
        String eventType, String actorId, String after, String before, Integer limit, String cursor
    ) {
        public static QueryOpts none() {
            return new QueryOpts(null, null, null, null, null, null);
        }

        Map<String, String> toQuery() {
            Map<String, String> q = new HashMap<>();
            if (eventType != null) q.put("eventType", eventType);
            if (actorId != null) q.put("actorId", actorId);
            if (after != null) q.put("after", after);
            if (before != null) q.put("before", before);
            if (limit != null) q.put("limit", String.valueOf(limit));
            if (cursor != null) q.put("cursor", cursor);
            return q;
        }
    }

    /** Query the audit log (reverse-chronological; filters AND-compose). */
    public Envelope<List<AuditEvent>> query(QueryOpts opts) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "audit-log"),
            null, (opts == null ? QueryOpts.none() : opts).toQuery(), new TypeReference<Envelope<List<AuditEvent>>>() {});
    }
}
