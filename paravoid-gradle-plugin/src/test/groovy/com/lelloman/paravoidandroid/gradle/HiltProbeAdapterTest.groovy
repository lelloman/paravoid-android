package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException
import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class HiltProbeAdapterTest {
    @Test void rewritesOnlyComponentLookupsAndContextBinding() {
        Map<String, byte[]> classes = fixture(3)
        byte[] ordinary = classes['example/Ordinary.class'].clone()
        HiltProbeAdapter.adapt(classes)
        assertArrayEquals(ordinary, classes['example/Ordinary.class'])
        assertEquals(3, calls(classes[HiltProbeAdapter.MANAGER + '.class']).count { it.contains('HiltLookup.manager') })
        assertEquals(1, calls(classes[HiltProbeAdapter.MANAGER + '.class']).count { it.contains('Activity.getApplication') })
        assertEquals(1, calls(classes[HiltProbeAdapter.ACCESSORS + '.class']).count { it.contains('HiltLookup.manager') })
        def bindings = calls(classes[HiltProbeAdapter.CONTEXT_MODULE + '.class'])
        assertTrue(bindings.any { it.contains('Context.getApplicationContext') })
        assertTrue(bindings.any { it.contains('Contexts.getApplication') })
    }

    @Test void failsClosedWhenTheExpectedHiltBytecodeChanges() {
        GradleException error = assertThrows(GradleException, { HiltProbeAdapter.adapt(fixture(2)) })
        assertTrue(error.message.contains('expected 3 probe edits, found 2'))
    }

    @Test void requiresThePayloadBridge() {
        Map<String, byte[]> classes = fixture(3)
        classes.remove(HiltProbeAdapter.BRIDGE + '.class')
        assertThrows(GradleException, { HiltProbeAdapter.adapt(classes) })
    }

    private static Map<String, byte[]> fixture(int calls) {
        Map<String, byte[]> classes = [:]
        classes[HiltProbeAdapter.BRIDGE + '.class'] = new byte[0] // Presence only; bridge is compiled in the device fixture.
        ClassWriter manager = writer(HiltProbeAdapter.MANAGER)
        method(manager, 'createComponent', '()Ljava/lang/Object;') { mv ->
            calls.times { applicationCall(mv) }
            mv.visitInsn(ACONST_NULL)
            mv.visitInsn(ARETURN)
        }
        // Same invocation outside createComponent must not be rewritten.
        method(manager, 'ordinary', '()V') { mv -> applicationCall(mv); mv.visitInsn(RETURN) }
        classes[HiltProbeAdapter.MANAGER + '.class'] = manager.toByteArray()
        classes['example/Ordinary.class'] = manager.toByteArray()
        ClassWriter accessors = writer(HiltProbeAdapter.ACCESSORS)
        method(accessors, 'fromApplication', '(Landroid/content/Context;Ljava/lang/Class;)Ljava/lang/Object;') { mv ->
            mv.visitInsn(ACONST_NULL)
            mv.visitMethodInsn(INVOKESTATIC, 'dagger/hilt/android/internal/Contexts', 'getApplication', '(Landroid/content/Context;)Landroid/app/Application;', false)
            mv.visitInsn(ARETURN)
        }
        classes[HiltProbeAdapter.ACCESSORS + '.class'] = accessors.toByteArray()
        ClassWriter module = writer(HiltProbeAdapter.CONTEXT_MODULE)
        method(module, '<init>', '(Landroid/content/Context;)V') { mv ->
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESPECIAL, 'java/lang/Object', '<init>', '()V', false)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitVarInsn(ALOAD, 1)
            mv.visitFieldInsn(PUTFIELD, HiltProbeAdapter.CONTEXT_MODULE, 'applicationContext', 'Landroid/content/Context;')
            mv.visitInsn(RETURN)
        }
        method(module, 'provideApplication', '()Landroid/app/Application;') { mv ->
            mv.visitInsn(ACONST_NULL)
            mv.visitMethodInsn(INVOKESTATIC, 'dagger/hilt/android/internal/Contexts', 'getApplication', '(Landroid/content/Context;)Landroid/app/Application;', false)
            mv.visitInsn(ARETURN)
        }
        classes[HiltProbeAdapter.CONTEXT_MODULE + '.class'] = module.toByteArray()
        return classes
    }

    private static ClassWriter writer(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(V11, ACC_PUBLIC, name, null, 'java/lang/Object', null)
        return writer
    }
    private static void method(ClassWriter writer, String name, String desc, Closure body) {
        MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, name, desc, null, null)
        mv.visitCode()
        body(mv)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }
    private static void applicationCall(MethodVisitor mv) {
        mv.visitInsn(ACONST_NULL)
        mv.visitMethodInsn(INVOKEVIRTUAL, 'android/app/Activity', 'getApplication', '()Landroid/app/Application;', false)
        mv.visitInsn(POP)
    }
    private static List<String> calls(byte[] bytes) {
        List<String> result = []
        new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(ASM9) {
                    @Override void visitMethodInsn(int opcode, String owner, String target, String descriptor, boolean isInterface) {
                        result.add(owner + '.' + target + descriptor)
                    }
                }
            }
        }, 0)
        return result
    }
}
