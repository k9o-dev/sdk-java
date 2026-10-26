package dev.k9o;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientTest {

    @Test
    void createClientConstructsWithMinimalOpts() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder()
            .baseUrl("https://api.k9o.io")
            .build());
        assertEquals("https://api.k9o.io", c.baseUrl());
        assertNotNull(c.projects());
        assertNotNull(c.gate());
    }

    @Test
    void createClientNormalisesTrailingSlash() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder()
            .baseUrl("https://api.k9o.io/")
            .build());
        assertEquals("https://api.k9o.io", c.baseUrl());
    }

    @Test
    void createClientRejectsPathBearingBaseUrl() {
        KError e = assertThrows(KError.class, () -> K9o.createClient(K9o.Opts.builder()
            .baseUrl("https://api.k9o.io/v2")
            .build()));
        assertEquals("K9O_INVALID_BASE_URL", e.getCode());
    }

    @Test
    void optsBuilderDefaultsBaseUrlToApex() {
        // baseUrl is OPTIONAL — defaults to the platform apex.
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder().build());
        assertEquals("https://api.k9o.io", c.baseUrl());
    }

    @Test
    void omittedBaseUrlWithOrgSlugDerivesSubdomain() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder()
            .orgSlug("acme")
            .build());
        assertEquals("https://acme.api.k9o.io", c.baseUrl());
    }

    @Test
    void dataPlaneSelectRequiresProjectSlug() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder()
            .baseUrl("https://api.k9o.io")
            .build());
        KError e = assertThrows(KError.class, () -> c.from("posts").select().execute(Object.class));
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.getCode());
    }

    @Test
    void dataPlaneUpdateRequiresIdFilter() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder()
            .baseUrl("https://api.k9o.io")
            .projectSlug("member-portal")
            .build());
        KError e = assertThrows(KError.class, () -> c.from("posts").update("patch").execute(Object.class));
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
    }

    @Test
    void projectsCreateValidatesSlug() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder()
            .baseUrl("https://api.k9o.io")
            .orgSlug("acme")
            .build());
        KError e = assertThrows(KError.class, () -> c.projects().create(
            new Projects.CreateInput("X", "Bad-Slug", null, null, null, null)));
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
    }

    @Test
    void gateRequiresProjectSlug() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder()
            .baseUrl("https://api.k9o.io")
            .build());
        KError e = assertThrows(KError.class, () -> c.gate().whoami());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.getCode());
    }

    @Test
    void collectionNameValidation() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder()
            .baseUrl("https://api.k9o.io")
            .projectSlug("member-portal")
            .build());
        KError e = assertThrows(KError.class, () -> c.from("Bad-Name").select().execute(Object.class));
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
    }
}
