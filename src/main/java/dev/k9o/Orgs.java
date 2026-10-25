package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Org lifecycle namespace (collection-style) — {@code client.orgs()}.
 * Distinct from {@code client.org()} (singular bound-org namespace).
 */
public final class Orgs {

    private final HttpTransport.Config config;

    Orgs(HttpTransport.Config config) {
        this.config = config;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Org record. */
    public record Org(
        @JsonProperty("id") String id,
        @JsonProperty("slug") String slug,
        @JsonProperty("name") String name,
        @JsonProperty("status") String status,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt
    ) {}

    /** List orgs accessible to the caller. */
    public Envelope<List<Org>> list() {
        return HttpTransport.request(config, "GET", UrlGrammar.orgPath("orgs"),
            null, null, new TypeReference<Envelope<List<Org>>>() {});
    }

    /** Get a single org by slug. */
    public Envelope<Org> get(String orgSlug) {
        return HttpTransport.request(config, "GET", UrlGrammar.orgPath("orgs/" + enc(orgSlug)),
            null, null, new TypeReference<Envelope<Org>>() {});
    }

    /** Restore a soft-deleted org. */
    public Envelope<Org> restore(String orgId) {
        return HttpTransport.request(config, "POST", UrlGrammar.orgPath("orgs/" + enc(orgId) + "/restore"),
            null, null, new TypeReference<Envelope<Org>>() {});
    }

    /** Get the org bound to the caller's current session context. */
    public Envelope<Org> current() {
        return HttpTransport.request(config, "GET", UrlGrammar.orgPath("org"),
            null, null, new TypeReference<Envelope<Org>>() {});
    }
}
