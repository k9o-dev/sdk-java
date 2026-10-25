package dev.k9o;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * HTTP transport using {@link java.net.http.HttpClient} (JDK 11+ stdlib).
 *
 * Per Java idiomatic shape: returns {@code Envelope<T>} on
 * 2xx; throws {@link KError} on non-2xx / transport failure / malformed
 * envelope.
 */
public final class HttpTransport {

    // Forward-compatible: the gateway may add envelope/meta fields (e.g.
    // meta.timing.gatewayMs) the SDK doesn't model yet — tolerate them rather
    // than fail deserialization (matches the lenient default of every other SDK).
    static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private HttpTransport() {}

    /** A body that is already encoded (multipart/form-data); sent as-is with its own content-type, never JSON-encoded. */
    record RawBody(byte[] bytes, String contentType) {}

    /**
     * The shared {@link com.fasterxml.jackson.databind.type.TypeFactory}, exposed so
     * callers in sibling packages (e.g. the data-plane DSL in {@code dev.k9o.data})
     * can build a runtime {@link JavaType} from a {@code Class<T> rowType} and pass it
     * to the {@link #request(Config, String, String, Object, Map, JavaType)} overload.
     */
    public static com.fasterxml.jackson.databind.type.TypeFactory typeFactory() {
        return JSON.getTypeFactory();
    }

    private static final java.util.Set<String> RETRYABLE_METHODS =
        java.util.Set.of("GET", "HEAD", "OPTIONS", "DELETE");

    /** Per-instance config bound on K9oClient. */
    public static class Config {
        public final String baseUrl;
        public final String apiKey;
        public final String orgSlug;
        public final String projectSlug;
        public final Map<String, String> defaultHeaders;
        public final HttpClient httpClient;
        public final RetryConfig retryConfig;

        public Config(String baseUrl, String apiKey, String orgSlug, String projectSlug,
               Map<String, String> defaultHeaders, HttpClient httpClient) {
            this(baseUrl, apiKey, orgSlug, projectSlug, defaultHeaders, httpClient, null);
        }

        public Config(String baseUrl, String apiKey, String orgSlug, String projectSlug,
               Map<String, String> defaultHeaders, HttpClient httpClient, RetryConfig retryConfig) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.orgSlug = orgSlug;
            this.projectSlug = projectSlug;
            this.defaultHeaders = defaultHeaders == null ? Map.of() : defaultHeaders;
            this.httpClient = httpClient == null ? DEFAULT_CLIENT : httpClient;
            this.retryConfig = retryConfig == null ? RetryConfig.defaults() : retryConfig;
        }

