package dev.k9o;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kibo namespace — {@code client.kibo()} (Zero-Knowledge BYOK/HYOK vault surface).
 *
 * <p>Mirrors the TypeScript reference's {@code client.kibo.vault} nesting
 * ({@code packages/kibo/src/vault.ts}): {@code kibo().vault().secrets()} is the
 * typed secrets family (`services/gateway/src/routes/vault-secrets.ts`) — the
 * full 9-operation set: list/get/create/revealChallenge/reveal/update/rotate/move/
 * delete. Response shapes and required query params below are LIVE-VERIFIED against
 * a real staging deployment (not just the route's OpenAPI schema, which is wrong in
 * several places — see the per-method 🔴 notes).
 *
 * <p>NOTE: {@code vault.zkDeks} (F.5 opaque customer-wrapped DEK
 * store/fetch) is NOT implemented here — out of scope for this pass. This class
 * only wires up the {@code kibo().vault().secrets()} nesting shape that a future
 * zk-deks wrapper would slot into alongside it as {@code kibo().vault().zkDeks()}.
 * Distinct from the separate {@code k9o-kibo} artifact ({@code dev.k9o.kibo.Kibo}),
 * which is the client-side ZK encrypt/decrypt FFI facade (no REST calls at all) —
 * this class lives in the main SDK because vault-secrets is an ordinary authenticated
 * REST resource, reusing {@link HttpTransport}/{@link KError}/{@link Envelope} like
 * every other namespace here.
 */
public final class Kibo {

    private final Vault vault;

    Kibo(HttpTransport.Config config) {
        this.vault = new Vault(config);
    }

