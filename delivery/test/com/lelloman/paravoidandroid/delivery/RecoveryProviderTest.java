package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.recovery.*;
import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.*;
import static com.lelloman.paravoidandroid.delivery.DeliveryClientTest.*;

public final class RecoveryProviderTest {
    static class Provider implements RecoveryUpdateProvider {
        int checks,downloads;
        byte[] head={1}, archive=ARCHIVE;
        int status;
        boolean cancel;
        public byte[] check(RecoveryRequest request, Cancellation cancellation) throws Exception {
            checks++;
            TransportTest.check(request.applicationId.equals("example.app"));
            if(status!=0) throw new RecoveryUpdateException(status);
            return head;
        }
        public void download(RecoveryRequest request, RecoveryUpdate update, OutputStream destination, Cancellation cancellation) throws Exception {
            downloads++;
            if(cancel) throw new IOException("cancelled fixture");
            destination.write(archive);
        }
    }
    public static void main(String[] args) throws Exception {
        try(Setup s=new Setup(false)) {
            Provider p=new Provider(); s.client.recoveryProvider(p);
            DeliveryClient.Result offer=s.client.check(s.scope,false,false);
            check(offer.available!=null && s.life.stages==0 && p.downloads==0);
            s.client.downloadOffered(s.scope,offer.available);
            check(s.life.stages==1 && p.downloads==1 && s.f.requests==0);
        }
        try(Setup s=new Setup(false)) {
            Provider p=new Provider(); s.client.recoveryProvider(p);
            ExpectedArchive offer=s.client.check(s.scope,false,false).available;
            s.metadata.archive=new ExpectedArchive("r2",2,"c".repeat(64),HASH,ARCHIVE.length);
            check(s.client.downloadOffered(s.scope,offer).stage==null);
            check(p.downloads==0 && s.life.stages==0);
            p.head=new byte[]{0};
            contractFailure(ContractException.Code.INVALID_SIGNATURE,()->s.client.check(s.scope,true,true));
            check(p.downloads==0);
        }
        try(Setup s=new Setup(true)) {
            Provider p=new Provider(); s.client.recoveryProvider(p); p.status=403;
            try { s.client.check(s.scope,false,false); throw new AssertionError(); } catch(IOException expected) { }
            int calls=p.checks;
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE,()->s.client.check(s.scope,false,false));
            check(p.checks==calls);
            p.status=0; s.client.check(s.scope,false,true); check(p.checks==calls+1);
        }
        try(Setup s=new Setup(false,true)) {
            Provider p=new Provider(); s.client.recoveryProvider(p);
            contractFailure(ContractException.Code.INSUFFICIENT_STORAGE,()->s.client.check(s.scope,true,true));
            check(p.downloads==0 && !s.life.reserved);
        }
        try(Setup s=new Setup(false)) {
            Provider p=new Provider(); s.client.recoveryProvider(p); p.archive=new byte[ARCHIVE.length];
            try { s.client.check(s.scope,true,true); throw new AssertionError(); }
            catch(HttpTransport.Failure failure) { check(failure.code.equals("archive-hash-mismatch")); }
            check(s.life.stages==0 && !s.life.reserved);
            try { s.client.recoveryCheck(s.scope,null,true,()->true); throw new AssertionError(); }
            catch(InterruptedIOException expected) { }
            check(p.checks==1);
        }
        HttpTransport.Cancellation c=new HttpTransport.Cancellation();
        boolean[] disconnected={false}; c.onCancel(()->disconnected[0]=true); c.cancel(); check(disconnected[0]);
        try { c.check(); throw new AssertionError(); } catch(IOException expected) { }
        System.out.println("PASS custom recovery transport: verified offers, explicit download, changed offer, signatures, auth suppression, storage, cancellation");
    }
}
