package dev.k9o;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * Typed async-iteration handle over a Server-Sent-Events line stream.
 *
 * <p>Used by Compute LLMs {@code invokeStream} + Realtime
 * {@code subscribe} SSE transport. The Java
 * iteration idiom is {@code Iterable<T>} — consumers write:
 *
 * <pre>{@code
 * try (var stream = client.compute().llms().invokeStream("gpt-4o", input)) {
 *     for (var chunk : stream) {
 *         // chunk is the typed event class supplied by the caller
 *     }
 * }
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} — use try-with-resources to
 * guarantee the underlying connection releases.
 */
public final class EventStream<T> implements Iterable<T>, AutoCloseable {

    private final Stream<String> lines;
    private final TypeReference<T> eventType;
    private final java.util.function.Predicate<T> accept;

    EventStream(Stream<String> lines, TypeReference<T> eventType) {
        this(lines, eventType, t -> true);
    }

    /** {@code accept} drops decoded frames that are not events (for example a {@code resync} control frame). */
    EventStream(Stream<String> lines, TypeReference<T> eventType, java.util.function.Predicate<T> accept) {
        this.lines = lines;
        this.eventType = eventType;
        this.accept = accept;
    }

    @Override
    public Iterator<T> iterator() {
        return new SseIterator();
    }

    @Override
    public void close() {
        lines.close();
    }

    private final class SseIterator implements Iterator<T> {
        private final Iterator<String> raw = lines.iterator();
        private final List<String> dataLines = new ArrayList<>();
        private T next;
        private boolean exhausted;

        @Override
        public boolean hasNext() {
            if (next != null) return true;
            if (exhausted) return false;
            advance();
            return next != null;
        }

        @Override
        public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            T out = next;
            next = null;
            return out;
        }

        private void advance() {
            while (raw.hasNext()) {
                String line = raw.next();
                if (line == null) continue;
                if (line.isEmpty()) {
                    if (!dataLines.isEmpty()) {
                        String payload = String.join("\n", dataLines);
                        dataLines.clear();
                        if ("[DONE]".equals(payload)) { exhausted = true; return; }
                        try {
                            T decoded = HttpTransport.JSON.readValue(payload, eventType);
                            if (decoded != null && accept.test(decoded)) {
                                next = decoded;
                                return;
                            }
                            continue;
                        } catch (JsonProcessingException e) {
                            exhausted = true;
                            throw new KError("K9O_INTERNAL_ERROR",
                                "failed to decode stream chunk: " + e.getMessage());
                        }
                    }
                    continue;
                }
                if (line.startsWith(":")) continue;
                if (line.startsWith("data:")) {
                    String rest = line.substring(5);
                    if (rest.startsWith(" ")) rest = rest.substring(1);
                    dataLines.add(rest);
                }
            }
            // EOF — flush trailing event if any
            if (!dataLines.isEmpty()) {
                String payload = String.join("\n", dataLines);
                dataLines.clear();
                if (!"[DONE]".equals(payload)) {
                    try {
                        T decoded = HttpTransport.JSON.readValue(payload, eventType);
                        if (decoded != null && accept.test(decoded)) next = decoded;
                    } catch (JsonProcessingException e) {
                        throw new KError("K9O_INTERNAL_ERROR",
                            "failed to decode stream chunk: " + e.getMessage());
                    }
                }
            }
            exhausted = true;
        }
    }
}
