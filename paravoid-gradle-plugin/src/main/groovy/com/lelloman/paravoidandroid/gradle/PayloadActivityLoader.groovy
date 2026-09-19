package com.lelloman.paravoidandroid.gradle

import org.objectweb.asm.*
import static org.objectweb.asm.Opcodes.*

/** Framework restoration resolves platform Fragments through Activity.getClassLoader(). */
class PayloadActivityLoader {
    static void adapt(Map<String, byte[]> classes, String activity) {
        // Preserve explicit downstream overrides, including inherited/final ones.
        String parent = activity
        Set<String> visited = new HashSet<>()
        while (parent != null && visited.add(parent) && classes.containsKey(parent + '.class')) {
            ClassReader reader = new ClassReader(classes[parent + '.class'])
            boolean[] declaresLoader = [false] as boolean[]
            reader.accept(new ClassVisitor(ASM9) {
                @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (name == 'getClassLoader' && desc == '()Ljava/lang/ClassLoader;') declaresLoader[0] = true
                    return null
                }
            }, ClassReader.SKIP_CODE)
            if (declaresLoader[0]) return
            parent = reader.superName
        }
        ClassWriter writer = new ClassWriter(0)
        new ClassReader(classes[activity + '.class']).accept(new ClassVisitor(ASM9, writer) {
            @Override void visitEnd() {
                MethodVisitor method = super.visitMethod(ACC_PUBLIC, 'getClassLoader', '()Ljava/lang/ClassLoader;', null, null)
                method.visitCode()
                method.visitVarInsn(ALOAD, 0)
                method.visitMethodInsn(INVOKEVIRTUAL, 'java/lang/Object', 'getClass', '()Ljava/lang/Class;', false)
                method.visitMethodInsn(INVOKEVIRTUAL, 'java/lang/Class', 'getClassLoader', '()Ljava/lang/ClassLoader;', false)
                method.visitInsn(ARETURN)
                method.visitMaxs(1, 1)
                method.visitEnd()
                super.visitEnd()
            }
        }, 0)
        classes[activity + '.class'] = writer.toByteArray()
    }
}
