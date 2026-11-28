package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.updates.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.check;

public final class WebSocketPushTransportTest {
    private static final Cancellation LIVE=new Cancellation() {
        public void check() {}
        public AutoCloseable onCancel(Runnable close) { return ()->{}; }
    };
    static void receive(byte[] frames,List<byte[]> events,ByteArrayOutputStream replies) throws Exception {
        WebSocketPushTransport.receive(new ByteArrayInputStream(frames),replies,new PushTransport.Listener() {
            public void connected() {}
            public void received(byte[] event) { events.add(event); }
        },LIVE);
    }
    private static final String KEY="dGhlIHNhbXBsZSBub25jZQ==";
    private static String accept() throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((KEY+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
    }
    private static String head(String status,String... headers) {
        StringBuilder head=new StringBuilder(status).append("\r\n");
        for(String header:headers) head.append(header).append("\r\n");
        return head.append("\r\n").toString();
    }
    private static void accepted(String head) throws Exception { WebSocketPushTransport.validateResponse(head,KEY); }
    private static void rejected(String head,String reason) throws Exception {
        try { WebSocketPushTransport.validateResponse(head,KEY); }
        catch(IOException expected) { check(expected.getMessage().equals(reason)); return; }
        throw new AssertionError("Accepted invalid handshake: "+reason);
    }
    static void handshakes() throws Exception {
        String ok="HTTP/1.1 101 Switching Protocols",acc="Sec-WebSocket-Accept: "+accept(),up="Upgrade: websocket",conn="Connection: Upgrade",proto="Sec-WebSocket-Protocol: paravoid.updates.v1";
        accepted(head(ok,acc,up,conn,proto));
        // Two chained reverse proxies (VPS Caddy -> home Caddy) each add their own Server, Alt-Svc and Via.
        accepted(head(ok,"Alt-Svc: h3=\":443\"; ma=2592000",conn,acc,proto,"Server: Caddy","Alt-Svc: h3=\":443\"; ma=2592000",
            "Server: Caddy",up,"Via: 1.1 Caddy","Via: 1.1 Caddy","Date: Sat, 28 Nov 2026 10:00:00 GMT","Date: Sat, 28 Nov 2026 10:00:00 GMT",
            "Set-Cookie: a=1","Set-Cookie: b=2"));
        accepted(head("HTTP/1.0 101",acc,"upgrade: WebSocket","connection: keep-alive, Upgrade",proto));
        accepted(head(ok,acc,up,"Connection: keep-alive","Connection: upgrade",proto));
        rejected(head(ok,acc,acc,up,conn,proto),"Duplicate push header sec-websocket-accept");
        rejected(head(ok,acc,"Sec-WebSocket-Accept: AAAAAAAAAAAAAAAAAAAAAAAAAAA=",up,conn,proto),"Duplicate push header sec-websocket-accept");
        rejected(head(ok,acc,up,"upgrade: websocket",conn,proto),"Duplicate push header upgrade");
        rejected(head(ok,acc,up,"Upgrade: h2c",conn,proto),"Duplicate push header upgrade");
        rejected(head(ok,acc,up,conn,proto,proto),"Duplicate push header sec-websocket-protocol");
        rejected(head(ok,acc,up,conn,proto,"Sec-WebSocket-Protocol: other"),"Duplicate push header sec-websocket-protocol");
        rejected(head(ok,acc,up,conn,proto,"Sec-WebSocket-Extensions: a","Sec-WebSocket-Extensions: a"),"Duplicate push header sec-websocket-extensions");
        rejected(head(ok,acc,up,conn,proto,"Sec-WebSocket-Extensions: permessage-deflate"),"Invalid push handshake protocol");
        rejected(head(ok,"Sec-WebSocket-Accept: AAAAAAAAAAAAAAAAAAAAAAAAAAA=",up,conn,proto),"Invalid push handshake accept");
        rejected(head(ok,up,conn,proto),"Invalid push handshake accept");
        rejected(head(ok,acc,"Upgrade: h2c",conn,proto),"Invalid push handshake upgrade");
        rejected(head(ok,acc,conn,proto),"Invalid push handshake upgrade");
        rejected(head(ok,acc,up,"Connection: keep-alive",proto),"Invalid push handshake upgrade");
        rejected(head(ok,acc,up,proto),"Invalid push handshake upgrade");
        rejected(head(ok,acc,up,conn),"Invalid push handshake protocol");
        rejected(head(ok,acc,up,conn,"Sec-WebSocket-Protocol: other"),"Invalid push handshake protocol");
        rejected(head(ok,acc,up,conn,proto,"no colon"),"Invalid push handshake");
        rejected(head("HTTP/1.1 403 Forbidden",acc,up,conn,proto),"Push handshake rejected (HTTP 403)");
        rejected(head("HTTP/2 101",acc,up,conn,proto),"Invalid push handshake status");
    }
    public static void main(String[] args) throws Exception {
        List<byte[]> events=new ArrayList<>(); ByteArrayOutputStream replies=new ByteArrayOutputStream();
        receive(new byte[]{1,1,'a',(byte)137,1,'x',(byte)128,1,'b',(byte)136,0},events,replies);
        check(events.size()==1 && Arrays.equals(events.get(0),new byte[]{'a','b'}));
        byte[] pong=replies.toByteArray(); check((pong[0]&255)==138 && (pong[1]&128)!=0);
        for(byte[] invalid:new byte[][]{{(byte)129,(byte)128},{(byte)130,0},{(byte)128,0},{(byte)129,126,32,0},{(byte)129,1,(byte)255},{(byte)137,126,0,(byte)126}}) {
            try { receive(invalid,new ArrayList<>(),new ByteArrayOutputStream()); throw new AssertionError("Accepted invalid frame"); }
            catch(IOException expected) { }
        }
        handshakes();
        System.out.println("PASS WebSocket framing: fragmentation, interleaved ping, masked pong, invalid opcode/size/UTF-8 rejection; handshake ignores repeated proxy headers, rejects duplicate/conflicting validated headers");
    }
}
