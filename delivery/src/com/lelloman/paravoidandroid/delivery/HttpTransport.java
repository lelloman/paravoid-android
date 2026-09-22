package com.lelloman.paravoidandroid.delivery;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Internal byte transport. All signed identities must come from the shared verifier. */
final class HttpTransport {
    static final long MAX_ARCHIVE = 1L << 30;
    static final int MAX_HEAD = 64 * 1024;
    interface Connections { HttpURLConnection open(URL url) throws IOException; }

    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        final int status;
        final long retryAfterSeconds;
        Failure(String code) { this(code, 0, -1); }
        Failure(String code, int status, long retryAfterSeconds) {
            super(code); // Deliberately excludes URLs, credentials and server-controlled text.
            this.code = code; this.status = status; this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    static final class Cancellation {
        private boolean cancelled;
        private HttpURLConnection active;
        synchronized void cancel() {
            cancelled = true;
            if (active != null) active.disconnect();
        }
        synchronized void check() throws Failure {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new Failure("cancelled");
        }
        synchronized void attach(HttpURLConnection connection) throws Failure {
            check(); active = connection;
        }
        synchronized void detach(HttpURLConnection connection) {
            if (active == connection) active = null;
        }
    }

    static final class HeadBytes {
        final boolean notModified;
        final byte[] body;
        final String etag;
        HeadBytes(boolean notModified, byte[] body, String etag) {
            this.notModified = notModified; this.body = body; this.etag = etag;
        }
    }

    private final URI base;
    private final Connections connections;
    private final String bearer;

    // Debug HTTP is an explicit internal test/development option. Release wiring must forbid it.
    HttpTransport(URI base, boolean debugHttp, String bearer, Connections connections) {
        String scheme = base.getScheme();
        if (!("https".equals(scheme) || (debugHttp && "http".equals(scheme)))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || !base.getPath().endsWith("/") || !base.normalize().equals(base)) {
            throw new IllegalArgumentException("invalid distribution base");
        }
        if (bearer != null && !bearer.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("invalid credential");
        }
        this.base = base; this.bearer = bearer; this.connections = connections;
    }

    static HttpTransport platform(URI base, boolean debugHttp, String bearer) {
        return new HttpTransport(base, debugHttp, bearer,
                url -> (HttpURLConnection) url.openConnection());
    }

    URI headUri(String app, String contract, String channel, int sdk, List<String> abis, int runtime) {
        app(app);
        if (!contract.matches("[0-9a-f]{64}") || !channel.matches("[A-Za-z0-9_-]{1,64}")
                || sdk < 1 || runtime < 1 || new HashSet<>(abis).size() != abis.size()) {
            throw new IllegalArgumentException("invalid request scope");
        }
        for (String abi : abis) if (!Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(abi))
            throw new IllegalArgumentException("invalid ABI");
        return base.resolve("v1/apps/" + app + "/head?contract=" + contract + "&channel=" + channel
                + "&sdk=" + sdk + "&abis=" + encode(String.join(",", abis))
                + "&runtime=" + runtime + "&format=1&protocol=1");
    }

    URI archiveUri(String app, String release) {
        app(app);
        if (!release.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid release ID");
        return base.resolve("v1/apps/" + app + "/releases/" + release + "/payload.vpk");
    }

    // A 304 is only an HTTP result: integration must enforce verified, fresh, identical-scope cache reuse.
    HeadBytes head(URI uri, String verifiedCacheEtag, Cancellation cancel) throws IOException {
        if (verifiedCacheEtag != null && !verifiedCacheEtag.matches("\"[0-9a-f]{64}\""))
            throw new IllegalArgumentException("invalid cache validator");
        HttpURLConnection c = open(uri, cancel);
        try {
            c.setRequestProperty("Accept", "application/json");
            if (verifiedCacheEtag != null) c.setRequestProperty("If-None-Match", verifiedCacheEtag);
            int status = c.getResponseCode();
            cancel.check(); encoding(c);
            if (status == 304) {
                if (verifiedCacheEtag == null || !verifiedCacheEtag.equals(header(c, "ETag")))
                    throw new Failure("invalid-304");
                return new HeadBytes(true, null, verifiedCacheEtag);
            }
            if (status != 200) throw status(c, status);
            mime(c, "application/json");
            long length = c.getContentLengthLong();
            if (length > MAX_HEAD) throw new Failure("head-too-large");
            byte[] body;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                copy(in, out, MAX_HEAD, cancel);
                body = out.toByteArray();
            }
            if (length >= 0 && length != body.length) throw new Failure("truncated-head");
            String etag = quoted(hash(body));
            if (!etag.equals(header(c, "ETag"))) throw new Failure("invalid-etag");
            return new HeadBytes(false, body, etag);
        } finally { cancel.detach(c); c.disconnect(); }
    }

    // Caller supplies a private, unshared directory and scope token from verified policy/grant identity.
    // No grant bytes are stored. Hashing a scope is cache partitioning, never replay authority.
    Path partial(Path directory, String scope, URI uri, long size, String hash) throws IOException {
        validateArchive(size, hash);
        String id = hash((scope + "\n" + uri.toASCIIString() + "\n" + size + "\n" + hash)
                .getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(directory);
        return directory.resolve(id + ".part");
    }

    Path download(URI uri, Path partial, long size, String sha256, Cancellation cancel) throws IOException {
        validateArchive(size, sha256);
        String etag = quoted(sha256);
        boolean retried416 = false;
        for (;;) {
            cancel.check();
            if (Files.isSymbolicLink(partial)) throw new Failure("unsafe-partial");
            long offset = Files.exists(partial) ? Files.size(partial) : 0;
            if (offset == size && sha256.equals(hash(partial, cancel))) return partial;
            if (offset >= size) { Files.delete(partial); offset = 0; }
            HttpURLConnection c = open(uri, cancel);
            try {
                c.setRequestProperty("Accept", "application/vnd.paravoid.vpk");
                if (offset > 0) {
                    c.setRequestProperty("Range", "bytes=" + offset + "-");
                    c.setRequestProperty("If-Range", etag);
                }
                int status = c.getResponseCode();
                cancel.check(); encoding(c);
                if (status == 416) {
                    Files.deleteIfExists(partial);
                    if (retried416) throw new Failure("repeated-416", 416, -1);
                    retried416 = true;
                    continue;
                }
                if (status != 200 && status != 206) throw status(c, status);
                mime(c, "application/vnd.paravoid.vpk");
                if (!etag.equals(header(c, "ETag"))) throw new Failure("invalid-etag");
                long start = status == 200 ? 0 : offset;
                if (status == 206 && (offset == 0 || !Objects.equals(header(c, "Content-Range"),
                        "bytes " + offset + "-" + (size - 1) + "/" + size)))
                    throw new Failure("invalid-range");
                if (c.getContentLengthLong() != size - start) throw new Failure("invalid-length");
                if (status == 200 && header(c, "Content-Range") != null) throw new Failure("invalid-range");
                try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(partial,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        start == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
                    long copied = copy(in, out, size - start, cancel);
                    if (copied != size - start) throw new Failure("truncated-archive");
                } catch (Failure failure) {
                    if (failure.code.equals("body-too-large")) Files.deleteIfExists(partial);
                    throw failure;
                }
                if (!sha256.equals(hash(partial, cancel))) {
                    Files.deleteIfExists(partial);
                    throw new Failure("archive-hash-mismatch");
                }
                return partial;
            } finally { cancel.detach(c); c.disconnect(); }
        }
    }

    private HttpURLConnection open(URI uri, Cancellation cancel) throws IOException {
        cancel.check();
        if (!Objects.equals(uri.getScheme(), base.getScheme()) || !Objects.equals(uri.getHost(), base.getHost())
                || port(uri) != port(base) || uri.getUserInfo() != null || uri.getFragment() != null
                || !uri.normalize().equals(uri) || !uri.getRawPath().startsWith(base.getRawPath())
                || uri.getRawPath().contains("%") || uri.getRawPath().contains("\\"))
            throw new Failure("outside-distribution-origin");
        HttpURLConnection c = connections.open(uri.toURL());
        c.setInstanceFollowRedirects(false); c.setUseCaches(false);
        c.setConnectTimeout(15_000); c.setReadTimeout(30_000);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        cancel.attach(c);
        return c;
    }

    private static long copy(InputStream in, OutputStream out, long limit, Cancellation cancel) throws IOException {
        byte[] buffer = new byte[32 * 1024]; long total = 0;
        for (;;) {
            cancel.check();
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - total + 1));
            cancel.check();
            if (n == -1) return total;
            total += n;
            if (total > limit) throw new Failure("body-too-large");
            out.write(buffer, 0, n);
        }
    }

