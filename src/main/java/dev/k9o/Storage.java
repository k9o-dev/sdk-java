package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Storage buckets and objects — {@code client.storage()}: per-project logical buckets and the objects in
 * them, against {@code /@<projectSlug>/storage/buckets…}.
 *
 * <p>Bucket names are logical (the platform resolves them to a physical store). Signed URLs require an explicit
 * {@code expiresIn} (1..604800 seconds, no default). Not offered, by design: a signed PUT (the gateway has no route
 * for it; uploads go through the multipart {@link Bucket#upload}) and copy.
 */
public final class Storage {

    static final long MAX_EXPIRES_IN = 604_800L;

    private final HttpTransport.Config config;

    Storage(HttpTransport.Config config) {
        this.config = config;
    }

    // ---- types ----

    /** A bucket. {@code fileSizeLimit} / {@code deleteAfterDays} / {@code byosBindingId} are null when unset. */
    public record StorageBucket(
        @JsonProperty("name") String name,
        @JsonProperty("public") boolean isPublic,
        @JsonProperty("fileSizeLimit") Long fileSizeLimit,
        @JsonProperty("allowedMimeTypes") List<String> allowedMimeTypes,
        @JsonProperty("versioning") boolean versioning,
        @JsonProperty("deleteAfterDays") Integer deleteAfterDays,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt,
        @JsonProperty("system") boolean system,
        @JsonProperty("byosBindingId") String byosBindingId
    ) {}

    /** An object listing entry; {@code path} is relative to the bucket. */
    public record StorageObject(
        @JsonProperty("path") String path,
        @JsonProperty("size") long size,
        @JsonProperty("lastModified") String lastModified
    ) {}

    public record SignedUrl(
        @JsonProperty("signedUrl") String signedUrl,
        @JsonProperty("expiresIn") long expiresIn
    ) {}

    public record RemovedObject(
        @JsonProperty("bucket") String bucket,
        @JsonProperty("path") String path,
        @JsonProperty("deleted") boolean deleted
    ) {}

    public record DeletedBucket(
        @JsonProperty("name") String name,
        @JsonProperty("deleted") boolean deleted,
        @JsonProperty("objectsDeleted") long objectsDeleted
    ) {}

    /**
     * Upload options. {@code upsert} null/false refuses to overwrite an existing path (409 K9O_ALREADY_EXISTS);
     * {@code metadata} is caller-defined string metadata (limits enforced by the gateway).
     */
    public record UploadOptions(String contentType, Boolean upsert, Map<String, String> metadata) {
        public static UploadOptions none() { return new UploadOptions(null, null, null); }
    }

    /** Signed-URL options: {@code expiresIn} seconds (required, integer 1..604800), optional {@code download}. */
    public record SignedUrlOptions(Number expiresIn, Boolean download) {}

    // ---- helpers ----

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT",
                "storage buckets and objects require a bound projectSlug (per-project resource)",
                Map.of(), "Pass projectSlug when constructing the client.", null, null);
        }
        return config.projectSlug;
    }

    /** Percent-encode one path segment / query value (space is %20, never '+'). */
    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String bucketsPath(String suffix) {
        return UrlGrammar.projectPath(requireProjectSlug(), "storage/buckets" + suffix);
    }

    private String bucketPath(String name, String suffix) {
        if (name == null || name.isEmpty()) {
            throw invalid("bucket name must not be empty", "bucket", "empty");
        }
        if (name.equals(".") || name.equals("..")) {
            throw invalid("bucket name must not be a dot segment", "bucket", "dot_segment");
        }
        return bucketsPath("/" + enc(name) + suffix);
    }

    private static KError invalid(String message, String field, String reason) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, Map.of("field", field, "reason", reason), null, null, null);
    }

    static void requireObjectPath(String path) {
        if (path == null || path.isEmpty()) {
            throw invalid("object path must not be empty", "path", "empty");
        }
    }

    static long requireExpiresIn(Number n) {
        boolean ok = false;
        long v = 0;
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) {
            v = n.longValue();
            ok = v >= 1 && v <= MAX_EXPIRES_IN;
        } else if (n instanceof Float || n instanceof Double) {
            double d = n.doubleValue();
            ok = !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d) && d >= 1 && d <= MAX_EXPIRES_IN;
            v = (long) d;
        }
        if (!ok) {
            throw new KError("K9O_INVALID_FIELD_VALUE",
                "expiresIn is required and must be an integer from 1 to " + MAX_EXPIRES_IN + " seconds",
                Map.of("field", "expiresIn", "reason", "out_of_range"),
                "Pass an explicit expiresIn; signed URLs have no default lifetime.", null, null);
        }
        return v;
    }

    // ---- bucket operations ----

    /** List the project's buckets. {@code GET /@<slug>/storage/buckets}. */
    public Envelope<List<StorageBucket>> listBuckets() {
        return HttpTransport.request(config, "GET", bucketsPath(""), null, null,
            new TypeReference<Envelope<List<StorageBucket>>>() {});
    }

    /** Read one bucket. {@code GET …/buckets/<name>}. */
    public Envelope<StorageBucket> getBucket(String name) {
        return HttpTransport.request(config, "GET", bucketPath(name, ""), null, null,
            new TypeReference<Envelope<StorageBucket>>() {});
    }

    public Envelope<StorageBucket> createBucket(String name) {
        return createBucket(name, Map.of());
    }

    /**
     * Create a bucket. {@code POST …/buckets} with body {@code {name, ...options}}; options keys: {@code public},
     * {@code fileSizeLimit}, {@code allowedMimeTypes}, {@code versioning}, {@code deleteAfterDays},
     * {@code byosBindingId}. A name outside 3..63 lowercase letters, digits and hyphens is refused by the gateway.
     */
    public Envelope<StorageBucket> createBucket(String name, Map<String, Object> options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (options != null) body.putAll(options);
        return HttpTransport.request(config, "POST", bucketsPath(""), body, null,
            new TypeReference<Envelope<StorageBucket>>() {});
    }

    /** Change a bucket. {@code PATCH …/buckets/<name>}; an explicit null value in {@code patch} clears that limit. */
    public Envelope<StorageBucket> updateBucket(String name, Map<String, Object> patch) {
        return HttpTransport.request(config, "PATCH", bucketPath(name, ""), patch, null,
            new TypeReference<Envelope<StorageBucket>>() {});
    }

    public Envelope<DeletedBucket> deleteBucket(String name) {
        return deleteBucket(name, false);
    }

    /**
     * Delete a bucket. {@code DELETE …/buckets/<name>}. A non-empty bucket is refused (409 bucket_not_empty) unless
     * {@code empty} is true, which sends {@code ?empty=true} and purges its objects first.
     */
    public Envelope<DeletedBucket> deleteBucket(String name, boolean empty) {
        return HttpTransport.request(config, "DELETE", bucketPath(name, ""), null,
            empty ? Map.of("empty", "true") : null,
            new TypeReference<Envelope<DeletedBucket>>() {});
    }

    /** A handle on one bucket for object operations: {@code storage().from("avatars").upload(...)}. */
    public Bucket from(String bucket) {
        return new Bucket(bucket);
    }

    // ---- object operations ----

    /** Object operations on one bucket. */
    public final class Bucket {
        private final String bucket;

        private Bucket(String bucket) { this.bucket = bucket; }

        public Envelope<StorageObject> upload(String path, String data) {
            return upload(path, data, UploadOptions.none());
        }

        /** A string is sent as UTF-8. */
        public Envelope<StorageObject> upload(String path, String data, UploadOptions options) {
            return upload(path, data.getBytes(StandardCharsets.UTF_8), options);
        }

        public Envelope<StorageObject> upload(String path, byte[] data) {
            return upload(path, data, UploadOptions.none());
        }

        /** Read to the end and send; the stream is not closed. */
        public Envelope<StorageObject> upload(String path, InputStream data, UploadOptions options) {
            try {
                return upload(path, data.readAllBytes(), options);
            } catch (IOException e) {
                throw new KError("K9O_INVALID_FIELD_VALUE", "could not read the upload stream",
                    Map.of("field", "data", "reason", "unreadable"), null, null, null);
            }
        }

        /**
         * Upload an object. {@code POST …/buckets/<bucket>/objects} (multipart/form-data: parts {@code file},
         * {@code path}, optional {@code contentType}, {@code upsert}, {@code metadata}). Bytes are sent as given.
         */
        public Envelope<StorageObject> upload(String path, byte[] data, UploadOptions options) {
            requireObjectPath(path);
            String urlPath = bucketPath(bucket, "/objects");
            UploadOptions o = options == null ? UploadOptions.none() : options;
            String boundary = "----k9o" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 512);
            String last = path.substring(path.lastIndexOf('/') + 1);
            String filename = (last.isEmpty() ? "file" : last).replaceAll("[\\r\\n\"\\\\]", "_");
            String fileType = o.contentType() != null ? o.contentType() : "application/octet-stream";
            writeAscii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: " + fileType.replaceAll("[\\r\\n]", "") + "\r\n\r\n");
            out.writeBytes(data);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            textPart(out, boundary, "path", path);
            if (o.contentType() != null) textPart(out, boundary, "contentType", o.contentType());
            textPart(out, boundary, "upsert", Boolean.TRUE.equals(o.upsert()) ? "true" : "false");
            if (o.metadata() != null) {
                try {
                    textPart(out, boundary, "metadata", HttpTransport.JSON.writeValueAsString(o.metadata()));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new KError("K9O_INTERNAL_ERROR", "failed to marshal upload metadata");
                }
            }
            writeAscii(out, "--" + boundary + "--\r\n");
            return HttpTransport.request(config, "POST", urlPath,
                new HttpTransport.RawBody(out.toByteArray(), "multipart/form-data; boundary=" + boundary),
                null, new TypeReference<Envelope<StorageObject>>() {});
        }

        public Envelope<SignedUrl> createSignedUrl(String path, long expiresIn) {
            return createSignedUrl(path, new SignedUrlOptions(expiresIn, null));
        }

        /**
         * A pre-authenticated GET URL for one object. {@code POST …/objects/sign} with {@code {path, expiresIn,
         * download?}}. {@code expiresIn} is required (integer 1..604800) and refused client-side otherwise.
         */
        public Envelope<SignedUrl> createSignedUrl(String path, SignedUrlOptions options) {
            requireObjectPath(path);
            long exp = requireExpiresIn(options == null ? null : options.expiresIn());
            String urlPath = bucketPath(bucket, "/objects/sign");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("path", path);
            body.put("expiresIn", exp);
            if (options.download() != null) body.put("download", options.download());
            return HttpTransport.request(config, "POST", urlPath, body, null,
                new TypeReference<Envelope<SignedUrl>>() {});
        }

        public Envelope<List<StorageObject>> list() {
            return list(null);
        }

        /** List objects, optionally under a prefix. {@code GET …/objects?prefix=}. */
        public Envelope<List<StorageObject>> list(String prefix) {
            return HttpTransport.request(config, "GET", bucketPath(bucket, "/objects"), null,
                prefix != null ? Map.of("prefix", prefix) : null,
                new TypeReference<Envelope<List<StorageObject>>>() {});
        }

        /** Delete one object. {@code DELETE …/objects?path=}. */
        public Envelope<RemovedObject> remove(String path) {
            requireObjectPath(path);
            return HttpTransport.request(config, "DELETE", bucketPath(bucket, "/objects"), null,
                Map.of("path", path), new TypeReference<Envelope<RemovedObject>>() {});
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void textPart(ByteArrayOutputStream out, String boundary, String name, String value) {
        writeAscii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
    }
}
