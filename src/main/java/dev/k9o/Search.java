package dev.k9o;

import dev.k9o.data.FromBuilder;

import java.util.List;
import java.util.Map;

/**
 * {@code client.search(collection, input)}: full-text search over one text column of a collection. Sugar over
 * {@code from(collection).select().textSearch(column, q, ...)}; the result is the normal list envelope, best
 * matches first, and {@code limit} / {@code cursor} page it.
 */
public final class Search {

    private Search() {}

    /**
     * Search request. {@code column} and {@code q} are required; {@code language} (default {@code english}),
     * {@code type} ({@code plain}, {@code phrase} or {@code websearch}; default {@code plain}), {@code limit} and
     * {@code cursor} are optional and {@code null} means "not set".
     */
    public record Input(String column, String q, String language, String type, Integer limit, String cursor) {
        public static Input of(String column, String q) {
            return new Input(column, q, null, null, null, null);
        }
    }

    static <T> Envelope<List<T>> run(HttpTransport.Config config, String collection, Input input, Class<T> rowType) {
        if (input == null) {
            throw new KError("K9O_INVALID_FIELD_VALUE", "search requires a column and a query");
        }
        FromBuilder.SelectBuilder b = new FromBuilder(config, collection).select()
            .textSearch(input.column(), input.q(), new FromBuilder.TextSearchOptions(input.language(), input.type()));
        if (input.limit() != null) b.limit(input.limit());
        if (input.cursor() != null) b.cursor(input.cursor());
        return b.execute(rowType);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Envelope<List<Map<String, Object>>> runMaps(HttpTransport.Config config, String collection, Input input) {
        return (Envelope) run(config, collection, input, Map.class);
    }
}
