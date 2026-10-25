package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * Project lifecycle namespace — {@code client.projects().*}.
 */
public class Projects {

    private final HttpTransport.Config config;

    Projects(HttpTransport.Config config) {
        this.config = config;
    }

    /** Project record. Jackson maps wire camelCase verbatim. */
    public record Project(
        @JsonProperty("id") String id,
        @JsonProperty("slug") String slug,
        @JsonProperty("name") String name,
        @JsonProperty("custodyMode") String custodyMode,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt,
        @JsonProperty("orgId") String orgId
    ) {}

    public record CreateInput(
        @JsonProperty("slug") String slug,
        @JsonProperty("name") String name,
        @JsonProperty("custodyMode") String custodyMode
    ) {}

    public record UpdateInput(
        @JsonProperty("name") String name
    ) {}

    /** List projects accessible to the caller. */
    public Envelope<List<Project>> list() {
        return HttpTransport.request(config, "GET", UrlGrammar.orgPath("projects"),
            null, null, new TypeReference<Envelope<List<Project>>>() {});
    }

    /** Create a new project under the caller's active org. */
    public Envelope<Project> create(CreateInput input) {
        UrlGrammar.validateSlug(input.slug(), "project");
        return HttpTransport.request(config, "POST", UrlGrammar.orgPath("projects"),
            input, null, new TypeReference<Envelope<Project>>() {});
    }

    /** Get a project by slug. */
    public Envelope<Project> get(String slug) {
        UrlGrammar.validateSlug(slug, "project");
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "project"),
            null, null, new TypeReference<Envelope<Project>>() {});
    }

    /** Update a project's mutable fields. */
    public Envelope<Project> update(String slug, UpdateInput patch) {
        UrlGrammar.validateSlug(slug, "project");
        return HttpTransport.request(config, "PATCH", UrlGrammar.projectPath(slug, "project"),
            patch, null, new TypeReference<Envelope<Project>>() {});
    }
}
