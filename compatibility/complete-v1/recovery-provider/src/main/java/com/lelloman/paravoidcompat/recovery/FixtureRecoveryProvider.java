package com.lelloman.paravoidcompat.recovery;

import com.lelloman.paravoidandroid.recovery.*;
import java.io.*;
import java.net.*;

/** Disposable fixture only. Production integrations must enforce their own transport policy. */
public final class FixtureRecoveryProvider implements RecoveryUpdateProvider {
    public FixtureRecoveryProvider() { }
    public byte[] check(RecoveryRequest request, Cancellation cancellation) throws Exception {
        if(new File(request.privateDirectory,"kill-provider").exists()) Runtime.getRuntime().halt(73);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        transfer(request,"v1/apps/"+request.applicationId+"/head?contract="+request.shellContractId+
            "&channel="+request.channel+"&sdk="+request.sdk+"&abis="+URLEncoder.encode(String.join(",",request.abis),"UTF-8")+
            "&runtime="+request.runtimeAbi+"&format=1&protocol=1",bytes,cancellation,1024*1024);
        return bytes.toByteArray();
    }
    public void download(RecoveryRequest request,RecoveryUpdate update,OutputStream destination,Cancellation cancellation) throws Exception {
        transfer(request,"v1/apps/"+request.applicationId+"/releases/"+update.releaseId+"/payload.vpk",destination,cancellation,update.archiveSize);
    }
    private void transfer(RecoveryRequest request,String path,OutputStream out,Cancellation cancellation,long limit) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(new URL(request.baseUrl),path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
        if(request.authorizationHeader()!=null) connection.setRequestProperty("Authorization",request.authorizationHeader());
        try(AutoCloseable registration=cancellation.onCancel(connection::disconnect)) {
            cancellation.check(); int status=connection.getResponseCode();
            if(status>=400) throw new RecoveryUpdateException(status);
            if(status!=200) throw new IOException("Unexpected response");
            try(InputStream input=connection.getInputStream()) {
                byte[] bytes=new byte[8192]; int n; long total=0;
                while((n=input.read(bytes))!=-1) {
                    cancellation.check(); total+=n; if(total>limit) throw new IOException("Response exceeds limit");
                    out.write(bytes,0,n);
                }
            }
        } finally { connection.disconnect(); }
    }
}
