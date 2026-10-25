package dev.k9o;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** {@code client.storage()} buckets and objects: routes, multipart, expiresIn bounds, encoding, error passthrough. */
class StorageTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String BUCKET_JSON = "{\"name\":\"avatars\",\"public\":false,\"fileSizeLimit\":null,\"allowedMimeTypes\":[],"
        + "\"versioning\":false,\"deleteAfterDays\":null,\"createdAt\":\"c\",\"updatedAt\":\"u\",\"system\":false,\"byosBindingId\":null}";

    record Seen(String method, String uri, String contentType, byte[] body) {
        String bodyText() { return new String(body, StandardCharsets.ISO_8859_1); }
    }

    HttpServer server;
    String baseUrl;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String reply = "{\"data\":" + BUCKET_JSON + ",\"meta\":{}}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
                ex.getRequestHeaders().getFirst("content-type"), ex.getRequestBody().readAllBytes()));
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    Storage storage() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("member-portal").build()).storage();
    }

    Seen only() {
        assertEquals(1, seen.size());
        return seen.get(0);
    }

    @Test
    void listBucketsGets() {
        reply = "{\"data\":[" + BUCKET_JSON + "],\"meta\":{}}";
        var r = storage().listBuckets();
        assertEquals("GET", only().method());
        assertEquals("/@member-portal/storage/buckets", only().uri());
        assertEquals("avatars", r.data().get(0).name());
    }

    @Test
    void getBucketEncodesName() {
        storage().getBucket("a b/c");
        assertEquals("GET", only().method());
        assertEquals("/@member-portal/storage/buckets/a%20b%2Fc", only().uri());
    }

    @Test
    void createBucketPostsNamePlusOptionsAsJson() throws Exception {
        var opts = new LinkedHashMap<String, Object>();
        opts.put("public", true);
        opts.put("fileSizeLimit", 1024);
        var r = storage().createBucket("avatars", opts);
        assertEquals("POST", only().method());
        assertEquals("/@member-portal/storage/buckets", only().uri());
        assertEquals("application/json", only().contentType());
        assertEquals(M.readTree("{\"name\":\"avatars\",\"public\":true,\"fileSizeLimit\":1024}"), M.readTree(only().body()));
        assertFalse(r.data().isPublic());
    }

    @Test
    void updateBucketPatchesAndNullClears() throws Exception {
        var patch = new LinkedHashMap<String, Object>();
        patch.put("fileSizeLimit", null);
        patch.put("versioning", true);
        storage().updateBucket("avatars", patch);
        assertEquals("PATCH", only().method());
        assertEquals("/@member-portal/storage/buckets/avatars", only().uri());
        assertEquals(M.readTree("{\"fileSizeLimit\":null,\"versioning\":true}"), M.readTree(only().body()));
    }

    @Test
    void deleteBucketSendsEmptyOnlyWhenTrue() {
        reply = "{\"data\":{\"name\":\"avatars\",\"deleted\":true,\"objectsDeleted\":3},\"meta\":{}}";
        var r = storage().deleteBucket("avatars", true);
        storage().deleteBucket("avatars", false);
        storage().deleteBucket("avatars");
        assertEquals("DELETE", seen.get(0).method());
        assertEquals("/@member-portal/storage/buckets/avatars?empty=true", seen.get(0).uri());
        assertEquals("/@member-portal/storage/buckets/avatars", seen.get(1).uri());
        assertEquals("/@member-portal/storage/buckets/avatars", seen.get(2).uri());
        assertEquals(3, r.data().objectsDeleted());
    }

    @Test
    void uploadSendsRealMultipartNotJson() {
        reply = "{\"data\":{\"path\":\"a/b.txt\",\"size\":5,\"lastModified\":null},\"meta\":{}}";
        var opts = new Storage.UploadOptions("text/plain", true, Map.of("k", "v"));
        var r = storage().from("avatars").upload("a/b.txt", "héllo", opts);
        Seen s = only();
        assertEquals("POST", s.method());
        assertEquals("/@member-portal/storage/buckets/avatars/objects", s.uri());
        assertTrue(s.contentType().startsWith("multipart/form-data; boundary="), s.contentType());
        assertFalse(s.contentType().contains("json"));
        String boundary = s.contentType().substring(s.contentType().indexOf("boundary=") + 9);
        String body = s.bodyText();
        assertTrue(body.startsWith("--" + boundary + "\r\n"));
        assertTrue(body.endsWith("--" + boundary + "--\r\n"));
        assertTrue(body.contains("name=\"file\"; filename=\"b.txt\"\r\nContent-Type: text/plain\r\n\r\nhÃ©llo\r\n"),
            "string must be sent as UTF-8");
        assertTrue(body.contains("name=\"path\"\r\n\r\na/b.txt\r\n"));
        assertTrue(body.contains("name=\"contentType\"\r\n\r\ntext/plain\r\n"));
        assertTrue(body.contains("name=\"upsert\"\r\n\r\ntrue\r\n"));
        assertTrue(body.contains("name=\"metadata\"\r\n\r\n{\"k\":\"v\"}\r\n"));
        assertEquals(5, r.data().size());
    }

    @Test
    void uploadDefaultsUpsertFalseAndSendsBytesAndStreamsAsGiven() {
        byte[] raw = {0, 1, 2, (byte) 0xff, 13, 10};
        storage().from("avatars").upload("x.bin", raw);
        storage().from("avatars").upload("y.bin", new ByteArrayInputStream(raw), Storage.UploadOptions.none());
        for (Seen s : seen) {
            String body = s.bodyText();
            assertTrue(body.contains("name=\"upsert\"\r\n\r\nfalse\r\n"));
            assertFalse(body.contains("name=\"contentType\""));
            assertFalse(body.contains("name=\"metadata\""));
            assertTrue(body.contains("\u0000\u0001\u0002ÿ\r\n\r\n--") || body.contains("\u0000\u0001\u0002ÿ\r\n\r\n"));
            assertTrue(body.contains("application/octet-stream"));
        }
    }

    @Test
    void signedUrlPostsPathExpiresInDownload() throws Exception {
        reply = "{\"data\":{\"signedUrl\":\"https://x/y\",\"expiresIn\":60},\"meta\":{}}";
        var r = storage().from("avatars").createSignedUrl("a/b.png", new Storage.SignedUrlOptions(60, true));
        assertEquals("POST", only().method());
        assertEquals("/@member-portal/storage/buckets/avatars/objects/sign", only().uri());
        assertEquals(M.readTree("{\"path\":\"a/b.png\",\"expiresIn\":60,\"download\":true}"), M.readTree(only().body()));
        assertEquals("https://x/y", r.data().signedUrl());
    }

    @Test
    void signedUrlOmitsDownloadWhenUnset() throws Exception {
        storage().from("avatars").createSignedUrl("a", 1);
        JsonNode n = M.readTree(only().body());
        assertFalse(n.has("download"));
    }

    @Test
    void expiresInBounds() {
        var b = storage().from("avatars");
        for (Number bad : new Number[] {0, -1, 604801, 604801L, 604801.0, 0.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY, null}) {
            KError e = assertThrows(KError.class, () -> b.createSignedUrl("a", new Storage.SignedUrlOptions(bad, null)),
                String.valueOf(bad));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        }
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> b.createSignedUrl("a", null)).getCode());
        assertEquals(0, seen.size(), "no request on refusal");
        b.createSignedUrl("a", 1);
        b.createSignedUrl("a", 604800);
        b.createSignedUrl("a", new Storage.SignedUrlOptions(60.0, null));
        assertEquals(3, seen.size());
    }

    @Test
    void listAddsPrefixOnlyWhenGivenAndEncodesIt() {
        reply = "{\"data\":[{\"path\":\"a\",\"size\":1,\"lastModified\":null}],\"meta\":{}}";
        var b = storage().from("avatars");
        b.list();
        b.list("my dir/a&b");
        assertEquals("/@member-portal/storage/buckets/avatars/objects", seen.get(0).uri());
        assertEquals("GET", seen.get(1).method());
        String q = seen.get(1).uri();
        assertTrue(q.startsWith("/@member-portal/storage/buckets/avatars/objects?prefix="));
        assertTrue(q.contains("a%26b"), q);
        assertFalse(q.contains(" ") || q.substring(q.indexOf('?')).contains("&"), q);
    }

    @Test
    void removeEncodesPathQueryAndKeepsDotDotUnrewritten() {
        reply = "{\"data\":{\"bucket\":\"avatars\",\"path\":\"p\",\"deleted\":true},\"meta\":{}}";
        var b = storage().from("avatars");
        b.remove("my file&x.png");
        b.remove("../etc/passwd");
        assertEquals("DELETE", seen.get(0).method());
        String q0 = seen.get(0).uri();
        assertTrue(q0.startsWith("/@member-portal/storage/buckets/avatars/objects?path="), q0);
        assertTrue(q0.contains("%26x.png"), q0);
        assertFalse(q0.substring(q0.indexOf('?')).contains("&"), q0);
        assertEquals("/@member-portal/storage/buckets/avatars/objects?path=..%2Fetc%2Fpasswd", seen.get(1).uri());
    }

    @Test
    void bucketNameWithDotDotIsEncodedNotRaw() {
        reply = "{\"data\":[],\"meta\":{}}";
        storage().from("../x").list();
        assertEquals("/@member-portal/storage/buckets/..%2Fx/objects", only().uri());
    }

    @Test
    void emptyPathOrBucketRefusedBeforeAnyRequest() {
        var b = storage().from("avatars");
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> b.remove("")).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> b.upload("", "x")).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> b.createSignedUrl("", 60)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> storage().getBucket("")).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> storage().from("").list()).getCode());
        assertEquals(0, seen.size());
    }

    @Test
    void dotSegmentBucketNamesRefusedBeforeAnyRequest() {
        for (String n : new String[] {".", ".."}) {
            var b = storage().from(n);
            List<org.junit.jupiter.api.function.Executable> ops = List.of(
                () -> storage().getBucket(n),
                () -> storage().updateBucket(n, Map.of("public", true)),
                () -> storage().deleteBucket(n),
                () -> b.upload("a.txt", "x"),
                () -> b.createSignedUrl("a.txt", 60),
                () -> b.list(),
                () -> b.remove("a.txt"));
            for (var op : ops) {
                KError e = assertThrows(KError.class, op);
                assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
                assertEquals("bucket", e.getContext().get("field"));
                assertEquals("dot_segment", e.getContext().get("reason"));
            }
        }
        assertEquals(0, seen.size());
        storage().getBucket("avatars");
        assertEquals("/@member-portal/storage/buckets/avatars", only().uri());
    }

    @Test
    void missingProjectSlugRefused() {
        Storage s = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build()).storage();
        List<Runnable> calls = new ArrayList<>();
        calls.add(s::listBuckets);
        calls.add(() -> s.getBucket("a"));
        calls.add(() -> s.createBucket("abc"));
        calls.add(() -> s.updateBucket("a", Map.of()));
        calls.add(() -> s.deleteBucket("a"));
        calls.add(() -> s.from("a").upload("p", "x"));
        calls.add(() -> s.from("a").createSignedUrl("p", 60));
        calls.add(() -> s.from("a").list());
        calls.add(() -> s.from("a").remove("p"));
        for (Runnable c : calls) {
            assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, c::run).getCode());
        }
        assertEquals(0, seen.size());
    }

    @Test
    void bucketNotEmpty409PassesThrough() {
        status = 409;
        reply = "{\"code\":\"K9O_CONFLICT\",\"message\":\"bucket_not_empty\",\"context\":{\"reason\":\"bucket_not_empty\"},\"requestId\":\"r1\"}";
        KError e = assertThrows(KError.class, () -> storage().deleteBucket("avatars"));
        assertEquals(409, e.getStatus());
        assertEquals("K9O_CONFLICT", e.getCode());
        assertEquals("bucket_not_empty", e.getMessage());
        assertEquals("r1", e.getRequestId());
    }

    @Test
    void invalidBucketNamePassesThrough() {
        status = 400;
        reply = "{\"code\":\"K9O_INVALID_BUCKET_NAME\",\"message\":\"bad name\",\"context\":{}}";
        KError e = assertThrows(KError.class, () -> storage().createBucket("A"));
        assertEquals("K9O_INVALID_BUCKET_NAME", e.getCode());
        assertEquals(400, e.getStatus());
    }

    @Test
    void uploadErrorNeverContainsObjectBytes() {
        status = 409;
        reply = "{\"code\":\"K9O_ALREADY_EXISTS\",\"message\":\"exists\",\"context\":{}}";
        KError e = assertThrows(KError.class, () -> storage().from("avatars").upload("a.txt", "SECRET-OBJECT-BYTES"));
        assertEquals("K9O_ALREADY_EXISTS", e.getCode());
        assertFalse((e.toString() + e.getContext() + e.getMessage()).contains("SECRET-OBJECT-BYTES"));
        // transport failure path too
        server.stop(0);
        server = null;
        KError net = assertThrows(KError.class, () -> storage().from("avatars").upload("a.txt", "SECRET-OBJECT-BYTES"));
        assertEquals("K9O_UPSTREAM_UNAVAILABLE", net.getCode());
        assertFalse((net.toString() + net.getContext()).contains("SECRET-OBJECT-BYTES"));
    }
}
