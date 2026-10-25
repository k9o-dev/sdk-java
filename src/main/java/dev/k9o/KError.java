package dev.k9o;

import java.util.Collections;
import java.util.Map;

/**
 * KError — the closed-taxonomy error class thrown by every k9o surface
 * on non-2xx gateway responses or SDK-side validation failures.
 *
 * Idiomatic Java shape: extends RuntimeException so
 * callers can use try/catch without checked-exception ceremony. Code-
 * narrowing pattern: caller catches KError and switches on {@code getCode()}.
 */
public class KError extends RuntimeException {

    private final String code;
    private final Map<String, Object> context;
    private final String recommendation;
    private final Integer status;
    private final String requestId;

    public KError(String code, String message) {
        this(code, message, Map.of(), null, null, null);
    }

    public KError(
        String code,
        String message,
        Map<String, Object> context,
        String recommendation,
        Integer status,
        String requestId
    ) {
        super(message);
        this.code = code;
        this.context = context == null ? Map.of() : Collections.unmodifiableMap(context);
        this.recommendation = recommendation;
        this.status = status;
        this.requestId = requestId;
    }

    /** Closed-taxonomy code (e.g. {@code "K9O_TIER_CAP_HIT"}). */
    public String getCode() {
        return code;
    }

    /** Structured per-code payload (unmodifiable). */
    public Map<String, Object> getContext() {
        return context;
    }

    /** Actionable next step; may be null. */
    public String getRecommendation() {
        return recommendation;
    }

    /** HTTP status the gateway returned; null for SDK-side errors. */
    public Integer getStatus() {
        return status;
    }

    /** Gateway-emitted trace id; may be null. */
    public String getRequestId() {
        return requestId;
    }

    /** Convenience constructor — adds a recommendation. */
    public KError withRecommendation(String rec) {
        return new KError(code, getMessage(), context, rec, status, requestId);
    }

    @Override
    public String toString() {
        if (status != null) {
            return String.format("[%s] %s (status=%d)", code, getMessage(), status);
        }
        return String.format("[%s] %s", code, getMessage());
    }
}
