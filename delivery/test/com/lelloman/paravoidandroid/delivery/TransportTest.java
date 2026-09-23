package com.lelloman.paravoidandroid.delivery;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public final class TransportTest {
    static final byte[] ARCHIVE = "opaque archive fixture: never executable".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static final String HASH = HttpTransport.hash(ARCHIVE);
    static final String ETAG = "\"" + HASH + "\"";
    static final URI BASE = URI.create("https://updates.test/prefix/");
    static final URI URL = BASE.resolve("v1/apps/example.app/releases/r1/payload.vpk");
    static int assertions;

    static final class Fake extends HttpURLConnection {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        byte[] body;
        volatile boolean disconnected;
        Runnable onDisconnect;
        InputStream stream;
        Fake(int status, byte[] body) throws Exception {
            super(URL.toURL()); this.responseCode = status; this.body = body;
            put("Content-Length", "" + body.length);
            put("Content-Type", "application/vnd.paravoid.vpk"); put("ETag", ETAG);
        }
        Fake put(String name, String value) { headers.put(name, Collections.singletonList(value)); return this; }
        @Override public int getResponseCode() { return responseCode; }
        @Override public Map<String, List<String>> getHeaderFields() { return headers; }
        @Override public long getContentLengthLong() { return Long.parseLong(headers.get("Content-Length").get(0)); }
        @Override public InputStream getInputStream() { return stream == null ? new ByteArrayInputStream(body) : stream; }
        @Override public void disconnect() { disconnected = true; if (onDisconnect != null) onDisconnect.run(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
    static final class Fixture implements AutoCloseable {
        final Queue<Fake> responses = new ArrayDeque<>();
        final Path dir = Files.createTempDirectory("paravoid-transport-");
        final Path partial = dir.resolve("test.part");
        final HttpTransport transport;
        int requests;
        Fixture() throws IOException { this(null); }
        Fixture(String key) throws IOException {
            transport = new HttpTransport(BASE, false, key, url -> {
                requests++;
                Fake next = responses.poll();
                if (next == null) throw new AssertionError("unexpected request");
                return next;
            });
        }
        Path download() throws IOException { return transport.download(URL, partial, ARCHIVE.length, HASH, new HttpTransport.Cancellation()); }
        @Override public void close() throws IOException {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
            }
        }
    }
    interface Action { void run() throws Exception; }
    static void check(boolean value) { assertions++; if (!value) throw new AssertionError("assertion " + assertions); }
    static void fails(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (HttpTransport.Failure failure) { check(code.equals(failure.code)); }
    }
    public static void main(String[] args) throws Exception {
        publicAndKey(); resume(); hostile(); heads(); cancellation();
        System.out.println("TransportTest: " + assertions + " assertions passed");
    }
    static void publicAndKey() throws Exception {
        for (String key : new String[] {null, "A".repeat(43)}) try (Fixture f = new Fixture(key)) {
            Fake response = new Fake(200, ARCHIVE); f.responses.add(response);
            check(Arrays.equals(ARCHIVE, Files.readAllBytes(f.download())));
            check(Objects.equals(response.getRequestProperty("Authorization"), key == null ? null : "Bearer " + key));
            check("identity".equals(response.getRequestProperty("Accept-Encoding")));
            check(!response.getInstanceFollowRedirects()); check(!response.getUseCaches());
            check(response.getConnectTimeout() == 15000 && response.getReadTimeout() == 30000);
            check(response.disconnected);
            f.download(); check(f.requests == 1); // Complete identical partial needs no HTTP.
            check(!f.transport.partial(f.dir, "grant-a", URL, ARCHIVE.length, HASH)
                    .equals(f.transport.partial(f.dir, "grant-b", URL, ARCHIVE.length, HASH)));
        }
    }
    static void resume() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.write(f.partial, Arrays.copyOf(ARCHIVE, 5));
            Fake range = new Fake(206, Arrays.copyOfRange(ARCHIVE, 5, ARCHIVE.length))
                    .put("Content-Range", "bytes 5-" + (ARCHIVE.length - 1) + "/" + ARCHIVE.length);
            f.responses.add(range); f.download();
            check(Arrays.equals(ARCHIVE, Files.readAllBytes(f.partial)));
            check("bytes=5-".equals(range.getRequestProperty("Range")));
            check(ETAG.equals(range.getRequestProperty("If-Range")));
        }
        try (Fixture f = new Fixture()) {
            Files.write(f.partial, "bad partial".getBytes());
            f.responses.add(new Fake(200, ARCHIVE)); f.download();
            check(Arrays.equals(ARCHIVE, Files.readAllBytes(f.partial)));
        }
        try (Fixture f = new Fixture()) {
            Files.write(f.partial, Arrays.copyOf(ARCHIVE, 5));
            f.responses.add(new Fake(416, new byte[0]));
            Fake full = new Fake(200, ARCHIVE); f.responses.add(full); f.download();
            check(full.getRequestProperty("Range") == null); check(f.requests == 2);
        }
        try (Fixture f = new Fixture()) {
            f.responses.add(new Fake(416, new byte[0])); f.responses.add(new Fake(416, new byte[0]));
            fails("repeated-416", f::download); check(f.requests == 2);
        }
        try (Fixture f = new Fixture()) {
            f.responses.add(new Fake(200, Arrays.copyOf(ARCHIVE, 5)).put("Content-Length", "" + ARCHIVE.length));
            fails("truncated-archive", f::download); check(Files.size(f.partial) == 5);
            f.responses.add(new Fake(206, Arrays.copyOfRange(ARCHIVE, 5, ARCHIVE.length))
                    .put("Content-Range", "bytes 5-" + (ARCHIVE.length - 1) + "/" + ARCHIVE.length));
            f.download(); check(Arrays.equals(ARCHIVE, Files.readAllBytes(f.partial)));
        }
    }
    static void hostile() throws Exception {
        for (int status : new int[] {301, 302, 307, 308, 401, 403, 404, 429, 500, 503}) try (Fixture f = new Fixture()) {
            f.responses.add(new Fake(status, new byte[0]));
            fails(status < 400 ? "redirect" : "http-status", f::download);
            check(f.requests == 1); check(!Files.exists(f.partial));
        }
        for (String uri : new String[] {"http://updates.test/prefix/x", "https://evil.test/prefix/x",
                "https://updates.test/outside/x", "https://updates.test/prefix/../x",
                "https://updates.test/prefix/%2e%2e/x", "https://user@updates.test/prefix/x",
                "https://updates.test:444/prefix/x", "https://updates.test/prefix/x#fragment"}) try (Fixture f = new Fixture()) {
            fails("outside-distribution-origin", () -> f.transport.head(URI.create(uri), null, new HttpTransport.Cancellation()));
            check(f.requests == 0);
        }
        for (String[] pair : new String[][] {{"Content-Encoding", "gzip", "content-encoding"},
                {"Content-Type", "text/html", "content-type"}, {"ETag", "W/" + ETAG, "invalid-etag"},
                {"Content-Length", "1", "invalid-length"}, {"Content-Range", "bytes 0-1/2", "invalid-range"}})
            try (Fixture f = new Fixture()) {
                f.responses.add(new Fake(200, ARCHIVE).put(pair[0], pair[1])); fails(pair[2], f::download);
            }
        for (String value : new String[] {"bytes 4-38/39", "bytes 5-7/39", "bytes 5-38/*", "bytes 5-38/40"})
            try (Fixture f = new Fixture()) {
                Files.write(f.partial, Arrays.copyOf(ARCHIVE, 5));
                f.responses.add(new Fake(206, Arrays.copyOfRange(ARCHIVE, 5, ARCHIVE.length)).put("Content-Range", value));
                fails("invalid-range", f::download); check(Files.size(f.partial) == 5);
            }
        try (Fixture f = new Fixture()) {
            byte[] wrong = ARCHIVE.clone(); wrong[0]++;
            f.responses.add(new Fake(200, wrong)); fails("archive-hash-mismatch", f::download);
            check(!Files.exists(f.partial));
        }
        try (Fixture f = new Fixture()) {
            f.responses.add(new Fake(200, Arrays.copyOf(ARCHIVE, ARCHIVE.length + 1)).put("Content-Length", "" + ARCHIVE.length));
            fails("body-too-large", f::download); check(!Files.exists(f.partial));
        }
        try (Fixture f = new Fixture()) {
            Fake response = new Fake(200, ARCHIVE);
            response.headers.put("etag", Collections.singletonList(ETAG)); f.responses.add(response);
            fails("duplicate-header", f::download);
        }
    }
    static void heads() throws Exception {
        byte[] bytes = "opaque signed head placeholder".getBytes();
        String etag = "\"" + HttpTransport.hash(bytes) + "\"";
        try (Fixture f = new Fixture()) {
            URI uri = f.transport.headUri("example.app", "a".repeat(64), "stable", 30, Arrays.asList("x86_64", "x86"), 1);
            check(uri.toString().contains("abis=x86_64%2Cx86&runtime=1&format=1&protocol=1"));
            f.responses.add(new Fake(200, bytes).put("Content-Type", "application/json").put("ETag", etag));
            HttpTransport.HeadBytes result = f.transport.head(uri, null, new HttpTransport.Cancellation());
            check(!result.notModified && Arrays.equals(result.body, bytes));
            Fake cached = new Fake(304, new byte[0]).put("ETag", etag); f.responses.add(cached);
            result = f.transport.head(uri, etag, new HttpTransport.Cancellation());
            check(result.notModified && result.body == null);
            check(etag.equals(cached.getRequestProperty("If-None-Match")));
            f.responses.add(new Fake(304, new byte[0]).put("ETag", etag));
            fails("invalid-304", () -> f.transport.head(uri, null, new HttpTransport.Cancellation()));
        }
        try (Fixture f = new Fixture()) {
            f.responses.add(new Fake(200, new byte[65537]).put("Content-Type", "application/json"));
            fails("head-too-large", () -> f.transport.head(URL, null, new HttpTransport.Cancellation()));
        }
        try (Fixture f = new Fixture()) {
            f.responses.add(new Fake(200, bytes).put("Content-Type", "application/json"));
            fails("invalid-etag", () -> f.transport.head(URL, null, new HttpTransport.Cancellation()));
        }
    }
    static void cancellation() throws Exception {
        try (Fixture f = new Fixture()) {
            Fake response = new Fake(200, ARCHIVE);
            response.stream = new InputStream() {
                int offset;
                public int read() throws IOException {
                    if (offset == 5) throw new IOException("untrusted server text or credential must not escape");
                    return ARCHIVE[offset++];
                }
            };
            f.responses.add(response);
            fails("network-io", f::download); check(Files.size(f.partial) == 5);
        }
        try (Fixture f = new Fixture()) {
            HttpTransport.Cancellation cancel = new HttpTransport.Cancellation(); cancel.cancel();
            fails("cancelled", () -> f.transport.download(URL, f.partial, ARCHIVE.length, HASH, cancel));
            check(f.requests == 0);
        }
        try (Fixture f = new Fixture()) {
            HttpTransport.Cancellation cancel = new HttpTransport.Cancellation();
            Fake response = new Fake(200, ARCHIVE);
            response.stream = new ByteArrayInputStream(ARCHIVE) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    int n = super.read(b, off, len); cancel.cancel(); return n;
                }
            };
            f.responses.add(response);
            fails("cancelled", () -> f.transport.download(URL, f.partial, ARCHIVE.length, HASH, cancel));
            check(response.disconnected); check(Files.size(f.partial) == 0);
        }
    }
}