    private static String header(HttpURLConnection c, String name) throws Failure {
        String result = null;
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) {
                if (result != null || e.getValue().size() != 1) throw new Failure("duplicate-header");
                result = e.getValue().get(0);
            }
        }
        return result;
    }
    private static void encoding(HttpURLConnection c) throws Failure {
        String value = header(c, "Content-Encoding");
        if (value != null && !value.equalsIgnoreCase("identity")) throw new Failure("content-encoding");
    }
    private static void mime(HttpURLConnection c, String expected) throws Failure {
        String value = header(c, "Content-Type");
        if (value == null || !value.split(";", 2)[0].trim().equalsIgnoreCase(expected))
            throw new Failure("content-type");
        header(c, "Content-Length"); // reject ambiguous duplicate framing
    }
    private static Failure status(HttpURLConnection c, int status) throws Failure {
        long seconds = -1;
        String retry = header(c, "Retry-After");
        if (retry != null && retry.matches("[0-9]{1,10}")) seconds = Math.min(3600, Long.parseLong(retry));
        return new Failure(status >= 300 && status < 400 ? "redirect" : "http-status", status, seconds);
    }
    private static void validateArchive(long size, String hash) {
        if (size <= 0 || size > MAX_ARCHIVE || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid expected archive");
    }
    private static void app(String app) {
        if (!app.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("invalid application ID");
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String quoted(String value) { return "\"" + value + "\""; }
    static String hash(byte[] bytes) { return hex(digest().digest(bytes)); }
    private static String hash(Path file, Cancellation cancel) throws IOException {
        MessageDigest digest = digest(); byte[] bytes = new byte[32 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n; while ((n = in.read(bytes)) != -1) { cancel.check(); digest.update(bytes, 0, n); }
        }
        return hex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
