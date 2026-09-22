package com.lelloman.paravoidandroid.delivery;

import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.check;

/** Actual JDK HTTP client against the independently implemented Python server. */
public final class ReferenceServerTest {
    public static void main(String[] args) throws Exception {
        URI base = URI.create(args[0]);
        HttpTransport transport = HttpTransport.platform(base, true, args[1].equals("public") ? null : "A".repeat(43));
        URI head = transport.headUri("example.app", "a".repeat(64), "stable", 30, Arrays.asList("x86_64", "x86"), 1);
        HttpTransport.HeadBytes result = transport.head(head, null, new HttpTransport.Cancellation());
        check(Arrays.equals(result.body, "opaque signed head fixture".getBytes()));
        check(transport.head(head, result.etag, new HttpTransport.Cancellation()).notModified);
        byte[] expected = "opaque transport bytes; not a signed VPK".getBytes();
        Path file = Files.createTempFile("paravoid-reference-", ".part");
        try {
            Files.write(file, Arrays.copyOf(expected, 7));
            transport.download(transport.archiveUri("example.app", "r1"), file, expected.length,
                    HttpTransport.hash(expected), new HttpTransport.Cancellation());
            check(Arrays.equals(expected, Files.readAllBytes(file)));
        } finally { Files.deleteIfExists(file); }
        System.out.println("Java/Python HTTP interop: " + TransportTest.assertions + " assertions passed");
    }
}
