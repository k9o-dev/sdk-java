package dev.k9o;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regional host resolution: the one unauthenticated HEAD to the region-free org host, the 308 Location
 * validation, the caching, and that a credential can only ever reach a validated regional host.
 */
class RegionTest {

    record Call(String url, String method, Map<String, List<String>> headers) {
        boolean hasHeader(String n) {
            return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(n));
        }
    }

    /** Stub transport. HEAD answers {headStatus, Location}; anything else 200 with an empty envelope / SSE body. */
    static final class Stub extends HttpClient {
        final List<Call> calls = new ArrayList<>();
        final String location;
        final int headStatus;
        final AtomicInteger headFailures;
        final Redirect policy;

        Stub(String location, int headStatus, int headFailures, Redirect policy) {
            this.location = location;
            this.headStatus = headStatus;
            this.headFailures = new AtomicInteger(headFailures);
            this.policy = policy;
        }

        static Stub redirecting(String location) { return new Stub(location, 308, 0, Redirect.NEVER); }

        long heads() { return calls.stream().filter(c -> c.method().equals("HEAD")).count(); }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h) throws IOException {
            calls.add(new Call(req.uri().toString(), req.method(), req.headers().map()));
            if (req.method().equals("HEAD")) {
                if (headFailures.getAndDecrement() > 0) throw new IOException("offline");
                Map<String, List<String>> hm = location == null ? Map.of() : Map.of("location", List.of(location));
                return (HttpResponse<T>) new Resp<Object>(req, headStatus, hm, null);
            }
            boolean sse = req.headers().firstValue("accept").orElse("").equals("text/event-stream");
            Object body = sse ? new ByteArrayInputStream("data: {\"n\":1}\n\ndata: [DONE]\n\n".getBytes())
                              : "{\"data\":{},\"meta\":{}}";
            return (HttpResponse<T>) new Resp<>(req, 200, Map.of(), body);
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return policy; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    record Resp<T>(HttpRequest req, int status, Map<String, List<String>> hm, T body) implements HttpResponse<T> {
        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return req; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(hm, (a, b) -> true); }
        @Override public T body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return req.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    static K9o.K9oClient mk(Stub s, String base) {
        return K9o.createClient(K9o.Opts.builder()
            .baseUrl(base).projectSlug("portal").apiKey("secret-key")
            .defaultHeader("x-extra", "1")
            .httpClient(s)
            .retryConfig(new RetryConfig(0, "none", 0, 0))
            .build());
    }

    static final String PROD = "https://acme.api.k9o.io";
    static final String LIST_URL = "https://acme.api-de01.k9o.io/@portal/gate/whoami";

    @Test
    void probesUnauthenticatedThenSendsCredentialToRegionalHost() {
        Stub s = Stub.redirecting("https://acme.api-de01.k9o.io/");
        mk(s, PROD).gate().whoami();
        Call probe = s.calls.get(0);
        assertEquals("HEAD", probe.method());
        assertEquals("https://acme.api.k9o.io/", probe.url());
        assertFalse(probe.hasHeader("authorization"));
        assertFalse(probe.hasHeader("x-extra"));
        assertFalse(probe.hasHeader("cookie"));
        assertFalse(probe.hasHeader("x-k9o-project-slug"));
        Call real = s.calls.get(1);
        assertTrue(real.url().startsWith("https://acme.api-de01.k9o.io/"), real.url());
        assertTrue(real.hasHeader("authorization"));
    }

    @Test
    void gateSignInPostGoesToRegionalHost() {
        Stub s = Stub.redirecting("https://acme.api-de01.k9o.io/");
        mk(s, PROD).gate().signIn(new Gate.SignInInput("u@x.com", "pw"));
        assertEquals("HEAD", s.calls.get(0).method());
        assertEquals("POST", s.calls.get(1).method());
        assertTrue(s.calls.get(1).url().startsWith("https://acme.api-de01.k9o.io/"));
    }

    @Test
    void streamingPathIsResolvedToo() {
        Stub s = Stub.redirecting("https://acme.api-staging-de01.k9o.io/");
        var c = K9o.createClient(K9o.Opts.builder().baseUrl("https://acme.api-staging.k9o.io")
            .projectSlug("portal").apiKey("secret-key").httpClient(s).build());
        // openSseLines is the transport under EventStream (Compute LLM invokeStream / Realtime subscribe).
        HttpTransport.Config cfg = new HttpTransport.Config("https://acme.api-staging.k9o.io", "secret-key", null,
            "portal", null, s);
        try (EventStream<Map<String, Object>> es = new EventStream<>(
                HttpTransport.openSseLines(cfg, "POST", "/@portal/compute/stream", Map.of("a", 1), null),
                new TypeReference<Map<String, Object>>() {})) {
            assertTrue(es.iterator().hasNext());
        }
        assertEquals("HEAD", s.calls.get(0).method());
        assertFalse(s.calls.get(0).hasHeader("authorization"));
        assertEquals("https://acme.api-staging-de01.k9o.io/@portal/compute/stream", s.calls.get(1).url());
        assertTrue(s.calls.get(1).hasHeader("authorization"));
        assertNotNull(c);
    }

    @Test
    void cachesRegionalOrigin_oneProbeForManyCalls() {
        Stub s = Stub.redirecting("https://acme.api-staging-de01.k9o.io/");
        var c = mk(s, "https://acme.api-staging.k9o.io");
        c.gate().whoami();
        c.gate().whoami();
        assertEquals(1, s.heads());
        assertEquals("https://acme.api-staging-de01.k9o.io/@portal/gate/whoami", s.calls.get(2).url());
    }

    @Test
    void noRedirectKeepsConfiguredHostAndIsCached() {
        Stub s = new Stub(null, 200, 0, HttpClient.Redirect.NEVER);
        var c = mk(s, PROD);
        c.gate().whoami();
        c.gate().whoami();
        assertEquals("https://acme.api.k9o.io/@portal/gate/whoami", s.calls.get(1).url());
        assertEquals(1, s.heads());
    }

    @Test
    void neverProbesRegionalCustomOrSelfHostedBase() {
        for (String base : List.of("https://acme.api-de01.k9o.io", "https://api.example.com",
                "https://k9o.internal.corp", "https://api.k9o.io", "http://127.0.0.1:8080",
                "https://x.acme.api.k9o.io")) {
            Stub s = Stub.redirecting("https://evil.example/");
            mk(s, base).gate().whoami();
            assertEquals(0, s.heads(), base);
            assertTrue(s.calls.get(0).url().startsWith(base), base);
        }
    }

    @Test
    void worksOnAnInternalApexAndRefusesAnotherApex() {
        Stub internal = Stub.redirecting("https://acme.api-de01.corp.internal/");
        mk(internal, "https://acme.api.corp.internal").gate().whoami();
        assertEquals("https://acme.api.corp.internal/", internal.calls.get(0).url());
        assertEquals("https://acme.api-de01.corp.internal/@portal/gate/whoami", internal.calls.get(1).url());
        // Same org, different apex: refused; the credential stays on the configured host.
        Stub wrong = Stub.redirecting("https://acme.api-de01.k9o.io/");
        mk(wrong, "https://acme.api.corp.internal").gate().whoami();
        assertEquals("https://acme.api.corp.internal/@portal/gate/whoami", wrong.calls.get(1).url());
        // localhost is never probed.
        Stub local = Stub.redirecting("https://evil.example/");
        mk(local, "http://localhost:6000").gate().whoami();
        assertEquals(0, local.heads());
    }

    @Test
    void orgSlugComposesOntoAnyPlatformHost() {
        assertEquals("https://acme.api.corp.internal", UrlGrammar.resolveBaseUrl("https://api.corp.internal", "acme"));
        assertEquals("https://acme.api-staging.example.org:8443", UrlGrammar.resolveBaseUrl("https://api-staging.example.org:8443", "acme"));
        assertEquals("http://localhost:6000", UrlGrammar.resolveBaseUrl("http://localhost:6000", "acme"));
        assertEquals("https://acme.api-de01.k9o.io", UrlGrammar.resolveBaseUrl("https://acme.api-de01.k9o.io", "other"));
        UrlGrammar.ApiHost h = UrlGrammar.parseApiHost("acme.api-de01.corp.internal");
        assertEquals("acme", h.orgSlug());
        assertEquals("de01", h.region());
        assertEquals("corp.internal", h.apex());
        assertNull(UrlGrammar.parseApiHost("localhost"));
    }

    @Test
    void refusesHostileLocation_credentialStaysOnConfiguredHost() {
        for (String loc : List.of(
            "https://evil.example/",
            "https://acme.api-de01.evil.example/",
            "https://acme.api-de01.k9o.io.evil.example/",
            "https://other.api-de01.k9o.io/",
            "https://acme.api-staging-de01.k9o.io/",
            "http://acme.api-de01.k9o.io/",
            "https://user:pw@acme.api-de01.k9o.io/",
            "https://acme.api-de01.k9o.io:8443/",
            "https://acme.api-germany.k9o.io/",
            "https://acme.api-de1.k9o.io/",
            "//evil.example/")) {
            Stub s = Stub.redirecting(loc);
            mk(s, PROD).gate().whoami();
            assertEquals("https://acme.api.k9o.io/@portal/gate/whoami", s.calls.get(1).url(), loc);
        }
    }

    @Test
    void networkFailureOfProbeIsNotCached() {
        Stub s = new Stub("https://acme.api-de01.k9o.io/", 308, 1, HttpClient.Redirect.NEVER);
        var c = mk(s, PROD);
        c.gate().whoami();
        assertEquals("https://acme.api.k9o.io/@portal/gate/whoami", s.calls.get(1).url());
        c.gate().whoami();
        assertEquals(2, s.heads());
        assertEquals(LIST_URL, s.calls.get(3).url());
    }

    @Test
    void regionalOriginFromLocationAcceptsOnlySameOrgSameApexFamily() {
        String base = PROD;
        assertEquals("https://acme.api-de01.k9o.io",
            HttpTransport.regionalOriginFromLocation("acme.api.k9o.io", "https://acme.api-de01.k9o.io/x", base));
        assertNull(HttpTransport.regionalOriginFromLocation("acme.api.k9o.io", "/relative", base));
        assertEquals("https://acme.api-staging-de01.k9o.io",
            HttpTransport.regionalOriginFromLocation("acme.api-staging.k9o.io", "https://acme.api-staging-de01.k9o.io/", base));
        assertNull(HttpTransport.regionalOriginFromLocation("api.example.com", "https://acme.api-de01.k9o.io/", base));
    }
}
