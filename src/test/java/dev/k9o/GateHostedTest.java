package dev.k9o;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GateHostedTest {

    static final class Stub extends HttpClient {
        final int status;
        final String body;
        final List<RegionTest.Call> calls = new ArrayList<>();
        final List<RegionTest.Call> real = calls;

        Stub(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h) throws IOException {
            calls.add(new RegionTest.Call(req.uri().toString(), req.method(), req.headers().map()));
            return (HttpResponse<T>) new RegionTest.Resp<>(req, status, Map.of(), body);
        }
    }

    static K9o.K9oClient mk(HttpClient s, String project) {
        K9o.Opts.Builder b = K9o.Opts.builder()
            .baseUrl("https://api.k9o.io").apiKey("secret-key")
            .defaultHeader("cookie", "sid=1")
            .httpClient(s)
            .retryConfig(new RetryConfig(0, "none", 0, 0));
        if (project != null) b.projectSlug(project);
        return K9o.createClient(b.build());
    }

    static final String OK = "{\"data\":{\"signInUrl\":\"https://auth.acme.com/\",\"rpId\":\"auth.acme.com\"},\"meta\":{}}";

    @Test
    void getsHostedUrlWithVerb() {
        Stub s = new Stub(200, OK);
        mk(s, "portal").gate().hosted();
        assertEquals("GET", s.real.get(0).method());
        assertEquals("https://api.k9o.io/@portal/gate/hosted", s.real.get(0).url());
    }

    @Test
    void sendsNoCredentialEvenWithTokenConfigured() {
        Stub s = new Stub(200, OK);
        mk(s, "portal").gate().hosted();
        assertFalse(s.real.get(0).hasHeader("authorization"));
        assertFalse(s.real.get(0).hasHeader("cookie"));
    }

    @Test
    void returnsSignInUrlAndRpId() {
        Stub s = new Stub(200, OK);
        Envelope<Gate.HostedGateInfo> env = mk(s, "portal").gate().hosted();
        assertEquals("https://auth.acme.com/", env.data().signInUrl());
        assertEquals("auth.acme.com", env.data().rpId());
    }

    @Test
    void uniform404SurfacesAsKError() {
        Stub s = new Stub(404, "{\"code\":\"K9O_PROJECT_NOT_FOUND\",\"message\":\"not found\"}");
        KError e = assertThrows(KError.class, () -> mk(s, "portal").gate().hosted());
        assertEquals("K9O_PROJECT_NOT_FOUND", e.getCode());
        assertEquals(404, e.getStatus());
    }

    @Test
    void missingProjectSlugRefusedWithNoRequest() {
        Stub s = new Stub(200, OK);
        KError e = assertThrows(KError.class, () -> mk(s, null).gate().hosted());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.getCode());
        assertTrue(s.calls.isEmpty());
    }
}
