package com.lelloman.paravoidandroid.gradle

import com.lelloman.paravoidandroid.contract.InstalledPolicyCodec
import com.lelloman.paravoidandroid.contract.Protocol
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateCompletePolicyTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getInstalledBoundary()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getTrustPolicyFile()
    @Input abstract Property<String> getBootstrap()
    @Input abstract Property<Boolean> getUpdatesEnabled()
    @Input abstract Property<String> getBaseUrl()
    @Input abstract Property<String> getChannel()
    @Input abstract Property<String> getAuthentication()
    @Input abstract Property<Boolean> getDebugHttpAllowed()
    @Input abstract Property<Boolean> getDebuggable()
    @Input abstract Property<Boolean> getReleaseBuild()
    @Input abstract MapProperty<String,String> getUpdateConfiguration()
    GenerateCompletePolicyTask() { updateConfiguration.convention([:]) }
    @OutputFile abstract RegularFileProperty getPolicyFile()

    @TaskAction void generate() {
        if (!(bootstrap.get() in ['embedded', 'empty']) || !(authentication.get() in ['public', 'apkKey']))
            throw new GradleException('Complete policy requires bootstrap embedded/empty and authentication public/apkKey.')
        if (releaseBuild.get() && debugHttpAllowed.get()) throw new GradleException('Release output forbids debug HTTP.')
        String endpoint = baseUrl.get()
        if (!endpoint && updateConfiguration.get().get('mode') == 'feed') {
            endpoint = new URI(updateConfiguration.get().get('metadataUrl')).resolve('.').toString()
        }
        if (endpoint) {
            try {
                URI uri = new URI(endpoint)
                if (uri.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null || !endpoint.endsWith('/'))
                    throw new IllegalArgumentException()
                String scheme = uri.scheme.toLowerCase(Locale.ROOT), host = uri.host.toLowerCase(Locale.ROOT)
                int port = uri.port
                endpoint = scheme + '://' + host + ((port == -1 || port == (scheme == 'https' ? 443 : 80)) ? '' : ':' + port) + uri.rawPath
            } catch (Exception ignored) { throw new GradleException('Invalid Paravoid baseUrl: use an absolute URL ending in /, without userinfo/query/fragment.') }
        }
        byte[] encoded = InstalledPolicyCodec.create(installedBoundary.get().asFile.bytes, trustPolicyFile.get().asFile.bytes,
            bootstrap.get() == 'embedded' ? Protocol.Bootstrap.EMBEDDED : Protocol.Bootstrap.EMPTY,
            updatesEnabled.get(), endpoint, channel.get(),
            authentication.get() == 'public' ? Protocol.Authentication.PUBLIC : Protocol.Authentication.APK_KEY,
            debugHttpAllowed.get(), debuggable.get(), updateConfiguration.get())
        File output = policyFile.get().asFile; output.parentFile.mkdirs()
        output.setText(new String(encoded, 'UTF-8') + '\n', 'UTF-8')
    }
}
