package com.lelloman.paravoidandroid.gradle

import com.android.apksig.ApkVerifier
import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*
import java.util.zip.ZipFile

/** Snapshot the installed boundary, never the independently replaceable payload bytes. */
@CacheableTask
abstract class GenerateShellContractTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getApkDirectory()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBoundaryFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getLedgerFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getShellClasses()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getManifestFile()
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getBaselineFile()
    @Input abstract Property<Integer> getMinSdk()
    @Input abstract MapProperty<String, String> getToolchain()
    @OutputFile abstract RegularFileProperty getContractFile()

    @TaskAction void generate() {
        if (minSdk.get() < 30) throw new GradleException('Resource shell packaging requires minSdk >= 30; normal/DEX-only builds are unchanged.')
        def boundary = new JsonSlurper().parse(boundaryFile.get().asFile)
        def ledger = ResourceLedger.read(ledgerFile.get().asFile.getText('UTF-8'))
        def accepted = baselineFile.present ? ShellContract.read(baselineFile.get().asFile.bytes) : null
        if (ledger.applicationId != boundary.applicationId) throw new GradleException('Contract resource inputs have different application IDs')
        Map classes = [:]
        new ZipFile(shellClasses.get().asFile).withCloseable { zip ->
            zip.entries().findAll { !it.directory }.each { entry ->
                classes[entry.name] = ShellContract.sha(ResourceArchive.read(zip, entry.name))
            }
        }
        def apks = apkDirectory.get().asFile.listFiles().findAll { it.isFile() && it.name.endsWith('.apk') }
        if (apks.size() != 1) throw new GradleException('Shell contract requires one standalone APK')
        def signature = new ApkVerifier.Builder(apks[0]).build().verify()
        if (!signature.verified) throw new GradleException('Shell contract requires the variant signingConfig and a valid signed input APK')
        Map abis = [:]
        new ZipFile(apks[0]).withCloseable { zip ->
            zip.entries().findAll { !it.directory && it.name.startsWith('lib/') }.collect { it.name.split('/')[1] }.toSet().each { abi ->
                def marker = getClass().getResourceAsStream('/com/lelloman/paravoid/abi/' + abi + '.base64')
                if (marker == null) throw new GradleException('No shell ABI marker for ' + abi)
                abis[abi] = marker.withCloseable { ShellContract.sha(Base64.mimeDecoder.decode(it.readAllBytes())) }
            }
        }
        Map descriptor = [profile: 'embedded-apk-v1', applicationId: boundary.applicationId,
            minSdk: minSdk.get(), manifestSha256: boundary.manifestSha256,
            declarations: declarations(manifestFile.get().asFile),
            pinnedResources: boundary.pinned.collectEntries { [(it.name): [id: it.id, sha256: it.sha256]] },
            runtimeClasses: classes, nativeAbis: abis,
            ledgerReservations: ShellContract.reservations(ledger, accepted?.descriptor),
            apkSigners: signature.signerCertificates.collect { ShellContract.sha(it.encoded) }.sort(),
            distribution: [bootstrap: 'embedded', updates: false], toolchain: toolchain.get()]
        File output = contractFile.get().asFile
        output.parentFile.mkdirs()
        output.setText(CanonicalJson.encode(ShellContract.document(descriptor)) + '\n', 'UTF-8')
    }

    static Map declarations(File manifest) {
        Map result = [:]
        snapshot(new XmlParser(false, true).parse(manifest), '', result)
        result
    }

    private static void snapshot(Node node, String path, Map result) {
        node.attributes().each { key, value ->
            String name = key.toString()
            if (!(path == '' && name in ['{http://schemas.android.com/apk/res/android}versionCode', '{http://schemas.android.com/apk/res/android}versionCodeMajor']))
                result[path + '/@' + name] = value.toString()
        }
        Map counts = [:]
        node.children().findAll { it instanceof Node }.each { child ->
            String tag = child.name().toString()
            int index = counts.get(tag, 0)
            counts[tag] = index + 1
            snapshot(child, path + '/' + tag + '[' + index + ']', result)
        }
    }
}
