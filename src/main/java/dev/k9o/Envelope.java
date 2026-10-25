package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Standard envelope returned by every k9o surface on success.
 * Generic over the per-method data shape T.
 *
 * Per Java idiomatic shape: camelCase fields; Jackson handles
 * wire camelCase ↔ Java camelCase translation (both use camelCase
 * already — no rename needed).
 */
public class Envelope<T> {

    private final T data;
    private final Meta meta;

    public Envelope(@JsonProperty("data") T data, @JsonProperty("meta") Meta meta) {
        this.data = data;
        this.meta = meta == null ? new Meta() : meta;
    }

    public T data() {
        return data;
    }

    public Meta meta() {
        return meta;
    }

    /** Structured meta accessor sugar. */
    public static class Meta {
        private final MetaCursor cursor;
        private final MetaSubscription subscription;
        private final MetaIdempotency idempotency;
        private final List<MetaWarning> warnings;
        private final Map<String, Object> cost;
        private final MetaTiming timing;
        private final MetaContext context;

        public Meta() {
            this(null, null, null, List.of(), Map.of(), null, null);
        }

        public Meta(
            @JsonProperty("cursor") MetaCursor cursor,
            @JsonProperty("subscription") MetaSubscription subscription,
            @JsonProperty("idempotency") MetaIdempotency idempotency,
            @JsonProperty("warnings") List<MetaWarning> warnings,
            @JsonProperty("cost") Map<String, Object> cost,
            @JsonProperty("timing") MetaTiming timing,
            @JsonProperty("context") MetaContext context
        ) {
            this.cursor = cursor;
            this.subscription = subscription;
            this.idempotency = idempotency;
            this.warnings = warnings == null ? List.of() : warnings;
            this.cost = cost == null ? Map.of() : cost;
            this.timing = timing;
            this.context = context;
        }

        public MetaCursor cursor() { return cursor; }
        public MetaSubscription subscription() { return subscription; }
        public MetaIdempotency idempotency() { return idempotency; }
        public List<MetaWarning> warnings() { return warnings; }
        public Map<String, Object> cost() { return cost; }
        public MetaTiming timing() { return timing; }
        public MetaContext context() { return context; }
    }

    public record MetaCursor(@JsonProperty("next") String next, @JsonProperty("totalCount") Long totalCount) {}
    public record MetaSubscription(@JsonProperty("isInitial") boolean isInitial) {}
    public record MetaIdempotency(@JsonProperty("replayed") boolean replayed) {}
    public record MetaWarning(@JsonProperty("code") String code, @JsonProperty("message") String message) {}
    public record MetaTiming(@JsonProperty("totalMs") double totalMs) {} // fractional ms (gateway emits e.g. 10.572)
    public record MetaContext(@JsonProperty("requestId") String requestId) {}
}
