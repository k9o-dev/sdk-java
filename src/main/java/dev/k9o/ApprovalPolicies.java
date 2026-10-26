package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Approval policies namespace: {@code client.approvalPolicies()}, the rules that decide which writes are queued.
 *
 * <p>Wire (project plane): {@code GET}, {@code POST} on {@code /@<slug>/approval-policies} and {@code DELETE} on
 * {@code /@<slug>/approval-policies/<id>}. The first matching policy for a write decides.
 */
public final class ApprovalPolicies {

    private static final Pattern COLLECTION = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$");

    private final HttpTransport.Config config;

    ApprovalPolicies(HttpTransport.Config config) {
        this.config = config;
    }

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    private String path(String tail) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "approvalPolicies operations require projectSlug bound on the client");
        }
        return UrlGrammar.projectPath(config.projectSlug, "approval-policies" + tail);
    }

    /** A rule: writes to {@code collection} with {@code operation} that match {@code predicate} get {@code action}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApprovalPolicy(
        @JsonProperty("id") String id,
        @JsonProperty("collection") String collection,
        /** {@code insert}, {@code update} or {@code delete}. */
        @JsonProperty("operation") String operation,
        @JsonProperty("predicate") Map<String, Object> predicate,
        /** {@code auto_approve} or {@code queue}. */
        @JsonProperty("action") String action,
        @JsonProperty("authoredBy") String authoredBy,
        @JsonProperty("createdAt") String createdAt
    ) {}

    /**
     * Policy to create. {@code collection} matches {@code ^[a-zA-Z_][a-zA-Z0-9_]{0,62}$}; {@code operation} is
     * {@code insert}, {@code update} or {@code delete}; {@code action} is {@code auto_approve} or {@code queue};
     * {@code predicate} is a non-null predicate object such as {@code {"gt": ["change.row.amount", 1000]}}.
     */
    public record CreateInput(String collection, String operation, String action, Map<String, ?> predicate) {}

    /** Answer of {@link #delete}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Deleted(@JsonProperty("id") String id) {}

    /** All policies, oldest first. */
    public Envelope<List<ApprovalPolicy>> list() {
        return HttpTransport.request(config, "GET", path(""), null, null,
            new TypeReference<Envelope<List<ApprovalPolicy>>>() {});
    }

    /** Create a policy. Identical policies are not de-duplicated. */
    public Envelope<ApprovalPolicy> create(CreateInput input) {
        String p = path("");
        if (input == null) throw invalid("input must be an object", Map.of());
        if (input.collection() == null || !COLLECTION.matcher(input.collection()).matches()) {
            throw invalid("collection must match ^[a-zA-Z_][a-zA-Z0-9_]{0,62}$", Map.of());
        }
        String op = input.operation();
        if (!"insert".equals(op) && !"update".equals(op) && !"delete".equals(op)) {
            throw invalid("operation must be \"insert\", \"update\" or \"delete\"", Map.of());
        }
        String action = input.action();
        if (!"auto_approve".equals(action) && !"queue".equals(action)) {
            throw invalid("action must be \"auto_approve\" or \"queue\"", Map.of());
        }
        Map<String, Object> predicate = Approvals.requirePredicate(input.predicate(), "predicate");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", input.collection());
        body.put("operation", op);
        body.put("action", action);
        body.put("predicate", predicate);
        return HttpTransport.request(config, "POST", p, body, null,
            new TypeReference<Envelope<ApprovalPolicy>>() {});
    }

    /** Delete a policy; answers {@code {id}}. */
    public Envelope<Deleted> delete(String id) {
        if (id == null || id.isEmpty()) throw invalid("policy id must be a non-empty string", Map.of());
        return HttpTransport.request(config, "DELETE",
            path("/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")), null, null,
            new TypeReference<Envelope<Deleted>>() {});
    }
}
