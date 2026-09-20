package com.lelloman.paravoidandroid.gradle

import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class PayloadStateEnvelopeTest {
    @Test void protectsAfterUserCodeAtEveryNormalReturnButNotThrow() {
        def classes = ['example/Main.class': fixture('example/Main', 'android/app/Activity', ACC_PROTECTED, PayloadStateEnvelope.SAVE, true)]
        PayloadStateEnvelope.adapt(classes, 'example/Main')
        assertEquals(['user', 'protect', 'return', 'protect', 'return', 'throw'], events(classes['example/Main.class']))
    }

    @Test void modifiesInheritedFinalSaveWithoutOverriding() {
        def classes = ['example/Main.class': fixture('example/Main', 'example/Base', -1),
                       'example/Base.class': fixture('example/Base', 'android/app/Activity', ACC_PROTECTED | ACC_FINAL)]
        PayloadStateEnvelope.adapt(classes, 'example/Main')
        assertTrue(events(classes['example/Main.class']).isEmpty())
        assertEquals(['user', 'protect', 'return'], events(classes['example/Base.class']))
    }

    @Test void generatesSaveAfterPlatformSuperAndLeavesUnrelatedTypesAlone() {
        def classes = ['example/Main.class': fixture('example/Main', 'android/app/Activity', -1),
                       'example/Other.class': fixture('example/Other', 'android/app/Activity', ACC_PROTECTED)]
        byte[] other = classes['example/Other.class'].clone()
        PayloadStateEnvelope.adapt(classes, 'example/Main')
        assertEquals(['super', 'protect', 'return'], events(classes['example/Main.class']))
        assertArrayEquals(other, classes['example/Other.class'])
    }

    @Test void coversPersistableAndSynthesizedNormalCallbacks() {
        def classes = ['example/Main.class': fixture('example/Main', 'android/app/Activity', ACC_PUBLIC,
            '(Landroid/os/Bundle;Landroid/os/PersistableBundle;)V')]
        PayloadStateEnvelope.adapt(classes, 'example/Main')
        assertEquals(2, events(classes['example/Main.class']).count { it == 'protect' })
    }

    private static byte[] fixture(String name, String parent, int access, String desc = PayloadStateEnvelope.SAVE, boolean branches = false) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(V11, ACC_PUBLIC, name, null, parent, null)
        if (access != -1) {
            MethodVisitor method = writer.visitMethod(access, 'onSaveInstanceState', desc, null, null)
            method.visitCode()
            method.visitMethodInsn(INVOKESTATIC, 'example/Probe', 'user', '()V', false)
            method.visitInsn(RETURN)
            if (branches) { method.visitInsn(RETURN); method.visitInsn(ACONST_NULL); method.visitInsn(ATHROW) }
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
    private static List<String> events(byte[] bytes) {
        List<String> result = []
        new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(ASM9) {
                    @Override void visitMethodInsn(int opcode, String owner, String target, String descriptor, boolean isInterface) {
                        result.add(target == 'onSaveInstanceState' ? 'super' : target)
                    }
                    @Override void visitInsn(int opcode) {
                        if (opcode == RETURN) result.add('return')
                        if (opcode == ATHROW) result.add('throw')
                    }
                }
            }
        }, 0)
        return result
    }
}
