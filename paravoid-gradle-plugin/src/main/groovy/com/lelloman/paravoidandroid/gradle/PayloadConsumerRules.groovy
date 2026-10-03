package com.lelloman.paravoidandroid.gradle

import com.android.build.gradle.internal.dependency.FilterShrinkerRulesTransformKt
import com.android.build.gradle.internal.dependency.ShrinkerVersion
import org.gradle.api.GradleException
import org.objectweb.asm.*
import java.security.MessageDigest
import java.util.zip.ZipFile

/** AGP 8.13.2 extraction, filtered for the SDK R8 actually running the payload. */
final class PayloadConsumerRules {
    static String r8Version(File jar) {
        String label = null
        new ZipFile(jar).withCloseable { zip ->
            def entry = zip.getEntry('com/android/tools/r8/Version.class')
            if (entry == null) throw new GradleException('Cannot read payload R8 version')
            byte[] bytes = zip.getInputStream(entry).withCloseable { it.readAllBytes() }
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    if (name == 'LABEL' && value instanceof String) label = (String) value
                    return null
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        }
        if (label == null) throw new GradleException('Cannot read payload R8 version label')
        return label
    }

    static String merge(Set<File> artifacts, String version) {
        // Explicit field access avoids Groovy resolving Kotlin's nested
        // Companion class instead of its singleton field of the same name.
        def shrinker = ShrinkerVersion.@Companion.parse(version)
        Map<String, String> rules = new TreeMap<>()
        artifacts.each { artifact ->
            List<File> files = []
            if (artifact.isFile()) files.add(artifact)
            else if (artifact.isDirectory()) {
                // AGP wraps each extracted JAR/AAR in a library directory. A
                // direct directory is supported too (e.g. locally supplied rules).
                List<File> children = artifact.listFiles().findAll { it.isDirectory() }
                List<File> libraries = children.any { new File(it, 'META-INF').isDirectory() || new File(it, 'proguard.txt').isFile() }
                    ? children : [artifact]
                libraries.each { library ->
                    File targeted = new File(library, 'META-INF/com.android.tools')
                    if (targeted.isDirectory()) {
                        targeted.listFiles().findAll { it.isDirectory() &&
                            FilterShrinkerRulesTransformKt.configDirMatchesVersion(it.name, shrinker) }.each { directory ->
                            files.addAll(directory.listFiles().findAll { it.isFile() })
                        }
                    } else {
                        File aar = new File(library, 'proguard.txt')
                        File legacy = new File(library, 'META-INF/proguard')
                        if (aar.isFile()) files.add(aar)
                        else if (legacy.isDirectory()) files.addAll(legacy.listFiles().findAll { it.isFile() })
                    }
                }
            }
            files.each { file ->
                byte[] bytes = file.bytes
                String digest = MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
                rules.put(digest, new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
            }
        }
        // Stable across checkout/cache locations; duplicate rule files apply once.
        "# Dependency consumer rules for payload R8 ${version}\n" + rules.collect { digest, text ->
            "# SHA-256 ${digest}\n${text}\n"
        }.join('\n')
    }
}
