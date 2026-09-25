package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import java.util.zip.ZipFile

final class RecoveryPackaging {
    static Set<String> collect(Collection<File> jars, Map<String, byte[]> classes) {
        Set<String> names = new TreeSet<>()
        jars.each { file ->
            if (!file.name.endsWith('.jar')) throw new GradleException('Recovery dependencies must be code-only JARs: ' + file)
            new ZipFile(file).withCloseable { zip ->
                zip.entries().each { e ->
                    if (e.directory) return
                    if (e.name == 'module-info.class') return
                    if (e.name.endsWith('.class')) {
                        byte[] bytes = zip.getInputStream(e).withCloseable { it.readAllBytes() }
                        byte[] existing = classes.get(e.name)
                        if (existing != null && !Arrays.equals(existing, bytes))
                            throw new GradleException('Conflicting recovery/payload dependency class: ' + e.name)
                        classes.put(e.name, bytes); names.add(e.name.substring(0, e.name.length() - 6))
                    } else if (!(e.name == 'META-INF/MANIFEST.MF' || e.name.startsWith('META-INF/maven/') ||
                            e.name ==~ /META-INF\/(LICENSE|NOTICE|DEPENDENCIES)(\..*)?/ || e.name.endsWith('.kotlin_module'))) {
                        throw new GradleException('Recovery dependency contains runtime resources; use a code-only module: ' + file.name + ':' + e.name)
                    }
                }
            }
        }
        names
    }
    static void validate(Set<String> names, Map<String, byte[]> classes, File androidJar, String provider) {
        validate(names,classes,androidJar,provider,'com/lelloman/paravoidandroid/recovery/RecoveryUpdateProvider')
    }
    static void validate(Set<String> names, Map<String, byte[]> classes, File androidJar, String provider, String api) {
        if (provider && !names.contains(provider.replace('.', '/')))
            throw new GradleException('Recovery provider must be supplied by paravoidRecoveryImplementation: ' + provider)
        Set<String> allowed = new HashSet<>(names)
        // D8 lowers these Java compiler bootstrap methods to Android-compatible bytecode.
        allowed.addAll(['java/lang/invoke/StringConcatFactory', 'java/lang/invoke/LambdaMetafactory'])
        classes.keySet().each { if (shell(it)) allowed.add(it.substring(0, it.length() - 6)) }
        new ZipFile(androidJar).withCloseable { zip ->
            zip.entries().each { if (it.name.endsWith('.class')) allowed.add(it.name.substring(0, it.name.length() - 6)) }
        }
        names.each { name ->
            ClassReader reader = new ClassReader(classes.get(name + '.class'))
            if (reader.className == provider.replace('.', '/')) {
                boolean constructor = false
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override MethodVisitor visitMethod(int access, String n, String desc, String sig, String[] exceptions) {
                        if (n == '<init>' && desc == '()V' && (access & Opcodes.ACC_PUBLIC) != 0) constructor = true
                        return null
                    }
                }, ClassReader.SKIP_CODE)
                if (!constructor || (reader.access & Opcodes.ACC_PUBLIC) == 0 || (reader.access & Opcodes.ACC_ABSTRACT) != 0)
                    throw new GradleException('Recovery provider must be public, concrete, with a public no-argument constructor.')
                String parent = reader.className
                boolean implementsApi = false
                Set<String> visited = new HashSet<>()
                def inspect
                inspect = { String type ->
                    if (type == api) { implementsApi = true; return }
                    if (!visited.add(type) || !classes.containsKey(type + '.class')) return
                    def c = new ClassReader(classes.get(type + '.class'))
                    c.interfaces.each { inspect(it) }; if (c.superName) inspect(c.superName)
                }
                inspect(parent)
                if (!implementsApi) throw new GradleException('Recovery/update provider must implement ' + api.substring(api.lastIndexOf('/')+1) + '.')
            }
            reader.accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
                @Override String map(String type) {
                    if (!allowed.contains(type)) throw new GradleException('Recovery class ' + name + ' references non-shell class ' + type)
                    type
                }
            }), 0)
        }
    }
    static boolean shell(String name) {
        ['runtime/', 'api/', 'contract/', 'delivery/', 'recovery/', 'updates/'].any { name.startsWith('com/lelloman/paravoidandroid/' + it) }
    }
}
