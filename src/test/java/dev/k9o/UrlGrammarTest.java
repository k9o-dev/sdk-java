package dev.k9o;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlGrammarTest {

    @Test
    void validateBaseUrlAccepts() {
        assertEquals("https://api.k9o.io", UrlGrammar.validateBaseUrl("https://api.k9o.io"));
        assertEquals("https://acme.api.k9o.io", UrlGrammar.validateBaseUrl("https://acme.api.k9o.io"));
        assertEquals("http://localhost:8080", UrlGrammar.validateBaseUrl("http://localhost:8080"));
        assertEquals("https://api.k9o.io", UrlGrammar.validateBaseUrl("https://api.k9o.io/"));
    }

    @Test
    void validateBaseUrlRejectsPathSegment() {
        KError e = assertThrows(KError.class, () -> UrlGrammar.validateBaseUrl("https://api.k9o.io/v2"));
        assertEquals("K9O_INVALID_BASE_URL", e.getCode());
    }

    @Test
    void validateBaseUrlRejectsWebsocketScheme() {
        KError e = assertThrows(KError.class, () -> UrlGrammar.validateBaseUrl("wss://api.k9o.io"));
        assertEquals("K9O_INVALID_BASE_URL", e.getCode());
    }

    @Test
    void resolveBaseUrlDefaultsToApexWhenOmitted() {
        // Omitted baseUrl, no orgSlug -> platform apex.
        assertEquals("https://api.k9o.io", UrlGrammar.resolveBaseUrl(null, null));
        assertEquals("https://api.k9o.io", UrlGrammar.resolveBaseUrl("", null));
    }

    @Test
    void resolveBaseUrlDerivesSubdomainFromApexPlusOrgSlug() {
        // Omitted baseUrl + orgSlug -> derives <orgSlug>.api.k9o.io.
        assertEquals("https://acme.api.k9o.io", UrlGrammar.resolveBaseUrl(null, "acme"));
        // Explicit bare apex + orgSlug -> derives subdomain, scheme preserved.
        assertEquals("https://acme.api.k9o.io", UrlGrammar.resolveBaseUrl("https://api.k9o.io", "acme"));
        assertEquals("http://acme.api.k9o.io", UrlGrammar.resolveBaseUrl("http://api.k9o.io", "acme"));
        // Apex with no orgSlug -> stays apex.
        assertEquals("https://api.k9o.io", UrlGrammar.resolveBaseUrl("https://api.k9o.io", null));
    }

    @Test
    void resolveBaseUrlPassesThroughExplicitSubdomain() {
        // Explicit subdomain is used unchanged (even if a different orgSlug is set).
        assertEquals("https://acme.api.k9o.io", UrlGrammar.resolveBaseUrl("https://acme.api.k9o.io", "acme"));
        assertEquals("https://acme.api.k9o.io", UrlGrammar.resolveBaseUrl("https://acme.api.k9o.io", "other"));
    }

    @Test
    void resolveBaseUrlPassesThroughSelfHostedAndDevHosts() {
        // Self-hosted / Sovereign host -> unchanged, orgSlug ignored.
        assertEquals("https://platform.acme.internal",
            UrlGrammar.resolveBaseUrl("https://platform.acme.internal", "acme"));
        // Dev host -> unchanged.
        assertEquals("http://localhost:8080",
            UrlGrammar.resolveBaseUrl("http://localhost:8080", "acme"));
    }

    @Test
    void resolveBaseUrlDerivesSubdomainFromStagingApexPlusOrgSlug() {
        // Bare staging apex + orgSlug -> derives <orgSlug>.api-staging.k9o.io (cross-pool fix).
        assertEquals("https://acme.api-staging.k9o.io", UrlGrammar.resolveBaseUrl("https://api-staging.k9o.io", "acme"));
        // Explicit staging subdomain passes through unchanged.
        assertEquals("https://acme.api-staging.k9o.io", UrlGrammar.resolveBaseUrl("https://acme.api-staging.k9o.io", "acme"));
        // Staging apex with no orgSlug -> stays apex.
        assertEquals("https://api-staging.k9o.io", UrlGrammar.resolveBaseUrl("https://api-staging.k9o.io", null));
    }

    @Test
    void resolveBaseUrlValidatesOrgSlugOnStagingDerive() {
        KError e = assertThrows(KError.class,
            () -> UrlGrammar.resolveBaseUrl("https://api-staging.k9o.io", "Bad_Slug"));
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
    }

    @Test
    void resolveBaseUrlValidatesOrgSlugOnDerive() {
        KError e = assertThrows(KError.class,
            () -> UrlGrammar.resolveBaseUrl("https://api.k9o.io", "Bad_Slug"));
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
    }

    @Test
    void resolveBaseUrlKeepsBaseUrlValidation() {
        KError e = assertThrows(KError.class,
            () -> UrlGrammar.resolveBaseUrl("https://api.k9o.io/v2", "acme"));
        assertEquals("K9O_INVALID_BASE_URL", e.getCode());
    }

    @Test
    void validateSlugAccepts() {
        assertDoesNotThrow(() -> UrlGrammar.validateSlug("acme", "org"));
        assertDoesNotThrow(() -> UrlGrammar.validateSlug("member-portal", "project"));
        assertDoesNotThrow(() -> UrlGrammar.validateSlug("a1", "org"));
    }

    @Test
    void validateSlugRejects() {
        for (String bad : new String[]{"Acme", "1acme", "-acme", "acme-", "a", ""}) {
            KError e = assertThrows(KError.class, () -> UrlGrammar.validateSlug(bad, "test"));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode(), "expected reject for " + bad);
        }
    }

    @Test
    void projectPathStripsLeadingSlash() {
        assertEquals("/@member-portal/data/posts",
            UrlGrammar.projectPath("member-portal", "data/posts"));
        assertEquals("/@acme/project", UrlGrammar.projectPath("acme", "/project"));
    }

    @Test
    void orgPathPrefixesSlash() {
        assertEquals("/projects", UrlGrammar.orgPath("projects"));
        assertEquals("/projects", UrlGrammar.orgPath("/projects"));
    }
}
