package dev.k9o;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code client.query(sql, params)}: one parameterised, read-only SQL statement against the project's data.
 *
 * <p>Wire: {@code POST /@<slug>/query} with body {@code {sql, params?}}, answered with {@code {data: rows, meta}}.
 *
 * <p>{@code params} is a {@link List} (positional, bound to {@code $1, $2, ...}; the SQL is sent as written) or a
 * {@link Map} of named values: {@code :name} placeholders are rewritten to {@code $n} here and the values become the
 * positional list. Values are always bound by the database and never put into the SQL text.
 *
 * <p>The gateway allows read-only SQL only (a write is refused with {@code K9O_RAW_SQL_READ_ONLY}), one statement
 * per call, at most 100000 characters of SQL and 1000 parameters, and needs the project-editor role.
 */
public final class Query {

    private Query() {}

    /** SQL with {@code :name} placeholders rewritten, and the values in positional order. */
    public record Translated(String sql, List<Object> params) {}

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    private static boolean identStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean identChar(char c) {
        return identStart(c) || (c >= '0' && c <= '9');
    }

    static Object checkValue(Object value, String where) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) {
            if (n instanceof Double d && !Double.isFinite(d)) {
                throw invalid(where + " must be a finite number", Map.of());
            }
            if (n instanceof Float f && !Float.isFinite(f)) {
                throw invalid(where + " must be a finite number", Map.of());
            }
            return value;
        }
        throw invalid(where + " must be a string, number, boolean or null; serialise anything else "
            + "(for example as JSON text) first", Map.of());
    }

    /**
     * Rewrite {@code :name} placeholders to {@code $n}. Quoted text, comments, dollar-quoted strings and
     * {@code ::} casts are copied as written.
     */
    public static Translated translateNamedParams(String sql, Map<String, ?> named) {
        List<String> order = new ArrayList<>();
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            boolean eString = (c == 'E' || c == 'e') && next == '\''
                && !(i > 0 && identChar(sql.charAt(i - 1)));
            if (c == '\'' || eString) {
                int j = eString ? i + 2 : i + 1;
                for (; j < n; j++) {
                    char d = sql.charAt(j);
                    if (eString && d == '\\') { j++; continue; }
                    if (d == '\'') {
                        if (j + 1 < n && sql.charAt(j + 1) == '\'') { j++; continue; }
                        break;
                    }
                }
                int end = Math.min(j + 1, n);
                out.append(sql, i, end);
                i = end;
            } else if (c == '"') {
                int j = i + 1;
                for (; j < n; j++) {
                    if (sql.charAt(j) == '"') {
                        if (j + 1 < n && sql.charAt(j + 1) == '"') { j++; continue; }
                        break;
                    }
                }
                int end = Math.min(j + 1, n);
                out.append(sql, i, end);
                i = end;
            } else if (c == '-' && next == '-') {
                int j = sql.indexOf('\n', i);
                if (j == -1) j = n;
                out.append(sql, i, j);
                i = j;
            } else if (c == '/' && next == '*') {
                int depth = 1;
                int j = i + 2;
                while (j < n && depth > 0) {
                    if (sql.charAt(j) == '/' && j + 1 < n && sql.charAt(j + 1) == '*') { depth++; j += 2; }
                    else if (sql.charAt(j) == '*' && j + 1 < n && sql.charAt(j + 1) == '/') { depth--; j += 2; }
                    else j++;
                }
                out.append(sql, i, j);
                i = j;
            } else if (c == '$') {
                int j = i + 1;
                if (j < n && identStart(sql.charAt(j))) {
                    while (j < n && identChar(sql.charAt(j))) j++;
                }
                if (j < n && sql.charAt(j) == '$') {
                    String tag = sql.substring(i, j + 1);
                    int close = sql.indexOf(tag, j + 1);
                    int end = close == -1 ? n : close + tag.length();
                    out.append(sql, i, end);
                    i = end;
                } else {
                    out.append(c);
                    i++;
                }
            } else if (c == ':' && next == ':') {
                out.append("::");
                i += 2;
            } else if (c == ':' && identStart(next) && !(i > 0 && identChar(sql.charAt(i - 1)))) {
                int j = i + 2;
                while (j < n && identChar(sql.charAt(j))) j++;
                String name = sql.substring(i + 1, j);
                int idx = order.indexOf(name);
                if (idx == -1) {
                    if (named == null || !named.containsKey(name)) {
                        throw invalid("no value for :" + name, Map.of("name", name));
                    }
                    order.add(name);
                    idx = order.size() - 1;
                }
                out.append('$').append(idx + 1);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        List<Object> values = new ArrayList<>(order.size());
        for (String name : order) values.add(checkValue(named.get(name), "the value for :" + name));
        return new Translated(out.toString(), values);
    }

    /** POST /@&lt;slug&gt;/query. */
    static Envelope<List<Map<String, Object>>> run(HttpTransport.Config config, String sql, List<?> positional,
                                                   Map<String, ?> named) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "query requires projectSlug bound on the client");
        }
        if (sql == null) throw invalid("sql must be a string", Map.of());
        String text = sql;
        List<Object> values = new ArrayList<>();
        if (positional != null) {
            for (int i = 0; i < positional.size(); i++) values.add(checkValue(positional.get(i), "params[" + i + "]"));
        } else if (named != null) {
            Translated t = translateNamedParams(sql, named);
            text = t.sql();
            values = t.params();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", text);
        if (!values.isEmpty()) body.put("params", values);
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(config.projectSlug, "query"),
            body, null, new TypeReference<Envelope<List<Map<String, Object>>>>() {});
    }
}
