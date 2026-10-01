package com.lelloman.paravoidandroid.contract;

import java.io.*;
import java.security.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Python/Java interoperability harness, never packaged in the shell. */
public final class DeltaDecodeMain {
    public static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[8192];
        try (InputStream input = new FileInputStream(file)) { int count; while ((count=input.read(bytes))!=-1) digest.update(bytes,0,count); }
        StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return out.toString();
    }
    public static void main(String[] args) throws Exception {
        File old = new File(args[0]), target = new File(args[1]), patch = new File(args[2]), output = new File(args[3]);
        ExpectedArchive baseIdentity = new ExpectedArchive("base",1,"a".repeat(64),hash(old),old.length());
        ExpectedArchive expected = new ExpectedArchive("target",2,"b".repeat(64),hash(target),target.length());
        try (RandomAccessFile file = new RandomAccessFile(old,"r"); DeltaBase base = new DeltaBase() {
            public ExpectedArchive identity() { return baseIdentity; }
            public int read(long position, byte[] bytes, int offset, int length) throws IOException { file.seek(position); return file.read(bytes,offset,length); }
            public void close() { }
        }) {
            DeltaPatch.apply(patch,base,output,new ExpectedDelta(DeltaPatch.ALGORITHM,baseIdentity.archiveSha256,
                old.length(),hash(patch),patch.length()),expected,()-> {});
        }
        if (!hash(output).equals(expected.archiveSha256)) throw new AssertionError("Wrong output");
    }
}
