package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Project-plane audit log namespace — {@code client.audit()}.
 * Distinct from {@code client.org().auditLog()} + {@code client.me().auditLog()}.
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

    /** Optional audit-query filters (null fields are omitted). */
    public record QueryOpts(
        String since, String until, String type, String actorId,
        String targetKind, String targetId, Integer limit, String cursor
    ) {
        public static QueryOpts none() {
            return new QueryOpts(null, null, null, null, null, null, null, null);
        }

        Map<String, String> toQuery() {
            Map<String, String> q = new HashMap<>();
            if (since != null) q.put("since", since);
            if (until != null) q.put("until", until);
            if (type != null) q.put("type", type);
            if (actorId != null) q.put("actorId", actorId);
            if (targetKind != null) q.put("targetKind", targetKind);
            if (targetId != null) q.put("targetId", targetId);
            if (limit != null) q.put("limit", String.valueOf(limit));
            if (cursor != null) q.put("cursor", cursor);
            return q;
        }
    }

    /** Query the audit log (reverse-chronological; filters AND-compose). */
    public Envelope<List<AuditEvent>> query(QueryOpts opts) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "audit-log"),
            null, opts.toQuery(), new TypeReference<Envelope<List<AuditEvent>>>() {});
    }
}
