package dev.k9o;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Barbican: the free, embeddable L2 guardrail. Input normalization, deny-list matching over a signed ruleset,
 * a local verdict, and a streaming output redactor (buffer-and-scan-before-flush).
 *
 * <p>A thin wrapper over the native {@code k9o-keep-core} engine (uniffi Kotlin binding, loaded by JNA).
 * This class holds NO key and does NO cryptography: the engine verifies key manifests, rulesets and
 * manifests itself, against a root compiled into it. There is no verifier or keyring argument anywhere
 * on this surface. A refusal arrives as a {@link KError} whose context carries the engine's closed
 * reason token verbatim.
 *
 * <p>CLAIM BOUNDARY: Barbican is advisory, defense-in-depth edge filtration, never the enforced sandbox boundary.
 *
 * <p>The generated binding and native library are CI-injected build artifacts (see the {@code barbican}
 * profile in pom.xml). When the native library is absent, constructing a {@code Barbican} throws
 * {@link EngineMissingError}; it never dereferences a null.
 */
public final class Barbican implements AutoCloseable {

    /** {@code KError} code for a refused key manifest, manifest or ruleset. */
    public static final String CODE_RULESET_INVALID = "K9O_BARBICAN_RULESET_INVALID";
    /** {@code KError} code for an engine failure that is not a refusal (shared with the Python and Ruby wrappers). */
    public static final String CODE_ENGINE_ERROR = "K9O_BARBICAN_WASM_ERROR";

    /** The native engine (or its generated binding) is not loadable on this platform. */
    public static final class EngineMissingError extends KError {
        public EngineMissingError(String detail) {
            super(CODE_ENGINE_ERROR,
                "barbican native engine is missing: the k9o-keep-core library could not be loaded for this platform"
                    + " (" + detail + "). Install the platform build that bundles it.");
        }
    }

    public record Rule(String id, String pattern, String severity) {}

    /** A signed ruleset as published. {@code signature} is the raw 64-byte Ed25519 signature; the engine checks it. */
    public record SignedRuleset(long rulesetVersion, List<Rule> rules, String signingKeyKid, byte[] signature, long createdAt) {}

    public record Matched(String ruleId, long spanStart, long spanEnd, String severity, String matchedForm) {}

    public record Verdict(String decision, String reason, List<Matched> matched, boolean schemaValid,
                          String waypointClassHint, String failMode, long rulesetVersion, String coreVersion) {}

    public record ManifestInfo(long rulesetVersion, String signingKeyKid, String artifactHash, String artifactUrl,
                               Long validUntil) {}

    public record LeakReport(long emails, long secrets, long markers, long total) {}

    /** A streaming output redactor: never emits a byte the engine has not cleared. */
    public static final class OutputFilter implements AutoCloseable {
        private final NFilter handle;

        OutputFilter(NFilter handle) {
            this.handle = handle;
        }

        /** Feed a chunk; returns the cleared (redacted) prefix safe to emit now. */
        public String push(String chunk) {
            return handle.push(chunk);
        }

        /** Flush the residual buffer (also scanned) at end of stream. */
        public String finish() {
            return handle.finish();
        }

        /** Counts only, never the leaked values. */
        public LeakReport report() {
            NLeakReport r = handle.report();
            return new LeakReport(r.getEmails(), r.getSecrets(), r.getMarkers(), r.getTotal());
        }

        @Override
        public void close() {
            handle.close();
        }
    }

    private final BarbicanEngine core;

    /** Default config: fail closed, leet de-obfuscation on, core-default decode depth. */
    public Barbican() {
        this(false, true, 0);
    }

    /** {@code decodeDepth} 0 uses the core default (3): a bounded base64/leetspeak de-obfuscation depth. */
    public Barbican(boolean failOpen, boolean leet, int decodeDepth) {
        this.core = engine(() -> new BarbicanEngine(failOpen, leet, decodeDepth));
    }

    /**
     * {@code false} on every shipped build. {@code true} means the loaded engine trusts the PUBLISHED TEST root
     * and must never be used outside tests.
     */
    public static boolean usesTestTrustRoot() {
        return engine(BarbicanEngine::testRoot);
    }

    /**
     * Load a static ruleset bundled with your package (host-vouched, so no signature is checked; the engine
     * still enforces anti-rollback). Returns the accepted version.
     */
    public long loadRuleset(long rulesetVersion, List<Rule> rules) {
        return guard("ruleset", () -> core.loadRulesetUnsigned(rulesetVersion, toN(rules)));
    }

