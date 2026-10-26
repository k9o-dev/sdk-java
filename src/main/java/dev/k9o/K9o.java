package dev.k9o;

import dev.k9o.data.FromBuilder;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;

/**
 * k9o — Kilimanjaro platform SDK for Java.
 *
 * Idiomatic Java: camelCase methods + throw
 * {@link KError} extends RuntimeException + builder-pattern client opts.
 *
 * <pre>{@code
 * K9oClient k9o = K9o.createClient(K9o.Opts.builder()
 *     .baseUrl("https://acme.api.k9o.io")
 *     .apiKey(System.getenv("K9O_API_KEY"))
 *     .orgSlug("acme")
 *     .projectSlug("member-portal")
 *     .build());
 *
 * var resp = k9o.from("posts").select().limit(10).execute(Post.class);
 * resp.data().forEach(System.out::println);
 *
 * try {
 *     var session = k9o.gate().signIn(new Gate.SignInInput("u@x.com", "..."));
 * } catch (KError e) {
 *     System.err.printf("[%s] %s%n", e.getCode(), e.getMessage());
 * }
 * }</pre>
 */
public final class K9o {

    private K9o() {}

    /** Factory — constructs a {@link K9oClient} bound to the supplied opts. */
    public static K9oClient createClient(Opts opts) {
        String normalised = UrlGrammar.resolveBaseUrl(opts.baseUrl, opts.orgSlug);
        HttpTransport.Config config = new HttpTransport.Config(
            normalised,
            opts.apiKey,
            opts.orgSlug,
            opts.projectSlug,
            opts.defaultHeaders,
            opts.httpClient,
            opts.retryConfig
        );
        return new K9oClient(config);
    }

    /** Builder-pattern client options. */
    public static final class Opts {
        final String baseUrl;
        final String apiKey;
        final String orgSlug;
        final String projectSlug;
        final Map<String, String> defaultHeaders;
        final HttpClient httpClient;
        final RetryConfig retryConfig;

        private Opts(Builder b) {
            this.baseUrl = b.baseUrl;
            this.apiKey = b.apiKey;
            this.orgSlug = b.orgSlug;
            this.projectSlug = b.projectSlug;
            this.defaultHeaders = Map.copyOf(b.defaultHeaders);
            this.httpClient = b.httpClient;
            this.retryConfig = b.retryConfig;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String baseUrl;
            private String apiKey;
            private String orgSlug;
            private String projectSlug;
            private final Map<String, String> defaultHeaders = new HashMap<>();
            private HttpClient httpClient;
            private RetryConfig retryConfig;

            /** Optional gateway host root (scheme + host; no path). Defaults to the public cloud; with {@code orgSlug} on a platform host ({@code api.<apex>}) the org host is composed. */
            public Builder baseUrl(String v) { this.baseUrl = v; return this; }
            public Builder apiKey(String v) { this.apiKey = v; return this; }
            public Builder orgSlug(String v) { this.orgSlug = v; return this; }
            public Builder projectSlug(String v) { this.projectSlug = v; return this; }
            public Builder defaultHeader(String k, String v) { this.defaultHeaders.put(k, v); return this; }
            public Builder httpClient(HttpClient c) { this.httpClient = c; return this; }
            public Builder retryConfig(RetryConfig c) { this.retryConfig = c; return this; }

            public Opts build() {
                // baseUrl is OPTIONAL — when omitted it
                // defaults to the public-cloud platform host in resolveBaseUrl.
                return new Opts(this);
            }
        }
    }

    /**
     * The customer-facing client returned by {@link K9o#createClient}.
     */
    public static final class K9oClient {
        final HttpTransport.Config config;
        private final Projects projects;
        private final Gate gate;
        private final Studio studio;
        private final Compute compute;
        private final Ai ai;
        private final Realtime realtime;
        private final Org org;
        private final Orgs orgs;
        private final Migrations migrations;
        private final Functions functions;
        private final Policies policies;
        private final Audit audit;
        private final Me me;
        private final Kibo kibo;
        private final Storage storage;

        K9oClient(HttpTransport.Config config) {
            this.config = config;
            this.projects = new Projects(config);
            this.gate = new Gate(config);
            this.studio = new Studio(config);
            this.compute = new Compute(config);
            this.ai = new Ai(config);
            this.realtime = new Realtime(config);
            this.org = new Org(config);
            this.orgs = new Orgs(config);
            this.migrations = new Migrations(config);
            this.functions = new Functions(config);
            this.policies = new Policies(config);
            this.audit = new Audit(config);
            this.me = new Me(config);
            this.kibo = new Kibo(config);
            this.storage = new Storage(config);
        }

        /** Data-plane DSL entry point. */
        public FromBuilder from(String collection) {
            return new FromBuilder(config, collection);
        }

        /** Project lifecycle namespace. */
        public Projects projects() {
            return projects;
        }

        /** Gate (IAK) auth namespace; project-scoped. */
        public Gate gate() {
            return gate;
        }

        /** Studio namespace — workflows + dashboards. */
        public Studio studio() { return studio; }

        /** Compute namespace — LLMs registry. */
        public Compute compute() { return compute; }

        /** AI namespace — platform AI primitives ({@code ai().embed(...)}). */
        public Ai ai() { return ai; }

        /** Realtime subscriptions namespace. */
        public Realtime realtime() { return realtime; }

        /** Org-plane bound namespace — members/groups/smtp/auditLog/gate/defaultLLM. */
        public Org org() { return org; }

        /** Org lifecycle namespace (collection-style). */
        public Orgs orgs() { return orgs; }

        /** Migration lifecycle namespace. */
        public Migrations migrations() { return migrations; }

        /** Function lifecycle namespace. */
        public Functions functions() { return functions; }

        /** Policy bundle lifecycle namespace. */
        public Policies policies() { return policies; }

        /** Project-plane audit log namespace. */
        public Audit audit() { return audit; }

        /** Operator-self namespace per console/18. */
        public Me me() { return me; }

        /** Kibo Zero-Knowledge vault namespace — {@code kibo().vault().secrets()}. */
        public Kibo kibo() { return kibo; }

        /** Storage namespace: buckets and objects ({@code storage().from(bucket)} for objects). */
        public Storage storage() { return storage; }

        /** Bound baseUrl (read-only for advanced use). */
        public String baseUrl() {
            return config.baseUrl;
        }
    }
}
