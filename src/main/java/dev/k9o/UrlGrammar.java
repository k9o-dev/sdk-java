package dev.k9o;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * URL grammar helpers.
 *
 * Public static helpers; throw {@link KError} on violation.
 */
public final class UrlGrammar {

    private static final Pattern SLUG_RE = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");

    /** The convenience default when the caller passes no baseUrl. A default value only: never used to parse a host. */
    public static final String DEFAULT_BASE_URL = "https://api.k9o.io";

    private static final Pattern API_LABEL = Pattern.compile("^(api|api-staging)(?:-([a-z]{2}\\d{2}))?$");

    /**
     * The structure of a gateway API host, read from the host itself. {@code orgSlug} is the first label of an org
     * host (null for a platform host); {@code family} is {@code api} or {@code api-staging}; {@code region} is the
     * 4-character region code of a regional host ({@code api-de01}), else null; {@code apex} is everything after the
     * gateway label (never matched against a list).
     */
    public record ApiHost(String orgSlug, String family, String region, String apex) {}

    /**
     * Read the org slug and region out of a gateway host without knowing its apex. The gateway label is {@code api}
     * or {@code api-staging}, optionally followed by {@code -<rgn>}; the label before it is the org slug and whatever
     * follows it is the apex. Returns null for any other shape (localhost, an IP, a single-label host, a host with no
     * gateway label), which callers use exactly as given.
     */
    public static ApiHost parseApiHost(String host) {
        if (host == null) return null;
        String[] labels = host.toLowerCase(java.util.Locale.ROOT).split("\\.", -1);
        for (String l : labels) if (l.isEmpty()) return null;
        if (labels.length >= 3 && SLUG_RE.matcher(labels[0]).matches() && !labels[0].endsWith("-")
            && API_LABEL.matcher(labels[1]).matches()) {
            return mkApiHost(labels[1], labels[0], java.util.Arrays.copyOfRange(labels, 2, labels.length));
        }
        if (labels.length >= 2 && API_LABEL.matcher(labels[0]).matches()) {
            return mkApiHost(labels[0], null, java.util.Arrays.copyOfRange(labels, 1, labels.length));
        }
        return null;
    }

    private static ApiHost mkApiHost(String gatewayLabel, String orgSlug, String[] rest) {
        java.util.regex.Matcher m = API_LABEL.matcher(gatewayLabel);
        if (!m.matches() || rest.length == 0) return null;
        return new ApiHost(orgSlug, m.group(1), m.group(2), String.join(".", rest));
    }

    private UrlGrammar() {}

    /**
     * Validate + normalise a baseUrl
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
                "Pass scheme + host, for example \"https://api.example.com\".",
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
     * Resolve the effective baseUrl. {@code baseUrl} is OPTIONAL: omitted, it defaults to {@link #DEFAULT_BASE_URL}
     * (the public cloud, a convenience only). When the (validated) host is a platform host ({@code api.<apex>} or
     * {@code api-staging.<apex>}, any apex) and {@code orgSlug} is supplied, the canonical org host
     * {@code <orgSlug>.<host>} is composed, preserving the scheme. Any other host (an org host already, an internal or
     * air-gapped host, localhost) passes through unchanged; {@code orgSlug} is then only sent as a header.
     *
     * @param baseUrl optional gateway host root; null/empty defaults to {@link #DEFAULT_BASE_URL}
     * @param orgSlug optional org slug; when set on a platform host, derives the org host
     * @return the resolved, validated, normalised baseUrl
     * @throws KError K9O_INVALID_BASE_URL on baseUrl violation; K9O_INVALID_FIELD_VALUE on bad orgSlug
     */
    public static String resolveBaseUrl(String baseUrl, String orgSlug) {
        String effective = (baseUrl == null || baseUrl.isEmpty()) ? DEFAULT_BASE_URL : baseUrl;
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
        ApiHost host = parseApiHost(uri.getHost());
        if (host != null && host.orgSlug() == null && host.region() == null && orgSlug != null && !orgSlug.isEmpty()) {
            validateSlug(orgSlug, "org");
            String authority = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return uri.getScheme() + "://" + orgSlug + "." + authority;
        }
        return validated;
    }

    /**
     * Validate an org/project slug
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
