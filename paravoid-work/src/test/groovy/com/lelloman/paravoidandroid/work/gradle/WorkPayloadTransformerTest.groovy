package com.lelloman.paravoidandroid.work.gradle

import org.gradle.api.GradleException
import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class WorkPayloadTransformerTest {
    @Test void changesOnlyProviderOperandsInExactLookupMethod() {
        byte[] original = fixture(true)
        Map classes = [(WorkPayloadTransformer.IMPL + '.class'): original, 'example/Untouched.class': original]
        WorkPayloadTransformer.adapt(classes)
        assertSame(original, classes['example/Untouched.class'])
        Map<String, List<String>> calls = [:].withDefault { [] }
        new ClassReader(classes[WorkPayloadTransformer.IMPL + '.class']).accept(new ClassVisitor(ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(ASM9) {
                    @Override void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                        calls[name].add(owner + '.' + method + descriptor)
                    }
                }
            }
        }, 0)
        String bridge = WorkLookupGenerator.NAME + '.owner(Landroid/content/Context;)Ljava/lang/Object;'
        assertEquals(2, calls.getInstance.count(bridge))
        assertFalse(calls.other.contains(bridge))
        assertTrue(calls.getInstance.contains('android/content/Context.getApplicationContext()Landroid/content/Context;'))
        assertTrue(calls.getInstance.contains(WorkPayloadTransformer.IMPL + '.initialize(Landroid/content/Context;Landroidx/work/Configuration;)V'))
        assertNotNull(classes[WorkLookupGenerator.NAME + '.class'])
    }

    @Test void rejectsMissingClassAndBridgeCollision() {
        assertThrows(GradleException, { WorkPayloadTransformer.adapt([:]) })
        assertThrows(GradleException, { WorkPayloadTransformer.adapt([(WorkLookupGenerator.NAME + '.class'): new byte[0]]) })
    }

    @Test void rejectsChangedBytecodeBeforeMutatingPayload() {
        byte[] original = fixture(false)
        Map classes = [(WorkPayloadTransformer.IMPL + '.class'): original]
        def error = assertThrows(GradleException, { WorkPayloadTransformer.adapt(classes) })
        assertTrue(error.message.contains('expected one provider check and cast'))
        assertSame(original, classes[WorkPayloadTransformer.IMPL + '.class'])
        assertEquals(1, classes.size())
    }

    private static byte[] fixture(boolean includeCast) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(V11, ACC_PUBLIC, WorkPayloadTransformer.IMPL, null, 'java/lang/Object', null)
        ['getInstance', 'other'].each { name ->
            def method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, WorkPayloadTransformer.GET_INSTANCE, null, null)
            method.visitCode()
            method.visitVarInsn(ALOAD, 0)
            method.visitMethodInsn(INVOKEVIRTUAL, 'android/content/Context', 'getApplicationContext', '()Landroid/content/Context;', false)
            method.visitVarInsn(ASTORE, 1)
            method.visitVarInsn(ALOAD, 1)
            method.visitTypeInsn(INSTANCEOF, WorkPayloadTransformer.PROVIDER)
            method.visitInsn(POP)
            if (includeCast) {
                method.visitVarInsn(ALOAD, 1)
                method.visitTypeInsn(CHECKCAST, WorkPayloadTransformer.PROVIDER)
                method.visitInsn(POP)
            }
            method.visitVarInsn(ALOAD, 1)
            method.visitInsn(ACONST_NULL)
            method.visitMethodInsn(INVOKESTATIC, WorkPayloadTransformer.IMPL, 'initialize', '(Landroid/content/Context;Landroidx/work/Configuration;)V', false)
            method.visitInsn(ACONST_NULL)
            method.visitInsn(ARETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
