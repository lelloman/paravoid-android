package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.RequestScope;
import com.lelloman.paravoidandroid.updates.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Small RFC 6455 client for bounded Paravoid events. No extensions, compression or redirects. */
public final class WebSocketPushTransport implements PushTransport {
    private static final SecureRandom RANDOM=new SecureRandom();
    @Override public void run(PushRequest request,Listener listener,Cancellation cancellation) throws Exception {
        URI uri=URI.create(request.endpoint);
        if(!Arrays.asList("ws","wss").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IOException("Invalid push endpoint");
        boolean tls="wss".equals(uri.getScheme());
        Socket tcp=new Socket();
        try(AutoCloseable registration=cancellation.onCancel(()->close(tcp))) {
            cancellation.check();
            tcp.connect(new InetSocketAddress(uri.getHost(),uri.getPort()<0 ? (tls ? 443 : 80) : uri.getPort()),15000);
            tcp.setSoTimeout(15000);
            Socket socket=tcp;
            if(tls) {
                SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(tcp,uri.getHost(),uri.getPort()<0 ? 443 : uri.getPort(),true);
                SSLParameters params=ssl.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); ssl.setSSLParameters(params);
                ssl.startHandshake(); socket=ssl;
            }
            try(Socket connected=socket) {
                InputStream input=new BufferedInputStream(connected.getInputStream());
                OutputStream output=connected.getOutputStream();
                handshake(uri,request.headers,input,output);
                RequestScope scope=new RequestScope(request.applicationId,request.shellContractId,request.channel,30,Collections.emptyList(),1);
                frame(output,1,UpdateEventCodec.subscription(scope));
                listener.connected();
                connected.setSoTimeout(60000);
                ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"paravoid-push-heartbeat"); t.setDaemon(true); return t; });
                heartbeat.scheduleWithFixedDelay(()-> { try { frame(output,9,new byte[0]); } catch(IOException failure) { close(connected); } },20,20,TimeUnit.SECONDS);
                try { receive(input,output,listener,cancellation); } finally { heartbeat.shutdownNow(); }
            }
        } finally { close(tcp); }
    }
    private static void handshake(URI uri,Map<String,String> headers,InputStream input,OutputStream output) throws Exception {
        byte[] nonce=new byte[16]; RANDOM.nextBytes(nonce); String key=Base64.getEncoder().encodeToString(nonce);
        String path=uri.getRawPath().isEmpty() ? "/" : uri.getRawPath(); if(uri.getRawQuery()!=null) path+="?"+uri.getRawQuery();
        StringBuilder request=new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(uri.getRawAuthority())
            .append("\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ")
            .append(key).append("\r\nSec-WebSocket-Protocol: paravoid.updates.v1\r\n");
        for(Map.Entry<String,String> header:headers.entrySet()) {
            String name=header.getKey(),value=header.getValue();
            if(!name.matches("[A-Za-z0-9-]{1,64}") || value==null || value.length()>4096 || !value.matches("[\\x20-\\x7e]*")
                || Arrays.asList("host","connection","upgrade","content-length","transfer-encoding").contains(name.toLowerCase(Locale.ROOT))
                || name.toLowerCase(Locale.ROOT).startsWith("sec-websocket-")) throw new IOException("Invalid push authentication headers");
            request.append(name).append(": ").append(value).append("\r\n");
        }
        request.append("\r\n"); if(request.length()>8192) throw new IOException("Push headers too large");
        output.write(request.toString().getBytes(StandardCharsets.US_ASCII)); output.flush();
        ByteArrayOutputStream response=new ByteArrayOutputStream(); int previous=0;
        while(response.size()<8192) {
            int b=input.read(); if(b<0) throw new EOFException("Push handshake truncated"); response.write(b);
            previous=(previous<<8)|b; if(previous==0x0d0a0d0a) break;
        }
        if(previous!=0x0d0a0d0a) throw new IOException("Push headers too large");
        String[] lines=response.toString("US-ASCII").split("\r\n");
        if(!lines[0].matches("HTTP/1\\.[01] 101(?: .*)?")) throw new IOException("Push handshake rejected");
        Map<String,String> received=new HashMap<>();
        for(int i=1;i<lines.length;i++) {
            int colon=lines[i].indexOf(':'); if(colon<1) throw new IOException("Invalid push handshake");
            String name=lines[i].substring(0,colon).toLowerCase(Locale.ROOT);
            if(received.put(name,lines[i].substring(colon+1).trim())!=null) throw new IOException("Duplicate push header");
        }
        String expected=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        if(!expected.equals(received.get("sec-websocket-accept")) || !"websocket".equalsIgnoreCase(received.get("upgrade"))
            || !Arrays.asList(received.getOrDefault("connection","").toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("upgrade")
            || !"paravoid.updates.v1".equals(received.get("sec-websocket-protocol")) || received.containsKey("sec-websocket-extensions"))
            throw new IOException("Invalid push handshake");
    }
    static void receive(InputStream input,OutputStream output,Listener listener,Cancellation cancellation) throws Exception {
        ByteArrayOutputStream message=null;
        for(;;) {
            cancellation.check(); int first=input.read(),second=input.read(); if(first<0 || second<0) throw new EOFException("Push disconnected");
            boolean fin=(first&128)!=0; int opcode=first&15; long size=second&127;
            if((first&112)!=0 || (second&128)!=0) throw new IOException("Invalid push frame");
            if(size==126) { size=((long)read(input)<<8)|read(input); if(size<126) throw new IOException("Invalid push length"); }
            else if(size==127) { size=0; for(int i=0;i<8;i++) { int b=read(input); if(i==0 && b!=0) throw new IOException("Push frame too large"); size=(size<<8)|b; } if(size<65536) throw new IOException("Invalid push length"); }
            if(size>UpdateEventCodec.MAX_BYTES || opcode>=8 && (!fin || size>125)) throw new IOException("Push frame too large");
            byte[] bytes=new byte[(int)size]; new DataInputStream(input).readFully(bytes);
            if(opcode==8) { if(size==1) throw new IOException("Invalid push close"); frame(output,8,bytes); return; }
            if(opcode==9) { frame(output,10,bytes); continue; }
            if(opcode==10) continue;
            if(opcode==1) { if(message!=null) throw new IOException("Unexpected push text frame"); message=new ByteArrayOutputStream(); }
            else if(opcode!=0 || message==null) throw new IOException("Unsupported push frame");
            if(message.size()+bytes.length>UpdateEventCodec.MAX_BYTES) throw new IOException("Push event too large");
            message.write(bytes);
            if(fin) {
                byte[] event=message.toByteArray(); message=null;
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(event));
                listener.received(event);
            }
        }
    }
    private static int read(InputStream input) throws IOException { int b=input.read(); if(b<0) throw new EOFException(); return b; }
    static void frame(OutputStream output,int opcode,byte[] bytes) throws IOException {
        if(bytes.length>UpdateEventCodec.MAX_BYTES) throw new IOException("Push frame too large");
        synchronized(output) {
            output.write(128|opcode);
            if(bytes.length<126) output.write(128|bytes.length);
            else { output.write(128|126); output.write(bytes.length>>8); output.write(bytes.length); }
            byte[] mask=new byte[4]; RANDOM.nextBytes(mask); output.write(mask);
            for(int i=0;i<bytes.length;i++) output.write(bytes[i]^mask[i%4]); output.flush();
        }
    }
    private static void close(Socket socket) { try { socket.close(); } catch(IOException ignored) { } }
}
