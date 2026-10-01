package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.*;

/** Opaque test byte fixtures; production staging still authenticates the reconstructed complete VPK. */
public final class DeltaDeliveryTest {
    static final byte[] OLD = new byte[32768], NEW = new byte[32768];
    static { Arrays.fill(OLD,(byte)1); Arrays.fill(NEW,(byte)2); }
    static byte[] deflate(byte[] bytes) throws Exception {
        Deflater deflater = new Deflater(9,true);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            try (DeflaterOutputStream stream = new DeflaterOutputStream(out,deflater)) { stream.write(bytes); }
            return out.toByteArray();
        } finally { deflater.end(); }
    }
    static byte[] patch() throws Exception {
        byte[] control=deflate(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(NEW.length).putLong(0).array());
        byte[] diff=deflate(new byte[0]), extra=deflate(NEW);
        ByteArrayOutputStream out=new ByteArrayOutputStream(); out.write("DVPKD001".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putLong(control.length).putLong(diff.length).putLong(NEW.length).array());
        out.write(control);out.write(diff);out.write(extra);return out.toByteArray();
    }
    static final class Life implements Lifecycle {
        final ExpectedArchive base=new ExpectedArchive("base",1,"b".repeat(64),HttpTransport.hash(OLD),OLD.length);
        ExpectedArchive target;
        boolean missing, corrupt, reserved, closedBase;
        int stages, opens, closes;
        public void setCredentialScope(CredentialScope scope) { }
        public AdmissionResult observeHead(VerifiedHead head, CredentialScope credential) { target=head.release;return new AdmissionResult(head.status,new AdmissionId("delta"),target); }
        public ExpectedArchive deltaBaseIdentity() { return base; }
        public DownloadReservation reserveDownload(AdmissionId id) {
            check(!reserved);reserved=true;
            return new DownloadReservation() {
                public DeltaBase openDeltaBase(String hash) {
                    opens++;check(reserved);check(hash.equals(base.archiveSha256));if(missing)return null;
                    return new DeltaBase() {
                        public ExpectedArchive identity(){return base;}
                        public int read(long position,byte[] bytes,int offset,int length)throws IOException {
                            if(position>=OLD.length)return -1;int count=(int)Math.min(length,OLD.length-position);
                            System.arraycopy(OLD,(int)position,bytes,offset,count);if(corrupt&&position==0)bytes[offset]^=1;return count;
                        }
                        public void close(){closedBase=true;}
                    };
                }
                public StageResult stage(File archive)throws ContractException{return stageDownloaded(archive,id);}
                public void close(){reserved=false;closes++;}
            };
        }
        public StageResult stageDownloaded(File archive,AdmissionId id) {
            check(reserved);try {check(Arrays.equals(NEW,Files.readAllBytes(archive.toPath())));}catch(IOException failure){throw new AssertionError(failure);}
            stages++;return new StageResult(StageStatus.PENDING,target);
        }
        public StageResult stageEmbedded(File archive){throw new AssertionError();}
        public LifecycleSnapshot snapshot(){throw new AssertionError("Delta transfer must not select code");}
        public GenerationLease acquireForProcess(){throw new AssertionError("Delta transfer must not execute code");}
        public void retryQuarantined(ExpectedArchive release){throw new AssertionError();}
        public void setRetainedPrevious(int count){throw new AssertionError();}
    }
    static Fake artifact(int status,byte[] bytes,String type)throws Exception {
        return new Fake(status,bytes).put("Content-Type",type).put("ETag","\""+HttpTransport.hash(bytes)+"\"");
    }
    public static void main(String[] args)throws Exception {
        for(String scenario:Arrays.asList("delta","missing","corrupt-base","bad-patch","404","401","cancel","unsupported","too-large"))try(Fixture f=new Fixture()){
            byte[] patch=patch();if(scenario.equals("bad-patch"))patch[0]^=1;
            Life life=new Life();life.missing=scenario.equals("missing");life.corrupt=scenario.equals("corrupt-base");
            ExpectedDelta delta=new ExpectedDelta(scenario.equals("unsupported")?"future-codec":DeltaPatch.ALGORITHM,
                life.base.archiveSha256,OLD.length,HttpTransport.hash(patch),scenario.equals("too-large")?NEW.length:patch.length);
            FakeMetadata verifier=new FakeMetadata();verifier.archive=new ExpectedArchive("r1",2,"c".repeat(64),HttpTransport.hash(NEW),NEW.length,Collections.singletonList(delta));
            DeliveryClientTest.Clock clock=new DeliveryClientTest.Clock();
            ShellPolicy policy=new ShellPolicy("example.app","a".repeat(64),new TrustPolicy("example.app",Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap(),0,0),
                BASE.toString(),"stable",Authentication.PUBLIC,Bootstrap.EMBEDDED,true,false,1,Collections.emptyMap(),new byte[0]);
            List<String> paths=new ArrayList<>();
            DeliveryClient client=new DeliveryClient(policy,verifier,life,clock,f.dir,url->{paths.add(url.getPath());f.requests++;return f.responses.remove();},(directory,size)->check(size==NEW.length));
            client.installedCredential(null);
            Fake head=artifact(200,new byte[]{1},"application/json");f.responses.add(head);
            boolean skip=Arrays.asList("missing","unsupported","too-large").contains(scenario);
            if(!skip){
                Fake response=artifact(scenario.equals("404")?404:scenario.equals("401")?401:200,patch,"application/vnd.paravoid.dvpk");
                if(scenario.equals("cancel"))response.onResponse=client::cancelDownload;
                f.responses.add(response);
            }
            boolean fallback=!Arrays.asList("delta","401","cancel").contains(scenario);
            if(fallback)f.responses.add(artifact(200,NEW,"application/vnd.paravoid.vpk"));
            RequestScope scope=new RequestScope("example.app","a".repeat(64),"stable",30,Collections.singletonList("x86_64"),1);
            if(scenario.equals("401")||scenario.equals("cancel")){
                try{client.check(scope,true,false);throw new AssertionError("Expected transfer failure");}catch(HttpTransport.Failure expected){check(expected.status==401||expected.code.equals("cancelled"));}
                check(life.stages==0);check(f.requests==2);
            }else {check(client.check(scope,true,false).stage!=null);check(life.stages==1);}
            check(DeltaPatch.ALGORITHM.equals(head.getRequestProperty("X-Paravoid-Dvpk")));
            check(life.base.archiveSha256.equals(head.getRequestProperty("X-Paravoid-Base-Sha256")));
            if(!skip)check(paths.get(1).endsWith("/releases/r1/deltas/"+life.base.archiveSha256+"/payload.dvpk"));
            if(fallback)check(paths.get(paths.size()-1).endsWith("/payload.vpk"));
            check(life.closes==1);check(!Files.exists(f.dir.resolve("delta-target.vpk")));
            try(java.util.stream.Stream<Path> files=Files.list(f.dir)){check(files.noneMatch(p->p.toString().endsWith(".part")));}
        }
        System.out.println("PASS delta delivery: exact reconstruction, missing/corrupt base, malformed patch, 404 fallback, auth/cancel, unsupported codec and size policy");
    }
}
