package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/**
 * Project lifecycle namespace — {@code client.projects().*}.
 */
public class Projects {

    private final HttpTransport.Config config;

    Projects(HttpTransport.Config config) {
        this.config = config;
    }

    /** Project record. Jackson maps wire camelCase verbatim. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(
        @JsonProperty("id") String id,
        @JsonProperty("slug") String slug,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("description") String description,
        @JsonProperty("region") String region,
        @JsonProperty("custodyMode") String custodyMode,
        @JsonProperty("status") String status,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("orgId") String orgId
    ) {}

    /**
     * Body of {@code POST /projects}. Property names are the gateway's. {@code displayName} is required (1 to 128
     * characters); the gateway derives the slug from it when {@code slug} is null. {@code custodyMode} is one of
     * {@code PLATFORM_MANAGED}, {@code SKR}, {@code BYOK}, {@code HYOK}. Null fields are not sent.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateInput(
        @JsonProperty("displayName") String displayName,
        @JsonProperty("slug") String slug,
        @JsonProperty("region") String region,
        @JsonProperty("custodyMode") String custodyMode,
        @JsonProperty("processingContexts") List<String> processingContexts,
        @JsonProperty("idempotencyKey") String idempotencyKey
    ) {
        public CreateInput(String displayName) {
            this(displayName, null, null, null, null, null);
        }
    }

    /** Body of {@code PATCH /projects/<slug>}. Null fields are not sent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateInput(
        @JsonProperty("displayName") String displayName,
        @JsonProperty("description") String description,
        @JsonProperty("softDeleteRetentionDays") Integer softDeleteRetentionDays,
        @JsonProperty("processingContexts") List<String> processingContexts
    ) {}

    /** List projects accessible to the caller. */
    public Envelope<List<Project>> list() {
        return HttpTransport.request(config, "GET", UrlGrammar.orgPath("projects"),
            null, null, new TypeReference<Envelope<List<Project>>>() {});
    }

    /** Create a new project under the caller's active org. */
    public Envelope<Project> create(CreateInput input) {
        if (input.slug() != null) UrlGrammar.validateSlug(input.slug(), "project");
        return HttpTransport.request(config, "POST", UrlGrammar.orgPath("projects"),
            input, null, new TypeReference<Envelope<Project>>() {});
    }

    /** Get a project by slug. */
    public Envelope<Project> get(String slug) {
        UrlGrammar.validateSlug(slug, "project");
        return HttpTransport.request(config, "GET", UrlGrammar.orgPath("projects/" + slug),
            null, null, new TypeReference<Envelope<Project>>() {});
    }

    /** Update a project's mutable fields. */
    public Envelope<Project> update(String slug, UpdateInput patch) {
        UrlGrammar.validateSlug(slug, "project");
        return HttpTransport.request(config, "PATCH", UrlGrammar.orgPath("projects/" + slug),
            patch, null, new TypeReference<Envelope<Project>>() {});
    }
}
