package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pending-change queue namespace: {@code client.approvals()}.
 *
 * <p>Wire (project plane):
 * <ul>
 * <li>{@code list(status, limit)}: {@code GET /@<slug>/pending-approvals}</li>
 * <li>{@code get(id)}: {@code GET /@<slug>/pending-approvals/<id>}</li>
 * <li>{@code approve(id, reason)}: {@code POST /@<slug>/pending-approvals/<id>/approve}</li>
 * <li>{@code reject(id, reason)}: {@code POST /@<slug>/pending-approvals/<id>/reject}</li>
 * <li>{@code bulk(action, criteria, reason)}: {@code POST /@<slug>/pending-approvals/bulk}</li>
 * </ul>
 *
 * <p>{@code list} is not paginated: it returns the newest {@code limit} changes (1 to 200, default 50). Project
 * admins and auditors see the whole queue; other members see only the changes they proposed.
 */
public final class Approvals {

    private final HttpTransport.Config config;

    Approvals(HttpTransport.Config config) {
        this.config = config;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    private String path(String tail) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "approvals operations require projectSlug bound on the client");
        }
        return UrlGrammar.projectPath(config.projectSlug, "pending-approvals" + tail);
    }

    private static String requireId(String id) {
        if (id == null || id.isEmpty()) throw invalid("approval id must be a non-empty string", Map.of());
        return id;
    }

    static String requireReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw invalid("reason is required and must be a non-empty string", Map.of());
        }
        return reason;
    }

    static Map<String, Object> requirePredicate(Map<String, ?> value, String field) {
        if (value == null) throw invalid(field + " must be a predicate object", Map.of());
        return new LinkedHashMap<String, Object>(value);
    }

    /** A change waiting for (or past) a decision. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingChange(
        @JsonProperty("id") String id,
        /** {@code pending_approval}, {@code approved}, {@code rejected}, {@code expired} or {@code redeemed}. */
        @JsonProperty("status") String status,
        @JsonProperty("collection") String collection,
        /** {@code insert}, {@code update} or {@code delete}. */
        @JsonProperty("operation") String operation,
        @JsonProperty("makerId") String makerId,
        @JsonProperty("proposedPayload") Map<String, Object> proposedPayload,
        @JsonProperty("targetRecordId") String targetRecordId,
        @JsonProperty("approverId") String approverId,
        @JsonProperty("decidedAt") String decidedAt,
        @JsonProperty("decisionReason") String decisionReason,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("expiresAt") String expiresAt
    ) {}

    /**
     * Answer of {@link #bulk}. Rows that fail (conflict, apply error) are skipped silently and left out of
     * {@code affectedIds}: compare it with the set you meant to decide.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BulkResult(
        @JsonProperty("attestationId") String attestationId,
        @JsonProperty("action") String action,
        @JsonProperty("affectedCount") int affectedCount,
        @JsonProperty("affectedIds") List<String> affectedIds,
        @JsonProperty("reason") String reason
    ) {}

    /** List pending changes, newest first, all statuses. */
    public Envelope<List<PendingChange>> list() {
        return list(null, null);
    }

    /**
     * List pending changes, newest first. {@code status} (optional) is one of {@code pending_approval},
     * {@code approved}, {@code rejected}, {@code expired}, {@code redeemed}. {@code limit} (optional) is 1 to 200.
     * Not paginated: only {@code limit} caps the result.
     */
    public Envelope<List<PendingChange>> list(String status, Integer limit) {
        if (limit != null && (limit < 1 || limit > 200)) {
            throw invalid("limit must be a whole number from 1 to 200", Map.of("limit", limit));
        }
        Map<String, String> q = new LinkedHashMap<>();
        if (status != null) q.put("status", status);
        if (limit != null) q.put("limit", String.valueOf(limit));
        return HttpTransport.request(config, "GET", path(""), null, q,
            new TypeReference<Envelope<List<PendingChange>>>() {});
    }

    /** One pending change. Members who are not queue admins can read only their own. */
    public Envelope<PendingChange> get(String id) {
        return HttpTransport.request(config, "GET", path("/" + enc(requireId(id))), null, null,
            new TypeReference<Envelope<PendingChange>>() {});
    }

    /** Approve a pending change with no note. */
    public Envelope<PendingChange> approve(String id) {
        return approve(id, null);
    }

    /**
     * Approve a pending change; {@code reason} (optional) is stored with the decision. Read {@code status} on the
     * answer: {@code pending_approval} means the approval was recorded but more approvers are needed and the change
     * has not been applied yet.
     */
    public Envelope<PendingChange> approve(String id, String reason) {
        String p = path("/" + enc(requireId(id)) + "/approve");
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) body.put("reason", reason);
        return HttpTransport.request(config, "POST", p, body, null,
            new TypeReference<Envelope<PendingChange>>() {});
    }

    /** Reject a pending change. {@code reason} is required and must be non-empty. */
    public Envelope<PendingChange> reject(String id, String reason) {
        String p = path("/" + enc(requireId(id)) + "/reject");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", requireReason(reason));
        return HttpTransport.request(config, "POST", p, body, null,
            new TypeReference<Envelope<PendingChange>>() {});
    }

    /**
     * Approve or reject every pending change matching {@code criteria}. {@code action} is {@code approve} or
     * {@code reject}; {@code criteria} is a predicate such as {@code {"eq": ["change.table", "orders"]}};
     * {@code reason} is required for {@code reject}. Rows that fail are skipped without a report, so compare
     * {@code affectedIds} with what you intended.
     */
    public Envelope<BulkResult> bulk(String action, Map<String, ?> criteria, String reason) {
        if (!"approve".equals(action) && !"reject".equals(action)) {
            throw invalid("action must be \"approve\" or \"reject\"", Map.of());
        }
        Map<String, Object> pred = requirePredicate(criteria, "criteria");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("criteria", pred);
        if ("reject".equals(action)) body.put("reason", requireReason(reason));
        else if (reason != null) body.put("reason", reason);
        return HttpTransport.request(config, "POST", path("/bulk"), body, null,
            new TypeReference<Envelope<BulkResult>>() {});
    }
}