    /** The Kibo vault surface — {@code client.kibo().vault()}. */
    public Vault vault() {
        return vault;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String requireProjectSlug(HttpTransport.Config config) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT",
                "kibo.vault operations require projectSlug bound on the client (member-plane resource)");
        }
        return config.projectSlug;
    }

    /** UTF-8-safe base64 encode — the vault-secrets wire {@code value} field is base64 in BOTH
     *  directions (live-verified; the OpenAPI "plaintext to seal" description reads like it should
     *  be raw, but a raw non-base64 value is rejected as unparseable). */
    private static String encodeBase64Utf8(String plaintext) {
        return Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /** Inverse of {@link #encodeBase64Utf8} — decodes a revealed wire {@code value} back to plaintext. */
    private static String decodeBase64Utf8(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    /** Both {@code vault.zkDeks} (not yet implemented) and {@code vault.secrets} are member-plane,
     *  project-scoped resources (/). */
    public static final class Vault {
        private final Secrets secrets;

        Vault(HttpTransport.Config config) {
            this.secrets = new Secrets(config);
        }

        /** The typed secrets surface — {@code client.kibo().vault().secrets()}. */
        public Secrets secrets() {
            return secrets;
        }
    }

    // ------------------------------------------------------------------
    // Shapes — vault secrets
    // ------------------------------------------------------------------

    /** secret visibility. Java has no native discriminated union, so — mirroring how this
     *  SDK already models kind-tagged unions elsewhere (see {@code Migrations.Operation}) — this is
     *  a flat record with nullable optional fields; construct via {@link #privateVisibility()} /
     *  {@link #shared}. Write ops (create/update) stay owner-only regardless of this tag. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SecretVisibility(
        @JsonProperty("kind") String kind,
        @JsonProperty("principals") List<String> principals,
        @JsonProperty("roles") List<String> roles
    ) {
        public static SecretVisibility privateVisibility() {
            return new SecretVisibility("private", null, null);
        }

        public static SecretVisibility shared(List<String> principals, List<String> roles) {
            return new SecretVisibility("shared", principals, roles);
        }
    }

    /** releasing-node observed attestation tier (K4) — present on every reveal. */
    public record AttestationTier(
        @JsonProperty("urn") String urn,
        @JsonProperty("operator") String operator,
        @JsonProperty("verified") boolean verified
    ) {}

    /** A secret WITHOUT its sealed ciphertext — the create/list/get metadata shape. */
    public record SecretSummary(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("ownerId") String ownerId,
        @JsonProperty("visibility") SecretVisibility visibility,
        @JsonProperty("version") long version,
        @JsonProperty("kid") String kid,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("createdBy") String createdBy,
        @JsonProperty("updatedAt") String updatedAt,
        @JsonProperty("updatedBy") String updatedBy,
        @JsonProperty("rotatedAt") String rotatedAt,
        @JsonProperty("retiredAt") String retiredAt,
        @JsonProperty("metadata") Map<String, String> metadata
    ) {}

    /** {@code secrets().list()} result. */
    public record ListSecretsResult(
        @JsonProperty("secrets") List<SecretSummary> secrets,
        @JsonProperty("nextCursor") String nextCursor
    ) {}

    /** {@code revealChallenge()} result — a single-use challenge to present on the
     *  immediately-following {@code reveal()} call. */
    public record RevealChallenge(
        @JsonProperty("challenge") String challenge,
        @JsonProperty("expiresAt") String expiresAt
    ) {}

    /** {@code reveal()} result. {@code value} is the decoded plaintext (this class base64-decodes
     *  the wire value, the inverse of {@code create}/{@code update}'s encode) — never raw base64. */
    public record RevealSecretResult(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("version") long version,
        @JsonProperty("value") String value,
        @JsonProperty("revealedAt") String revealedAt,
        @JsonProperty("attestationTier") AttestationTier attestationTier
    ) {}

    /**
     * {@code update()} result — 🔴 live-verified NARROWER than the documented {@link SecretSummary}:
     * the gateway returns only {@code {id, name, version}} on a real PUT, not the full summary the
     * OpenAPI schema claims. Typed to what's actually observed; call {@code get()} afterward if the
     * full summary is needed.
     */
    public record UpdateSecretResult(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("version") long version
    ) {}

    /**
     * {@code rotate()} result — 🔴 live-verified shape, not documented anywhere in the OpenAPI schema
     * (the route only declares a union of {@code SecretSummary} / a reveal-challenge response, which
     * this matches neither). {@code ledgerSequenceNumber} is undocumented; kept as-observed.
     */
    public record RotateSecretResult(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("version") long version,
        @JsonProperty("rotatedAt") String rotatedAt,
        @JsonProperty("ledgerSequenceNumber") long ledgerSequenceNumber
    ) {}

    /**
     * {@code move()} result — 🔴 live-verified shape, also not the documented {@link SecretSummary}:
     * no {@code version}, and an undocumented {@code event} field ({@code "SECRET_PATH_CHANGED"}
     * observed; typed as {@code String} since only one sample was seen and a closed enum can't be
     * asserted from one observation).
     */
    public record MoveSecretResult(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("event") String event
    ) {}

    /** {@code create()} input — the caller-facing plaintext {@code value} (this class base64-encodes
     *  it for the wire internally). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateSecretInput(
        String value,
        SecretVisibility visibility,
        Map<String, String> metadata
    ) {
        public CreateSecretInput(String value) {
            this(value, null, null);
        }
    }

    /** {@code update()} input — new plaintext {@code value} (bumps version); base64-encoded for the
     *  wire internally. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateSecretInput(
        String value,
        Map<String, String> metadata
    ) {
        public UpdateSecretInput(String value) {
            this(value, null);
        }
    }

    /** Wire body for create/update — {@code value} already base64-encoded by the caller-facing method. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record WriteBody(
        @JsonProperty("value") String value,
        @JsonProperty("visibility") SecretVisibility visibility,
        @JsonProperty("metadata") Map<String, String> metadata
    ) {}

    /** Wire body for move — {@code {newPath}}. */
    private record MoveBody(@JsonProperty("newPath") String newPath) {}

    /**
     * The typed secrets surface (`services/gateway/src/routes/vault-secrets.ts`) — the full
     * family: list/get/create/revealChallenge/reveal/update/rotate/move/delete. Every operation on a
     * NAMED secret (everything except {@link #list}) requires {@code region} — live-verified against
     * staging, not documented in the route's OpenAPI schema. {@code region} is caller-supplied (e.g.
     * {@code "us01"}); the SDK does not validate or resolve it — a missing/blank region is simply
     * omitted from the query string and the gateway fail-closes with
     * {@code K9O_INVALID_FIELD_VALUE "this request requires an authoritative region (?region=)"},
     * mirroring the TypeScript reference (which relies on its type system, not a runtime check, since
     * {@code region} is a required field there too).
     *
     * <p>Response shapes for {@code update}/{@code rotate}/{@code move} are typed to what the gateway
     * ACTUALLY returns (live-verified), not the OpenAPI's documented (and inaccurate for these three)
     * {@link SecretSummary} — see the 🔴 notes on {@link UpdateSecretResult}/{@link RotateSecretResult}/
     * {@link MoveSecretResult} above.
     */
    public static final class Secrets {
        private final HttpTransport.Config config;

        Secrets(HttpTransport.Config config) {
            this.config = config;
        }

        /** Optional {@link #list} filters — none of these need {@code region} (live-verified: unlike
         *  every other secrets operation, list is NOT region-gated). */
        public record ListOpts(String prefix, String cursor, Integer limit) {
            public static ListOpts none() {
                return new ListOpts(null, null, null);
            }

            Map<String, String> toQuery() {
                Map<String, String> q = new HashMap<>();
                if (prefix != null) q.put("prefix", prefix);
                if (cursor != null) q.put("cursor", cursor);
                if (limit != null) q.put("limit", String.valueOf(limit));
                return q;
            }
        }

        private static Map<String, String> regionQuery(String region) {
            Map<String, String> q = new HashMap<>();
            if (region != null) q.put("region", region);
            return q;
        }

        /** List secrets (metadata only — never lists ciphertext). {@code GET /@<projectSlug>/vault/secrets}.
         *  The one secrets operation that does NOT need {@code region} (live-verified). */
        public Envelope<ListSecretsResult> list(ListOpts opts) {
            String slug = requireProjectSlug(config);
            return HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "vault/secrets"),
                null, opts == null ? null : opts.toQuery(),
                new TypeReference<Envelope<ListSecretsResult>>() {});
        }

        /**
         * Read a secret's metadata (never its value — use {@link #revealChallenge}+{@link #reveal} for
         * that). {@code GET /@<projectSlug>/vault/secrets/<name>?region=<region>}.
         *
         * <p>🔴 KNOWN LIVE BUG (staging, 2026-09-05): this call currently fails with
         * {@code K9O_REVEAL_CHALLENGE_REQUIRED} on every attempt — the gateway/kibo does not honor the
         * documented "no {@code X-K9O-Reveal-Challenge} header ⇒ return metadata" behavior; it demands
         * a challenge unconditionally. Implemented per the documented contract (this is a backend
         * defect, not a client workaround); {@code list(new ListOpts(exactName, null, null))} is the
         * current working alternative for a single secret's metadata until the gateway/kibo side is
         * fixed. Mirrors the TypeScript reference's identical implementation + comment.
         */
        public Envelope<SecretSummary> get(String name, String region) {
            String slug = requireProjectSlug(config);
            return HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "vault/secrets/" + enc(name)),
                null, regionQuery(region), new TypeReference<Envelope<SecretSummary>>() {});
        }

        /**
         * Create a secret under the caller's own auth.
         * {@code POST /@<projectSlug>/vault/secrets/<name>?region=<region>} → the {@code sec_<base62>} summary.
         *
         * <p>🔴 {@code region} is REQUIRED on the query string, live-verified against staging — the
         * vault-forward proxy fail-closes with {@code K9O_INVALID_FIELD_VALUE} ("this request requires
         * an authoritative region") without it. Distinct from any region carried inside the value's own
         * envelope (e.g. a BYOS aws_sigv4 STS-signing region): this one scopes the vault WRITE itself.
         *
         * <p>🔴 Live-verified against staging: the wire {@code value} MUST be base64 — a raw plaintext
         * string (matching the route's own "the plaintext to seal" OpenAPI description literally) is
         * rejected with {@code K9O_INVALID_FIELD_VALUE "Request body could not be parsed."}
         * {@code input.value()} stays the caller-facing plaintext; base64 is purely a wire-transport
         * detail handled here.
         */
        public Envelope<SecretSummary> create(String name, String region, CreateSecretInput input) {
            String slug = requireProjectSlug(config);
            WriteBody body = new WriteBody(encodeBase64Utf8(input.value()), input.visibility(), input.metadata());
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "vault/secrets/" + enc(name)),
                body, regionQuery(region), new TypeReference<Envelope<SecretSummary>>() {});
        }

        /** Mint a single-use reveal challenge — present it as {@code X-K9O-Reveal-Challenge} on the
         *  immediately-following {@link #reveal} call. {@code POST .../<name>?op=reveal-challenge&region=<region>}. */
        public Envelope<RevealChallenge> revealChallenge(String name, String region) {
            String slug = requireProjectSlug(config);
            Map<String, String> q = regionQuery(region);
            q.put("op", "reveal-challenge");
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "vault/secrets/" + enc(name)),
                null, q, new TypeReference<Envelope<RevealChallenge>>() {});
        }

        /** Reveal a secret's plaintext value using a single-use challenge from {@link #revealChallenge}.
         *  {@code GET .../<name>?region=<region>} with {@code X-K9O-Reveal-Challenge}. Decodes the
         *  wire's base64 {@code value} back to plaintext (live-verified: the reveal response's
         *  {@code value} is base64, symmetric with {@code create}/{@code update}'s encode). */
        public Envelope<RevealSecretResult> reveal(String name, String region, String challenge) {
            String slug = requireProjectSlug(config);
            Envelope<RevealSecretResult> envelope = HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "vault/secrets/" + enc(name)),
                null, regionQuery(region), Map.of("X-K9O-Reveal-Challenge", challenge),
                new TypeReference<Envelope<RevealSecretResult>>() {});
            RevealSecretResult raw = envelope.data();
            RevealSecretResult decoded = new RevealSecretResult(
                raw.id(), raw.name(), raw.version(), decodeBase64Utf8(raw.value()),
                raw.revealedAt(), raw.attestationTier());
            return new Envelope<>(decoded, envelope.meta());
        }

        /** Update a secret's value (bumps version) — owner-only. {@code PUT .../<name>?region=<region>}.
         *  🔴 Returns only {@code {id, name, version}} live (see {@link UpdateSecretResult}), not the
         *  full summary the OpenAPI schema documents — call {@link #get} after if you need the rest. */
        public Envelope<UpdateSecretResult> update(String name, String region, UpdateSecretInput input) {
            String slug = requireProjectSlug(config);
            WriteBody body = new WriteBody(encodeBase64Utf8(input.value()), null, input.metadata());
            return HttpTransport.request(config, "PUT",
                UrlGrammar.projectPath(slug, "vault/secrets/" + enc(name)),
                body, regionQuery(region), new TypeReference<Envelope<UpdateSecretResult>>() {});
        }

        /** Rotate a secret's wrapping key (re-wraps under a fresh {@code kid}; content unchanged) —
         *  owner-only. {@code POST .../<name>?op=rotate&region=<region>}. */
        public Envelope<RotateSecretResult> rotate(String name, String region) {
            String slug = requireProjectSlug(config);
            Map<String, String> q = regionQuery(region);
            q.put("op", "rotate");
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "vault/secrets/" + enc(name)),
                null, q, new TypeReference<Envelope<RotateSecretResult>>() {});
        }

        /** Move (rename) a secret's hierarchical path — owner-only.
         *  {@code POST .../<name>?op=move&region=<region>}. */
        public Envelope<MoveSecretResult> move(String name, String region, String newPath) {
            String slug = requireProjectSlug(config);
            Map<String, String> q = regionQuery(region);
            q.put("op", "move");
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "vault/secrets/" + enc(name)),
                new MoveBody(newPath), q, new TypeReference<Envelope<MoveSecretResult>>() {});
        }

        /** Retire a secret — owner-only. {@code DELETE .../<name>?region=<region>} →
         *  {@code 204 No Content} (empty body; no data). */
        public Envelope<Void> delete(String name, String region) {
            String slug = requireProjectSlug(config);
            return HttpTransport.request(config, "DELETE",
                UrlGrammar.projectPath(slug, "vault/secrets/" + enc(name)),
                null, regionQuery(region), new TypeReference<Envelope<Void>>() {});
        }
    }
}
