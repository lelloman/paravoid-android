package com.lelloman.paravoidandroid.gradle

import com.lelloman.paravoidandroid.contract.*
import com.lelloman.paravoidandroid.contract.Protocol
import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipFile

/** Independently signed complete payload artifact. Does not by itself wire a shell's startup/activation. */
abstract class PackageCompleteVpkTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getPolicyFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getCodeBundle()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getResourcesApk()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getJavaResources()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getNativeArchive()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getLedgerFile()
    @Input abstract Property<Long> getPayloadVersion()
    @Input abstract Property<String> getReleaseId()
    @Input abstract Property<String> getKeyId()
    @Input abstract Property<Integer> getMinSdk()
    @Input abstract Property<Boolean> getDebuggable()
    @Internal abstract RegularFileProperty getPrivateKeyFile()
    @OutputFile abstract RegularFileProperty getVpkFile()
    @OutputFile abstract RegularFileProperty getReleaseFile()
    @OutputFile abstract RegularFileProperty getDigestFile()
    @OutputFile @Optional abstract RegularFileProperty getLegacyDigestFile()
    @OutputFile abstract RegularFileProperty getReportFile()
    PackageCompleteVpkTask() {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf('Payload signing material must not enter Gradle caches') { true }
    }
    @TaskAction void pack() {
        if (!privateKeyFile.present || !privateKeyFile.get().asFile.isFile()) throw new GradleException('Configure paravoid.signing.privateKeyFile (unencrypted RSA-3072 PKCS#8).')
        def policy = InstalledPolicyCodec.read(policyFile.get().asFile.bytes, debuggable.get())
        Map<String,File> components = new TreeMap<>()
        new ZipFile(codeBundle.get().asFile).withCloseable { zip ->
            zip.entries().each { entry ->
                if (entry.name ==~ /classes(?:[2-9]|[1-9][0-9]+)?\.dex/) {
                    File dex = new File(temporaryDir, entry.name)
                    zip.getInputStream(entry).withCloseable { source -> dex.withOutputStream { it << source } }
                    components['code/' + entry.name] = dex
                } else if (entry.name != 'module.properties') throw new GradleException('Unexpected code-bundle entry.')
            }
        }
        components['resources.apk'] = resourcesApk.get().asFile
        components['java-resources.jar'] = javaResources.get().asFile
        components['resource-ledger.json'] = ledgerFile.get().asFile
        Set<String> abis = new TreeSet<>()
        new ZipFile(nativeArchive.get().asFile).withCloseable { zip ->
            zip.entries().each { entry ->
                if (!(entry.name ==~ /lib\/(arm64-v8a|armeabi-v7a|x86|x86_64)\/[A-Za-z0-9_+.-]+\.so/)) throw new GradleException('Unexpected native-bundle entry.')
                abis.add(entry.name.split('/')[1])
                File library = new File(temporaryDir, entry.name); library.parentFile.mkdirs()
                zip.getInputStream(entry).withCloseable { source -> library.withOutputStream { it << source } }
                components['native/' + entry.name.substring(4)] = library
            }
        }
        def key
        try {
            File source = privateKeyFile.get().asFile
            if (source.length() > 16384) throw new IllegalArgumentException()
            byte[] encoded = source.bytes
            String pem = new String(encoded, 'US-ASCII')
            if (pem.startsWith('-----BEGIN PRIVATE KEY-----')) {
                def match = pem =~ /(?s)-----BEGIN PRIVATE KEY-----\r?\n([A-Za-z0-9+\/=\r\n]+)-----END PRIVATE KEY-----\r?\n?/
                if (!match.matches()) throw new IllegalArgumentException()
                encoded = Base64.decoder.decode(match[0][1].replace('\r', '').replace('\n', ''))
            }
            key = KeyFactory.getInstance('RSA').generatePrivate(new PKCS8EncodedKeySpec(encoded))
        } catch (Exception ignored) { throw new GradleException('Signing key must be an unencrypted RSA-3072 PKCS#8 file (DER or PEM).') }
        def device = new Protocol.RequestScope(policy.applicationId, policy.shellContractId, policy.channel, minSdk.get(),
            abis.empty ? ['x86_64'] : abis.toList(), Protocol.RUNTIME_ABI)
        def specification = new VpkWriter.ReleaseSpec(releaseId.get(), payloadVersion.get(), minSdk.get(), 0, abis.toList())
        def release = new VpkWriter().write(vpkFile.get().asFile, components, specification, policy, device, keyId.get(), key)
        releaseFile.get().asFile.bytes = release.envelope()
        digestFile.get().asFile.setText(release.identity.archiveSha256 + '\n', 'US-ASCII')
        if (legacyDigestFile.present) legacyDigestFile.get().asFile.setText(release.identity.archiveSha256 + '\n', 'US-ASCII')
        reportFile.get().asFile.setText(JsonOutput.prettyPrint(JsonOutput.toJson([
            profile: 'complete-vpk-1', applicationId: policy.applicationId, shellContractId: policy.shellContractId,
            releaseId: release.identity.releaseId, payloadVersion: release.identity.payloadVersion,
            archiveSize: release.identity.archiveSize, archiveSha256: release.identity.archiveSha256,
            releaseKeyId: keyId.get(), components: release.inventory.collect { [path: it.path, size: it.size, sha256: it.sha256] }
        ])) + '\n', 'UTF-8')
    }
}
