package dev.k9o;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * URL grammar helpers.
 *
 * Public static helpers; throw {@link KError} on violation.
 */
public final class UrlGrammar {

    private static final Pattern SLUG_RE = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");

    /**
     * Bare platform apex hosts org-subdomain derivation applies to —
     * production and staging share the {@code <orgSlug>.<apex>} wire-routing
     * scheme (confirmed live). A third apex needs the same confirmation
     * before joining this set.
     */
    private static final Set<String> K9O_ORG_DERIVABLE_APEXES = Set.of("api.k9o.io", "api-staging.k9o.io");

    private UrlGrammar() {}

    /**
     * Validate + normalise a baseUrl.
     * @return normalised form (trailing slash stripped)
     * @throws KError with code K9O_INVALID_BASE_URL on violation
     */
    public static String validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new KError(
                "K9O_INVALID_BASE_URL",
                String.format("baseUrl \"%s\" is not a valid URL: %s", baseUrl, e.getMessage()),
                Map.of("baseUrl", baseUrl),
                "Pass scheme + host like \"https://api.k9o.io\".",
                null, null
            );
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new KError(
                "K9O_INVALID_BASE_URL",
                String.format("baseUrl \"%s\" uses unsupported scheme \"%s\"; expected http or https", baseUrl, scheme),
                Map.of("baseUrl", baseUrl, "scheme", scheme == null ? "null" : scheme),
                null, null, null
            );
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new KError(
                "K9O_INVALID_BASE_URL",
                String.format("baseUrl \"%s\" missing host", baseUrl),
                Map.of("baseUrl", baseUrl), null, null, null
            );
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new KError(
                "K9O_INVALID_BASE_URL",
                String.format("baseUrl \"%s\" must not contain a path segment; got \"%s\"", baseUrl, path),
                Map.of("baseUrl", baseUrl, "path", path), null, null, null
            );
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new KError(
                "K9O_INVALID_BASE_URL",
                String.format("baseUrl \"%s\" must not contain query or fragment", baseUrl),
                Map.of("baseUrl", baseUrl), null, null, null
            );
        }
        String hostPort = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        return scheme + "://" + hostPort;
    }

    /**
     * Resolve the effective baseUrl. {@code baseUrl}
     * is OPTIONAL — it defaults to the platform apex {@code https://api.k9o.io}. When
     * the (validated) host is a bare apex in {@link #K9O_ORG_DERIVABLE_APEXES} and
     * {@code orgSlug} is supplied, the SDK composes the canonical org-subdomain wire
     * root {@code https://<orgSlug>.<apex>}, preserving the scheme. An
     * explicit {@code <orgSlug>} subdomain, a self-hosted / Sovereign host, or a dev
     * host (anything not in that set) passes through unchanged — {@code orgSlug} is
     * intentionally NOT composed onto a non-apex host.
     *
     * <p>Fixed 2026-08-30 (SDK-2): this used to silently ignore {@code orgSlug} for
     * {@code api-staging.k9o.io} too, not just genuine self-hosted/custom hosts,
     * since only the literal production apex string was checked.
     *
     * @param baseUrl optional gateway host root; null/empty defaults to the apex
     * @param orgSlug optional org slug; when set on a derivable apex, derives the subdomain
     * @return the resolved, validated, normalised baseUrl
     * @throws KError K9O_INVALID_BASE_URL on baseUrl violation; K9O_INVALID_FIELD_VALUE on bad orgSlug
     */
    public static String resolveBaseUrl(String baseUrl, String orgSlug) {
        String effective = (baseUrl == null || baseUrl.isEmpty()) ? "https://api.k9o.io" : baseUrl;
        String validated = validateBaseUrl(effective);
        URI uri;
        try {
            uri = new URI(validated);
        } catch (URISyntaxException e) {
            // validateBaseUrl already parsed this successfully; unreachable in practice.
            throw new KError(
                "K9O_INVALID_BASE_URL",
                String.format("baseUrl \"%s\" is not a valid URL: %s", validated, e.getMessage()),
                Map.of("baseUrl", validated), null, null, null
            );
        }
        if (K9O_ORG_DERIVABLE_APEXES.contains(uri.getHost()) && orgSlug != null && !orgSlug.isEmpty()) {
            validateSlug(orgSlug, "org");
            return uri.getScheme() + "://" + orgSlug + "." + uri.getHost();
        }
        return validated;
    }

    /**
     * Validate an org/project slug.
     * @throws KError K9O_INVALID_FIELD_VALUE on violation
     */
    public static void validateSlug(String slug, String kind) {
        if (slug == null || !SLUG_RE.matcher(slug).matches() || slug.endsWith("-")) {
            throw new KError(
                "K9O_INVALID_FIELD_VALUE",
                String.format("%sSlug \"%s\" does not match required format ^[a-z][a-z0-9-]{1,62}$ (must not end with hyphen)", kind, slug),
                Map.of("slug", slug == null ? "" : slug, "kind", kind),
                null, null, null
            );
        }
    }

    /** Member-plane path: {@code /@<slug>/<sub-resource>}. */
    public static String projectPath(String projectSlug, String subResource) {
        String cleaned = subResource.startsWith("/") ? subResource.substring(1) : subResource;
        return "/@" + projectSlug + "/" + cleaned;
    }

    /** Org-plane path: {@code /<resource>}. */
    public static String orgPath(String resource) {
        return "/" + (resource.startsWith("/") ? resource.substring(1) : resource);
    }

    /** Platform-plane path: {@code /<resource>}. */
    public static String platformPath(String resource) {
        return "/" + (resource.startsWith("/") ? resource.substring(1) : resource);
    }
}
