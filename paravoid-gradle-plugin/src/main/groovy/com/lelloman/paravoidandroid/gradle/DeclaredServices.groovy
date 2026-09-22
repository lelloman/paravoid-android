package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import org.objectweb.asm.*

/** APK-bound service-kind dispatch; payload superclass changes therefore change the shell contract. */
final class DeclaredServices {
    static final String NAME = 'com/lelloman/paravoidandroid/runtime/DeclaredServices'
    static byte[] generate(Map<String, byte[]> classes, Collection<String> services) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, NAME, null, 'java/lang/Object', null)
        def method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, 'isJobService', '(Ljava/lang/String;)Z', null, null)
        method.visitCode()
        services.toSet().sort().each { service ->
            String current = service.replace('.', '/')
            Set<String> seen = [] as Set
            boolean job = false
            while (current != null && seen.add(current)) {
                if (current == 'android/app/job/JobService') { job = true; break }
                byte[] bytes = classes[current + '.class']
                if (bytes == null) {
                    if (!current.startsWith('android/')) throw new GradleException('Missing declared service superclass: ' + current)
                    break
                }
                current = new ClassReader(bytes).superName
            }
            if (job) {
                Label next = new Label()
                method.visitLdcInsn(service); method.visitVarInsn(Opcodes.ALOAD, 0)
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/String', 'equals', '(Ljava/lang/Object;)Z', false)
                method.visitJumpInsn(Opcodes.IFEQ, next)
                method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN)
                method.visitLabel(next)
            }
        }
        method.visitInsn(Opcodes.ICONST_0); method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd()
        writer.toByteArray()
    }
}