    /**
     * Load a Barbican key manifest (compact JWS). The engine checks it against the root compiled into it and,
     * only if it verifies, replaces the trusted ruleset-signing keys. Returns the manifest sequence.
     */
    public long loadKeyManifest(String jws) {
        return loadKeyManifest(jws, System.currentTimeMillis() / 1000L);
    }

    public long loadKeyManifest(String jws, long nowSecs) {
        return guard("key_manifest", () -> core.loadKeyManifest(jws, nowSecs));
    }

    /** Verify a ruleset manifest (compact JWS) under a trusted key; returns its authenticated fields. */
    public ManifestInfo verifyManifest(String jws) {
        NManifestInfo i = guard("ruleset", () -> core.verifyManifest(jws));
        return new ManifestInfo(i.getRulesetVersion(), i.getSigningKeyKid(), i.getArtifactHash(), i.getArtifactUrl(),
            i.getValidUntil());
    }

    /**
     * Load a signed ruleset. The engine verifies the signature ITSELF under a key from the loaded key manifest.
     * Any failure raises a {@link KError} ({@value #CODE_RULESET_INVALID}) with {@code context.reason} and
     * leaves the current ruleset running. Returns the accepted version.
     */
    public long loadRulesetSigned(SignedRuleset blob) {
        return guard("ruleset", () -> core.loadRulesetSigned(blob.rulesetVersion(), toN(blob.rules()),
            blob.signingKeyKid(), blob.signature(), blob.createdAt()));
    }

    /** Lowercase hex SHA-256 of the ruleset's canonical signing bytes. A digest only; it decides no trust. */
    public String rulesetDigest(SignedRuleset blob) {
        return guard("ruleset", () -> core.rulesetDigest(blob.rulesetVersion(), toN(blob.rules()),
            blob.signingKeyKid(), blob.signature(), blob.createdAt()));
    }

    /** The sequence of the key manifest in force (0 before one is loaded). */
    public long keyManifestSequence() {
        return core.keyManifestSequence();
    }

    /** The highest VERIFIED ruleset version loaded (0 = none): the anti-rollback floor for signed updates. */
    public long signedFloor() {
        return core.signedFloor();
    }

    /** Whether the ruleset in force came from a verified load. */
    public boolean activeRulesetIsSigned() {
        return core.activeRulesetIsSigned();
    }

    /** The signing kids the loaded key manifest currently trusts, sorted. */
    public List<String> trustedKids() {
        return core.trustedKids();
    }

    /** The trusted kids the key manifest flags deprecated, sorted. */
    public List<String> deprecatedKids() {
        return core.deprecatedKids();
    }

    public Verdict inspect(String text) {
        return inspect(text, null);
    }

    /** Normalize, deny, local verdict. {@code waypointClass} is a meter hint and NEVER gates the decision. */
    public Verdict inspect(String text, String waypointClass) {
        NVerdict v = core.inspect(text, waypointClass);
        List<Matched> m = new ArrayList<>();
        for (NMatched x : v.getMatched()) {
            m.add(new Matched(x.getRuleId(), x.getSpanStart(), x.getSpanEnd(), x.getSeverity(), x.getMatchedForm()));
        }
        return new Verdict(v.getDecision(), v.getReason(), List.copyOf(m), v.getSchemaValid(), v.getWaypointClassHint(),
            v.getFailMode(), v.getRulesetVersion(), v.getCoreVersion());
    }

    /** Local L3 stand-in classification: "clear", "suspicious" or "malicious". Not an enforcement decision. */
    public String classify(String text) {
        return core.classify(text);
    }

    /** Open an output stream-filter bound to the active ruleset's exfil markers. */
    public OutputFilter createOutputFilter() {
        return new OutputFilter(core.outputFilter());
    }

    public String coreVersion() {
        return core.coreVersion();
    }

    @Override
    public void close() {
        core.close();
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private static List<NRule> toN(List<Rule> rules) {
        List<NRule> out = new ArrayList<>(rules.size());
        for (Rule r : rules) {
            out.add(new NRule(r.id(), r.pattern(), r.severity()));
        }
        return out;
    }

    /** Run an engine call, mapping the engine's refusal onto KError with the closed reason token verbatim. */
    private static <T> T guard(String stage, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (Refusal e) {
            throw new KError(CODE_RULESET_INVALID, e.getMessage(), Map.of("reason", e.getReason(), "stage", stage),
                null, null, null);
        }
    }

    /** Touch the engine; a missing library or binding becomes a typed EngineMissingError, never a raw link error. */
    private static <T> T engine(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (LinkageError e) {
            throw new EngineMissingError(String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("UniFFI")) {
                throw new EngineMissingError(e.getMessage());
            }
            throw e;
        }
    }
}
