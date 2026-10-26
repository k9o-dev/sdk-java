package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema namespace: {@code client.schema()}.
 *
 * <p>{@code collections().list(limit, cursor)} is {@code GET /@<slug>/schema/collections}. The list is
 * cursor-paginated, ordered by collection name: pass {@code meta().cursor().next()} of one page as {@code cursor}
 * of the next, until it is null.
 */
public final class Schema {

    private final Collections collections;

    Schema(HttpTransport.Config config) {
        this.collections = new Collections(config);
    }

    /** The project's collections. */
    public Collections collections() { return collections; }

    /** One collection (table) of the project's data. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectionSummary(
        @JsonProperty("name") String name,
        /** Live row count, excluding soft-deleted rows. Ignores row-level security. */
        @JsonProperty("rowCount") long rowCount,
        @JsonProperty("rowCountIsExact") boolean rowCountIsExact,
        @JsonProperty("columnCount") int columnCount,
        @JsonProperty("indexCount") int indexCount,
        @JsonProperty("lastMigrationSlug") String lastMigrationSlug,
        @JsonProperty("lastMigrationAppliedAt") String lastMigrationAppliedAt
    ) {}

    /** Collections sub-namespace. */
    public static final class Collections {
        private final HttpTransport.Config config;

        Collections(HttpTransport.Config config) { this.config = config; }

        /** First page of collections, default page size. */
        public Envelope<List<CollectionSummary>> list() {
            return list(null, null);
        }

        /** One page of collections by name. {@code limit} is 1 to 200 (default 50); {@code cursor} is {@code meta().cursor().next()} of the previous page. Null arguments are not sent. */
        public Envelope<List<CollectionSummary>> list(Integer limit, String cursor) {
            if (config.projectSlug == null || config.projectSlug.isEmpty()) {
                throw new KError("K9O_MISSING_PROJECT_CONTEXT", "schema operations require projectSlug bound on the client");
            }
            if (limit != null && (limit < 1 || limit > 200)) {
                throw new KError("K9O_INVALID_FIELD_VALUE", "limit must be a whole number from 1 to 200",
                    Map.of("limit", limit), null, null, null);
            }
            Map<String, String> q = new LinkedHashMap<>();
            if (limit != null) q.put("limit", String.valueOf(limit));
            if (cursor != null) q.put("cursor", cursor);
            return HttpTransport.request(config, "GET", UrlGrammar.projectPath(config.projectSlug, "schema/collections"),
                null, q, new TypeReference<Envelope<List<CollectionSummary>>>() {});
        }
    }
}
