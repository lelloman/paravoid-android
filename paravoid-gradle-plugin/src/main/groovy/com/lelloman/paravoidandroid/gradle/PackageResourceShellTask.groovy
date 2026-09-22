package com.lelloman.paravoidandroid.gradle

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/** Explicit embedded-resource APK stage. Does not claim complete/VPK packaging. */
abstract class PackageResourceShellTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getApkDirectory()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getShellResources()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getPayloadResources()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getJavaResourceArchive()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getNativeResourceArchive()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getShellClasses()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getManifestFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getContractFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getZipalign()
    @Input abstract Property<Integer> getMinSdk()
    @Internal abstract RegularFileProperty getKeyStoreFile()
    @Internal abstract Property<String> getStorePassword()
    @Internal abstract Property<String> getKeyPassword()
    @Internal abstract Property<String> getKeyAlias()
    @Internal abstract Property<String> getStoreType()
    @OutputFile abstract RegularFileProperty getShellApk()
    @Inject abstract ExecOperations getExecOperations()

    PackageResourceShellTask() {
        // Signing credentials must not enter Gradle cache keys or serialized task outputs.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf('Signed output uses private signing configuration') { true }
    }

    @TaskAction void assemble() {
        if (minSdk.get() < 30) throw new GradleException('Resource shell packaging requires minSdk >= 30; normal/DEX-only builds are unchanged.')
        if (!keyStoreFile.present || !storePassword.present || !keyPassword.present || !keyAlias.present)
            throw new GradleException('Resource shell packaging requires the variant signingConfig (debug or release).')
        def manifest = new XmlParser(false, false).parse(manifestFile.get().asFile)
        if (manifest.depthFirst().any { it instanceof Node &&
            ['android:directBootAware', 'android:isolatedProcess'].any { name ->
                it.attributes().containsKey(name) && it.attributes()[name] != 'false'
            } })
            throw new GradleException('Resource shells do not yet support directBootAware or isolatedProcess components.')
        new ZipFile(shellClasses.get().asFile).withCloseable {
            if (it.getEntry('com/lelloman/paravoidandroid/runtime/EmbeddedResources.class') == null ||
                it.getEntry('com/lelloman/paravoidandroid/runtime/PayloadResourceClassLoader.class') == null ||
                it.getEntry('com/lelloman/paravoidandroid/runtime/EmbeddedNativeLibraries.class') == null)
                throw new GradleException('Resource shell requires a matching runtime with EmbeddedResources support.')
        }
        List<File> apks = apkDirectory.get().asFile.listFiles().findAll { it.name.endsWith('.apk') && it.isFile() }
        if (apks.size() != 1) throw new GradleException('Resource shell packaging requires one standalone APK.')
        def outputs = new groovy.json.JsonSlurper().parse(new File(apkDirectory.get().asFile, 'output-metadata.json'))
        if (outputs.elements.size() != 1 || outputs.elements[0].filters)
            throw new GradleException('Resource shell packaging does not support filtered/split APK outputs.')
        File input = apks[0]
        def originalSignature = new ApkVerifier.Builder(input).build().verify()
        if (!originalSignature.verified) throw new GradleException('Input APK must have a valid signature.')
        File payload = payloadResources.get().asFile
        if (payload.length() <= 0 || payload.length() > 256L * 1024 * 1024)
            throw new GradleException('Embedded resource archive exceeds the current 256 MiB profile.')
        if (javaResourceArchive.get().asFile.length() > 256L * 1024 * 1024)
            throw new GradleException('Java-resource archive exceeds 256 MiB.')
        if (nativeResourceArchive.get().asFile.length() > 256L * 1024 * 1024)
            throw new GradleException('Embedded native archive exceeds the current 256 MiB profile.')
        File unsigned = new File(temporaryDir, 'unsigned.apk')
        new ZipFile(input).withCloseable { original ->
            new ZipFile(shellResources.get().asFile).withCloseable { pinned ->
                new ZipFile(javaResourceArchive.get().asFile).withCloseable { javaResources ->
                    writeShell(unsigned, original, pinned, payload.bytes, javaResourceArchive.get().asFile.bytes,
                        javaResources.entries().collect { it.name }.toSet(), nativeResourceArchive.get().asFile.bytes, contractFile.get().asFile.bytes)
                }
            }
        }
        File aligned = new File(temporaryDir, 'aligned.apk')
        execOperations.exec { commandLine(zipalign.get().asFile, '-f', '-P', '16', '4', unsigned, aligned) }.assertNormalExitValue()
        KeyStore keys = KeyStore.getInstance(storeType.getOrElse(KeyStore.defaultType))
        keyStoreFile.get().asFile.withInputStream { keys.load(it, storePassword.get().toCharArray()) }
        def privateKey = keys.getKey(keyAlias.get(), keyPassword.get().toCharArray())
        List<X509Certificate> chain = keys.getCertificateChain(keyAlias.get())?.toList()
        if (!(privateKey instanceof PrivateKey) || !chain) throw new GradleException('Signing alias has no private key/certificate.')
        if (!originalSignature.signerCertificates.any { Arrays.equals(it.encoded, chain[0].encoded) })
            throw new GradleException('Resource shell signing certificate must match the input APK.')
        File signed = new File(temporaryDir, 'signed.apk')
        def signer = new ApkSigner.SignerConfig.Builder('paravoid', privateKey, chain).build()
        new ApkSigner.Builder([signer]).setInputApk(aligned).setOutputApk(signed)
            .setMinSdkVersion(minSdk.get()).setV1SigningEnabled(false).setV2SigningEnabled(true)
            .setV3SigningEnabled(true).setV4SigningEnabled(false).build().sign()
        if (!new ApkVerifier.Builder(signed).build().verify().verified) throw new GradleException('Resource shell signature verification failed.')
        execOperations.exec { commandLine(zipalign.get().asFile, '-c', '-P', '16', '4', signed) }.assertNormalExitValue()
        File output = shellApk.get().asFile
        output.parentFile.mkdirs()
        Files.copy(signed.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    static void writeShell(File output, ZipFile original, ZipFile pinned, byte[] payload, byte[] javaResources = null, Set<String> javaPaths = [], byte[] nativeResources = null, byte[] contract = null) {
        if (!Arrays.equals(ResourceArchive.read(original, 'AndroidManifest.xml'), ResourceArchive.read(pinned, 'AndroidManifest.xml')))
            throw new GradleException('Resource shell manifest does not match the original APK.')
        Set<String> keep = original.entries().findAll { entry ->
            String name = entry.name
            !entry.directory && !javaPaths.contains(name) && name != 'resources.arsc' && !name.startsWith('res/') &&
                (nativeResources == null || !name.startsWith('lib/')) &&
                (!name.startsWith('assets/') || name == 'assets/paravoid/module.zip') &&
                !(name.startsWith('META-INF/') && (name.toUpperCase(Locale.ROOT).endsWith('.SF') ||
                    name.toUpperCase(Locale.ROOT).endsWith('.RSA') || name.toUpperCase(Locale.ROOT).endsWith('.DSA') ||
                    name.toUpperCase(Locale.ROOT).endsWith('.EC') || name == 'META-INF/MANIFEST.MF'))
        }.collect { it.name }.toSet()
        if (!keep.contains('assets/paravoid/module.zip')) throw new GradleException('Embedded code bundle is missing.')
        Map<String, byte[]> replacements = [:]
        if (contract != null) {
            ShellContract.read(contract)
            replacements['assets/paravoid/shell-contract.json'] = contract
        }
        pinned.entries().each { entry ->
            if (!entry.directory) {
                if (!(entry.name in ['AndroidManifest.xml', 'resources.arsc']) && !entry.name.startsWith('res/'))
                    throw new GradleException('Unexpected pinned container entry: ' + entry.name)
                replacements[entry.name] = ResourceArchive.read(pinned, entry.name)
            }
        }
        if (!replacements.containsKey('resources.arsc')) throw new GradleException('Pinned resource table is missing.')
        replacements['assets/paravoid/resources.apk'] = payload
        replacements['assets/paravoid/resources.sha256'] = (MessageDigest.getInstance('SHA-256').digest(payload).encodeHex().toString() + '\n').getBytes('US-ASCII')
        if (javaResources != null) {
            if (javaResources.length > 256L * 1024 * 1024) throw new GradleException('Java-resource archive exceeds 256 MiB.')
            replacements['assets/paravoid/java-resources.jar'] = javaResources
            replacements['assets/paravoid/java-resources.sha256'] = (MessageDigest.getInstance('SHA-256').digest(javaResources).encodeHex().toString() + '\n').getBytes('US-ASCII')
        }
        if (nativeResources != null) {
            replacements['assets/paravoid/native-libraries.zip'] = nativeResources
            replacements['assets/paravoid/native-libraries.sha256'] = (MessageDigest.getInstance('SHA-256').digest(nativeResources).encodeHex().toString() + '\n').getBytes('US-ASCII')
            original.entries().findAll { !it.directory && it.name.startsWith('lib/') }.collect { it.name.split('/')[1] }.toSet().each { abi ->
                String path = '/com/lelloman/paravoid/abi/' + abi + '.base64'
                def marker = PackageResourceShellTask.getResourceAsStream(path)
                if (marker == null) throw new GradleException('No shell ABI marker for ' + abi)
                replacements['lib/' + abi + '/libparavoid_abi.so'] = marker.withCloseable { Base64.mimeDecoder.decode(it.readAllBytes()) }
            }
        }
        ResourceArchive.write(output, original, keep, replacements)
    }
}
