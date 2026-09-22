package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.GenerationFiles;
import dalvik.system.InMemoryDexClassLoader;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Loads one authenticated immutable generation; caller retains its process-lifetime lease. */
final class CompleteGenerationLoader {
    private CompleteGenerationLoader() {}
    static ClassLoader load(Application application, GenerationLease lease, ClassLoader parent) throws Exception {
        GenerationFiles files = lease.files();
        // Must be durable before any code from this generation can execute, including providers.
        lease.beforeUserCode();
        EmbeddedResources.installVerified(application, files.resourcesApk);
        ClassLoader resources = new PayloadResourceClassLoader(parent, files.javaResourcesJar);
        ByteBuffer[] buffers = new ByteBuffer[files.dexFiles.size()];
        for (int i = 0; i < buffers.length; i++) {
            try (FileInputStream input = new FileInputStream(files.dexFiles.get(i))) {
                buffers[i] = input.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, input.getChannel().size());
            }
        }
        return new InMemoryDexClassLoader(buffers,
            files.nativeDirectory == null ? null : files.nativeDirectory.getAbsolutePath(), resources);
    }
}
