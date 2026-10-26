package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * User data streams namespace — {@code client.streams()}.
 *
 * <p>Control plane ({@link #create}, {@link #list}, {@link #get}, {@link #delete}) and the data plane over
 * {@code /@<slug>/data-streams/user/<stream>}: {@link #publish}, {@link #replay} (one bounded read) and
 * {@link #subscribe} (SSE live tail). The gateway refuses data-plane calls with {@code K9O_STREAM_UNAVAILABLE}
 * (503) while the plane is not available; it is thrown as a {@link KError}.
 */
public final class Streams {

    private final HttpTransport.Config config;

    Streams(HttpTransport.Config config) { this.config = config; }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "streams operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    private String base(String slug) { return UrlGrammar.projectPath(slug, "data-streams/user"); }

    /** A user data stream as the gateway returns it. {@code maxBytes} is null when there is no storage ceiling. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserStream(
        @JsonProperty("id") String id,
        @JsonProperty("slug") String slug,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("subjectPrefix") String subjectPrefix,
        @JsonProperty("region") String region,
        @JsonProperty("retentionSeconds") long retentionSeconds,
        @JsonProperty("retentionClass") String retentionClass,
        @JsonProperty("maxBytes") Long maxBytes,
        @JsonProperty("status") String status,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("createdBy") String createdBy
    ) {}

    /** Result of {@link #delete}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Deleted(
        @JsonProperty("slug") String slug,
        @JsonProperty("status") String status
    ) {}

    /** Result of {@link #publish}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublishResult(
        @JsonProperty("streamSlug") String streamSlug,
        @JsonProperty("subject") String subject,
        @JsonProperty("bytes") long bytes,
        @JsonProperty("accepted") boolean accepted
    ) {}

    /** One event of a stream: {@code sequence} is the stream sequence (a decimal string); {@code data} the published JSON. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamEvent(
        @JsonProperty("sequence") String sequence,
        @JsonProperty("subject") String subject,
        @JsonProperty("data") Object data,
        @JsonProperty("publishedAt") String publishedAt
    ) {}

    /** A page of events from {@link #replay}. Pass {@code nextCursor} as the next {@code fromSequence} while {@code hasMore}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplayResult(
        @JsonProperty("streamSlug") String streamSlug,
        @JsonProperty("events") List<StreamEvent> events,
        @JsonProperty("nextCursor") String nextCursor,
        @JsonProperty("hasMore") boolean hasMore
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateBody(
        @JsonProperty("displayName") String displayName,
        @JsonProperty("maxBytes") long maxBytes,
        @JsonProperty("retentionSeconds") Long retentionSeconds
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReplayBody(
        @JsonProperty("fromSequence") Long fromSequence,
        @JsonProperty("untilSequence") Long untilSequence,
        @JsonProperty("limit") Integer limit
    ) {}

    /** Create a stream. {@code maxBytes} is the storage ceiling; {@code retentionSeconds} may be null (gateway default). */
    public Envelope<UserStream> create(String displayName, long maxBytes, Long retentionSeconds) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", base(slug),
            new CreateBody(displayName, maxBytes, retentionSeconds), null,
            new TypeReference<Envelope<UserStream>>() {});
    }

    /** List the project's streams. */
    public Envelope<List<UserStream>> list() {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", base(slug), null, null,
            new TypeReference<Envelope<List<UserStream>>>() {});
    }

    /** Get one stream by slug. */
    public Envelope<UserStream> get(String streamSlug) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", base(slug) + "/" + enc(streamSlug), null, null,
            new TypeReference<Envelope<UserStream>>() {});
    }

    /** Retire a stream. */
    public Envelope<Deleted> delete(String streamSlug) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "DELETE", base(slug) + "/" + enc(streamSlug), null, null,
            new TypeReference<Envelope<Deleted>>() {});
    }

    /** Append an event. {@code data} is any JSON-serialisable value; {@code subject} (nullable) is a dot-separated routing suffix. */
    public Envelope<PublishResult> publish(String streamSlug, Object data, String subject) {
        String slug = requireProjectSlug();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        if (subject != null) body.put("subject", subject);
        return HttpTransport.request(config, "POST", base(slug) + "/" + enc(streamSlug) + "/publish", body, null,
            new TypeReference<Envelope<PublishResult>>() {});
    }

    /** Read a bounded range of events in one call. Any argument may be null. */
    public Envelope<ReplayResult> replay(String streamSlug, Long fromSequence, Long untilSequence, Integer limit) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", base(slug) + "/" + enc(streamSlug) + "/replay",
            new ReplayBody(fromSequence, untilSequence, limit), null,
            new TypeReference<Envelope<ReplayResult>>() {});
    }

    /**
     * Tail a stream over SSE. {@code fromSequence} (nullable) resumes after that sequence. The first connection is
     * opened here, so a refusal such as {@code K9O_STREAM_UNAVAILABLE} is thrown now. Iterate with
     * {@code for (var event : sub)}; the server closes each connection after a bounded time and the subscription
     * reconnects from the last delivered sequence. Close it (try-with-resources) to stop.
     */
    public StreamSubscription subscribe(String streamSlug, String fromSequence) {
        String path = base(requireProjectSlug()) + "/" + enc(streamSlug) + "/subscribe";
        return new StreamSubscription(config, path, fromSequence);
    }

    /** A live tail of a stream; see {@link Streams#subscribe}. */
    public static final class StreamSubscription implements Iterable<StreamEvent>, AutoCloseable {
        private final HttpTransport.Config config;
        private final String path;
        private volatile String last;
        private volatile Duration delay = Duration.ofSeconds(1);
        private volatile boolean closed;
        private volatile EventStream<StreamEvent> current;

        StreamSubscription(HttpTransport.Config config, String path, String fromSequence) {
            this.config = config;
            this.path = path;
            this.last = fromSequence;
            this.current = open();
        }

        /** Pause before reconnecting after the server closes a connection (default one second). */
        public StreamSubscription reconnectDelay(Duration d) {
            this.delay = d;
            return this;
        }

        private EventStream<StreamEvent> open() {
            Map<String, String> q = new LinkedHashMap<>();
            if (last != null && !last.isEmpty()) q.put("fromSequence", last);
            return new EventStream<>(
                HttpTransport.openSseLines(config, "GET", path, null, q),
                new TypeReference<StreamEvent>() {},
                e -> e.sequence() != null
            );
        }

        @Override
        public Iterator<StreamEvent> iterator() {
            return new Iterator<>() {
                private Iterator<StreamEvent> inner;
                private StreamEvent next;

                @Override
                public boolean hasNext() {
                    if (next != null) return true;
                    while (!closed) {
                        if (inner == null) {
                            EventStream<StreamEvent> cur = current;
                            if (cur == null) {
                                try {
                                    Thread.sleep(delay.toMillis());
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    return false;
                                }
                                if (closed) return false;
                                cur = open();
                                current = cur;
                            }
                            inner = cur.iterator();
                        }
                        boolean more;
                        try {
                            more = inner.hasNext();
                        } catch (RuntimeException e) {
                            if (closed) return false;
                            throw e;
                        }
                        if (more) {
                            next = inner.next();
                            last = next.sequence();
                            return true;
                        }
                        // The server closed the connection: reconnect from the last sequence.
                        EventStream<StreamEvent> done = current;
                        current = null;
                        inner = null;
                        if (done != null) done.close();
                    }
                    return false;
                }

                @Override
                public StreamEvent next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    StreamEvent out = next;
                    next = null;
                    return out;
                }
            };
        }

        /** Stop the subscription and release the connection. */
        @Override
        public void close() {
            closed = true;
            EventStream<StreamEvent> cur = current;
            if (cur != null) cur.close();
        }
    }
}
