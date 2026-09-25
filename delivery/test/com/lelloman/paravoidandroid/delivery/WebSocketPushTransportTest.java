package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.updates.*;
import java.io.*;
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
    public static void main(String[] args) throws Exception {
        List<byte[]> events=new ArrayList<>(); ByteArrayOutputStream replies=new ByteArrayOutputStream();
        receive(new byte[]{1,1,'a',(byte)137,1,'x',(byte)128,1,'b',(byte)136,0},events,replies);
        check(events.size()==1 && Arrays.equals(events.get(0),new byte[]{'a','b'}));
        byte[] pong=replies.toByteArray(); check((pong[0]&255)==138 && (pong[1]&128)!=0);
        for(byte[] invalid:new byte[][]{{(byte)129,(byte)128},{(byte)130,0},{(byte)128,0},{(byte)129,126,32,0},{(byte)129,1,(byte)255},{(byte)137,126,0,(byte)126}}) {
            try { receive(invalid,new ArrayList<>(),new ByteArrayOutputStream()); throw new AssertionError("Accepted invalid frame"); }
            catch(IOException expected) { }
        }
        System.out.println("PASS WebSocket framing: fragmentation, interleaved ping, masked pong, invalid opcode/size/UTF-8 rejection");
    }
}