        /** Cached regional origin once the probe gave a definite answer; null until then. */
        private String resolvedOrigin;
    }

    /**
     * Issue an HTTP request with retry on transient failures, returning an
     * Envelope<T>. Idempotent methods are retried with backoff per
     * {@code config.retryConfig}; semantic / non-idempotent
     * failures throw immediately.
     */
    public static <T> Envelope<T> request(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        TypeReference<Envelope<T>> envelopeType
    ) {
        return request(config, method, path, body, query, null, envelopeType);
    }

    /**
     * Overload accepting extra per-request headers layered on top of
     * {@code config.defaultHeaders} — e.g. a one-shot challenge header on a
     * single call (kibo vault-secrets {@code reveal}'s {@code X-K9O-Reveal-Challenge}).
     * {@code headers} may be null, matching a plain {@link Map} of no extras.
     */
    public static <T> Envelope<T> request(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        Map<String, String> headers,
        TypeReference<Envelope<T>> envelopeType
    ) {
        // Bridge to the JavaType path. TypeReference resolves to a fully-
        // reified JavaType here because callers supply a concrete envelope
        // type (e.g. Envelope<FunctionRecord>) — no erased type variable.
        return request(config, method, path, body, query, headers,
            JSON.getTypeFactory().constructType(envelopeType));
    }

    /**
     * JavaType variant: deserialize the 2xx envelope using a runtime-built
     * {@link JavaType} rather than a compile-time {@link TypeReference}. The
     * data-plane terminals use this so the caller's {@code Class<T> rowType}
     * actually drives row deserialization (generics are erased, so the
     * {@code TypeReference<Envelope<T>>} form would otherwise yield
     * {@code LinkedHashMap} rows — see FromBuilder).
     */
    public static <T> Envelope<T> request(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        JavaType envelopeType
    ) {
        return request(config, method, path, body, query, null, envelopeType);
    }

    /** JavaType variant of the headers-accepting overload — see
     *  {@link #request(Config, String, String, Object, Map, Map, TypeReference)}. */
    public static <T> Envelope<T> request(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        Map<String, String> headers,
        JavaType envelopeType
    ) {
        return request(config, method, path, body, query, headers, false, envelopeType);
    }

    /** As above; {@code anonymous} sends NO credential (no Authorization / Cookie, even from default headers). */
    static <T> Envelope<T> request(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        Map<String, String> headers,
        boolean anonymous,
        JavaType envelopeType
    ) {
        RetryConfig rc = config.retryConfig;
        KError last = null;
        for (int attempt = 0; attempt <= rc.maxRetries; attempt++) {
            try {
                return requestOnce(config, method, path, body, query, headers, anonymous, envelopeType);
            } catch (KError e) {
                last = e;
                if (attempt == rc.maxRetries || !isRetryable(e, method)) throw e;
                int delayMs = computeDelayMs(rc, attempt);
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last; // unreachable: loop returns or throws
    }

    private static boolean isCredentialHeader(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("authorization") || n.equals("proxy-authorization") || n.equals("cookie");
    }

    private static boolean isRetryable(KError e, String method) {
        if (!RETRYABLE_METHODS.contains(method.toUpperCase())) return false;
        if ("K9O_UPSTREAM_UNAVAILABLE".equals(e.getCode())) return true;
        Integer st = e.getStatus();
        return st != null && (st == 502 || st == 503 || st == 504 || st == 429);
    }

    private static int computeDelayMs(RetryConfig c, int attempt) {
        if ("none".equals(c.backoff)) return 0;
        int base = "linear".equals(c.backoff)
            ? c.initialDelayMs * (attempt + 1)
            : c.initialDelayMs * (1 << attempt);
        int capped = Math.min(base, c.maxDelayMs);
        double jitter = capped * 0.25 * (Math.random() * 2 - 1);
        return Math.max(0, (int) (capped + jitter));
    }

    /** Single-attempt request. {@link #request} wraps this in retry-with-backoff. */
    static <T> Envelope<T> requestOnce(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        TypeReference<Envelope<T>> envelopeType
    ) {
        return requestOnce(config, method, path, body, query, null, envelopeType);
    }

    /** Headers-accepting single-attempt request (TypeReference). */
    static <T> Envelope<T> requestOnce(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        Map<String, String> headers,
        TypeReference<Envelope<T>> envelopeType
    ) {
        return requestOnce(config, method, path, body, query, headers,
            JSON.getTypeFactory().constructType(envelopeType));
    }

    /** JavaType single-attempt request — see {@link #request(Config, String, String, Object, Map, JavaType)}. */
    @SuppressWarnings("unchecked")
    static <T> Envelope<T> requestOnce(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        JavaType envelopeType
    ) {
        return requestOnce(config, method, path, body, query, null, envelopeType);
    }

    /** Headers-accepting single-attempt request (JavaType) — the shared work method every
     *  {@code requestOnce} overload above bridges to. {@code headers} (may be null) are applied
     *  LAST, after {@code accept}/{@code defaultHeaders}/project-org/auth, so a caller-supplied
     *  per-request header can override a default of the same name if needed. */
    @SuppressWarnings("unchecked")
    static <T> Envelope<T> requestOnce(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        Map<String, String> headers,
        JavaType envelopeType
    ) {
        return requestOnce(config, method, path, body, query, headers, false, envelopeType);
    }

    @SuppressWarnings("unchecked")
    static <T> Envelope<T> requestOnce(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query,
        Map<String, String> headers,
        boolean anonymous,
        JavaType envelopeType
    ) {
        String url = buildUrl(resolveRegionalOrigin(config), path, query);
        HttpRequest.Builder reqB = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("accept", "application/json");

        for (Map.Entry<String, String> e : config.defaultHeaders.entrySet()) {
            if (anonymous && isCredentialHeader(e.getKey())) continue;
            reqB.header(e.getKey(), e.getValue());
        }
        if (config.projectSlug != null) reqB.header("x-k9o-project-slug", config.projectSlug);
        if (config.orgSlug != null) reqB.header("x-k9o-org-slug", config.orgSlug);
        if (!anonymous && config.apiKey != null) reqB.header("authorization", "Bearer " + config.apiKey);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (anonymous && isCredentialHeader(e.getKey())) continue;
                if (e.getValue() != null) reqB.header(e.getKey(), e.getValue());
            }
        }

        HttpRequest.BodyPublisher bodyPub;
        if (body instanceof RawBody raw) {
            // Pre-encoded body (multipart): the caller owns the content-type (it carries the boundary).
            reqB.header("content-type", raw.contentType());
            bodyPub = HttpRequest.BodyPublishers.ofByteArray(raw.bytes());
        } else if (body != null) {
            reqB.header("content-type", "application/json");
            try {
                bodyPub = HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            } catch (JsonProcessingException e) {
                throw new KError("K9O_INTERNAL_ERROR", "failed to marshal request body: " + e.getMessage());
            }
        } else {
            bodyPub = HttpRequest.BodyPublishers.noBody();
        }
        reqB.method(method, bodyPub);

        HttpResponse<String> response;
        try {
            response = config.httpClient.send(reqB.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new KError(
                "K9O_UPSTREAM_UNAVAILABLE",
                "Network error reaching " + url + ": " + e.getMessage(),
                Map.of("url", url),
                "Check connectivity to the gateway; retry with backoff if transient.",
                null, null
            );
        }

        int status = response.statusCode();
        String respBody = response.body();

        if (status == 204) {
            return new Envelope<>(null, new Envelope.Meta());
        }

        if (status >= 200 && status < 300) {
            try {
                return (Envelope<T>) JSON.readValue(respBody, envelopeType);
            } catch (JsonProcessingException e) {
                throw new KError(
                    "K9O_INTERNAL_ERROR",
                    "Malformed envelope from " + url + ": " + e.getMessage(),
                    Map.of("url", url, "status", status), null, status, null
                );
            }
        }

        // Non-2xx — try KError wire shape
        try {
            Map<String, Object> wire = JSON.readValue(respBody, new TypeReference<Map<String, Object>>() {});
            if (wire.get("code") instanceof String code && wire.get("message") instanceof String message) {
                Map<String, Object> context = wire.get("context") instanceof Map<?, ?> c
                    ? (Map<String, Object>) c : Map.of();
                String recommendation = wire.get("recommendation") instanceof String r ? r : null;
                String requestId = wire.get("requestId") instanceof String rid ? rid : null;
                throw new KError(code, message, context, recommendation, status, requestId);
            }
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        throw new KError(
            "K9O_INTERNAL_ERROR",
            "Upstream returned " + status + " with non-KError body",
            Map.of("url", url, "status", status),
            null, status, null
        );
    }

    /**
     * Open an SSE stream and return a lazy {@code Stream<String>} of body
     * lines. Used by streaming surfaces — Compute LLMs invokeStream
     * + Realtime subscribe SSE transport.
     * Caller must {@code .close()} the returned stream (or use
     * try-with-resources) to release the connection.
     *
     * <p>Throws {@link KError} on non-2xx (KError wire shape on body) or
     * transport failure. On 2xx, lines stream from the response body
     * (InputStream-backed) — close the stream to terminate.
     */
    public static Stream<String> openSseLines(
        Config config,
        String method,
        String path,
        Object body,
        Map<String, String> query
    ) {
        String url = buildUrl(resolveRegionalOrigin(config), path, query);
        HttpRequest.Builder reqB = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("accept", "text/event-stream");
        for (Map.Entry<String, String> e : config.defaultHeaders.entrySet()) {
            reqB.header(e.getKey(), e.getValue());
        }
        if (config.projectSlug != null) reqB.header("x-k9o-project-slug", config.projectSlug);
        if (config.orgSlug != null) reqB.header("x-k9o-org-slug", config.orgSlug);
        if (config.apiKey != null) reqB.header("authorization", "Bearer " + config.apiKey);

        HttpRequest.BodyPublisher bodyPub;
        if (body != null) {
            reqB.header("content-type", "application/json");
            try {
                bodyPub = HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            } catch (JsonProcessingException e) {
                throw new KError("K9O_INTERNAL_ERROR", "failed to marshal request body: " + e.getMessage());
            }
        } else {
            bodyPub = HttpRequest.BodyPublishers.noBody();
        }
        reqB.method(method, bodyPub);

        HttpResponse<InputStream> response;
        try {
            response = config.httpClient.send(reqB.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new KError(
                "K9O_UPSTREAM_UNAVAILABLE",
                "Network error reaching " + url + ": " + e.getMessage(),
                Map.of("url", url),
                "Check connectivity to the gateway; retry with backoff if transient.",
                null, null
            );
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String errBody;
            try (InputStream is = response.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                errBody = "";
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> wire = JSON.readValue(errBody, Map.class);
                if (wire.get("code") instanceof String code && wire.get("message") instanceof String message) {
                    throw new KError(code, message,
                        wire.get("context") instanceof Map<?, ?> c ? (Map<String, Object>) (Map) c : Map.of(),
                        wire.get("recommendation") instanceof String r ? r : null,
                        status,
                        wire.get("requestId") instanceof String rid ? rid : null);
                }
            } catch (IOException ignored) { /* fall through */ }
            throw new KError(
                "K9O_INTERNAL_ERROR",
                "Upstream returned " + status + " opening stream",
                Map.of("url", url, "status", status),
                null, status, null
            );
        }

        InputStream is = response.body();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        return reader.lines().onClose(() -> {
            try { reader.close(); } catch (IOException ignored) {}
            try { is.close(); } catch (IOException ignored) {}
        });
    }

    // ---- Regional host resolution ----
    // The region-free org host `<org>.api[-staging].k9o.io` redirects GET/HEAD only and refuses a request that
    // carries a body, Authorization or cookie. ONE unauthenticated HEAD (no redirect followed) learns the region
    // from the 308 Location; the validated regional origin is cached on the Config and used for every call.

    private static final java.util.regex.Pattern REGION_FREE_ORG_HOST =
        java.util.regex.Pattern.compile("^([a-z][a-z0-9-]{1,62})\\.(api|api-staging)\\.k9o\\.io$");
    private static final java.util.regex.Pattern REGION_CODE =
        java.util.regex.Pattern.compile("^[a-z]{2}\\d{2}$");

    /** Used for the probe when the configured client would follow redirects (a HttpClient's redirect policy is
     *  client-wide, so it cannot be switched off per request). */
    private static final HttpClient NO_REDIRECT_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /** The regional origin a {@code location} may name for {@code host}, or null if it is not exactly that. */
    static String regionalOriginFromLocation(String host, String location, String base) {
        java.util.regex.Matcher m = REGION_FREE_ORG_HOST.matcher(host);
        if (!m.matches()) return null;
        URI u;
        try {
            u = URI.create(base).resolve(location);
        } catch (RuntimeException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null || u.getPort() != -1) return null;
        String h = u.getHost();
        if (h == null) return null;
        h = h.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher r = java.util.regex.Pattern
            .compile("^" + java.util.regex.Pattern.quote(m.group(1)) + "\\." + java.util.regex.Pattern.quote(m.group(2))
                + "-([a-z0-9]+)\\.k9o\\.io$")
            .matcher(h);
        if (!r.matches() || !REGION_CODE.matcher(r.group(1)).matches()) return null;
        return "https://" + h;
    }

    /** The origin requests go to: the cached regional origin once learned, else the configured base URL. */
    static String resolveRegionalOrigin(Config config) {
        synchronized (config) {
            if (config.resolvedOrigin != null) return config.resolvedOrigin;
            String configured = config.baseUrl;
            String host;
            try {
                host = URI.create(configured).getHost();
            } catch (RuntimeException e) {
                host = null;
            }
            if (host == null || !REGION_FREE_ORG_HOST.matcher(host.toLowerCase(java.util.Locale.ROOT)).matches()) {
                config.resolvedOrigin = configured;
                return configured;
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            String trimmed = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
            try {
                // Probe: no Authorization, no cookie, none of the default headers.
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(trimmed + "/"))
                    .timeout(Duration.ofSeconds(10))
                    .header("accept", "*/*")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
                HttpClient c = config.httpClient.followRedirects() == HttpClient.Redirect.NEVER
                    ? config.httpClient : NO_REDIRECT_CLIENT;
                HttpResponse<Void> res = c.send(req, HttpResponse.BodyHandlers.discarding());
                String origin = configured;
                if (res.statusCode() == 308) {
                    String loc = res.headers().firstValue("location").orElse(null);
                    String o = loc == null ? null : regionalOriginFromLocation(host, loc, configured);
                    if (o != null) origin = o;
                }
                config.resolvedOrigin = origin; // definite answer, including "no redirect": cached
                return origin;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return configured; // not cached
            } catch (IOException | RuntimeException e) {
                return configured; // network failure: not cached
            }
        }
    }

    private static String buildUrl(String baseUrl, String path, Map<String, String> query) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url = trimmed + path;
        if (query != null && !query.isEmpty()) {
            StringBuilder sb = new StringBuilder(url);
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() == null) continue;
                sb.append(first ? '?' : '&');
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
                sb.append('=');
                sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
            url = sb.toString();
        }
        return url;
    }
}
