package com.lelloman.paravoidandroid.hilt.gradle

import org.gradle.api.GradleException
import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class HiltPayloadTransformerTest {
    @Test void rewritesOnlyComponentLookupsAndContextBinding() {
        Map<String, byte[]> classes = fixture(3)
        byte[] ordinary = classes['example/Ordinary.class'].clone()
        HiltPayloadTransformer.adapt(classes)
        assertArrayEquals(ordinary, classes['example/Ordinary.class'])
        assertEquals(3, calls(classes[HiltPayloadTransformer.MANAGER + '.class']).count { it.contains('HiltLookup.manager') })
        assertEquals(1, calls(classes[HiltPayloadTransformer.MANAGER + '.class']).count { it.contains('Activity.getApplication') })
        assertEquals(1, calls(classes[HiltPayloadTransformer.ACCESSORS + '.class']).count { it.contains('HiltLookup.manager') })
        assertEquals(1, calls(classes[HiltPayloadTransformer.SERVICE_MANAGER + '.class']).count { it.contains('HiltLookup.manager') })
        assertEquals(1, calls(classes[HiltPayloadTransformer.SERVICE_MANAGER + '.class']).count { it.contains('Service.getApplication') })
        assertEquals(1, calls(classes[HiltPayloadTransformer.RECEIVER_MANAGER + '.class']).count { it.contains('HiltLookup.manager') })
        assertEquals(1, calls(classes[HiltPayloadTransformer.RECEIVER_MANAGER + '.class']).count { it.contains('Contexts.getApplication') })
        def bindings = calls(classes[HiltPayloadTransformer.CONTEXT_MODULE + '.class'])
        assertTrue(bindings.any { it.contains('Context.getApplicationContext') })
        assertTrue(bindings.any { it.contains('Contexts.getApplication') })
        byte[] bridge = classes[HiltPayloadTransformer.BRIDGE + '.class']
        assertEquals(HiltLookupGenerator.NAME, new ClassReader(bridge).className)
        assertTrue(calls(bridge).any { it.contains('ShellApplication.requirePayloadApplication') })
    }

    @Test void failsClosedWhenTheExpectedHiltBytecodeChanges() {
        GradleException error = assertThrows(GradleException, { HiltPayloadTransformer.adapt(fixture(2)) })
        assertTrue(error.message.contains('expected 3 edits, found 2'))
    }

    @Test void rejectsBridgeNameCollisions() {
        Map<String, byte[]> classes = fixture(3)
        classes[HiltPayloadTransformer.BRIDGE + '.class'] = new byte[0]
        assertThrows(GradleException, { HiltPayloadTransformer.adapt(classes) })
    }

    @Test void rejectsMissingOrChangedComponentManagers() {
        [HiltPayloadTransformer.SERVICE_MANAGER, HiltPayloadTransformer.RECEIVER_MANAGER].each { owner ->
            def missing = fixture(3)
            missing.remove(owner + '.class')
            assertTrue(assertThrows(GradleException, { HiltPayloadTransformer.adapt(missing) }).message.contains("missing required class: ${owner}"))
            def changed = fixture(3)
            changed[owner + '.class'] = writer(owner).toByteArray()
            def error = assertThrows(GradleException, { HiltPayloadTransformer.adapt(changed) })
            assertTrue(error.message.contains(owner))
            assertTrue(error.message.contains('expected 1 edits, found 0'))
        }
    }

    private static Map<String, byte[]> fixture(int calls) {
        Map<String, byte[]> classes = [:]
        ClassWriter manager = writer(HiltPayloadTransformer.MANAGER)
        method(manager, 'createComponent', '()Ljava/lang/Object;') { mv ->
            calls.times { applicationCall(mv) }
            mv.visitInsn(ACONST_NULL)
            mv.visitInsn(ARETURN)
        }
        // Same invocation outside createComponent must not be rewritten.
        method(manager, 'ordinary', '()V') { mv -> applicationCall(mv); mv.visitInsn(RETURN) }
        classes[HiltPayloadTransformer.MANAGER + '.class'] = manager.toByteArray()
        classes['example/Ordinary.class'] = manager.toByteArray()
        ClassWriter service = writer(HiltPayloadTransformer.SERVICE_MANAGER)
        method(service, 'createComponent', '()Ljava/lang/Object;') { mv ->
            mv.visitInsn(ACONST_NULL)
            mv.visitMethodInsn(INVOKEVIRTUAL, 'android/app/Service', 'getApplication', '()Landroid/app/Application;', false)
            mv.visitInsn(ARETURN)
        }
        method(service, 'ordinary', '()Landroid/app/Application;') { mv ->
            mv.visitInsn(ACONST_NULL)
            mv.visitMethodInsn(INVOKEVIRTUAL, 'android/app/Service', 'getApplication', '()Landroid/app/Application;', false)
            mv.visitInsn(ARETURN)
        }
        classes[HiltPayloadTransformer.SERVICE_MANAGER + '.class'] = service.toByteArray()
        ClassWriter receiver = writer(HiltPayloadTransformer.RECEIVER_MANAGER)
        ['generatedComponent', 'ordinary'].each { name ->
            method(receiver, name, '(Landroid/content/Context;)Ljava/lang/Object;') { mv ->
                mv.visitInsn(ACONST_NULL)
                mv.visitMethodInsn(INVOKESTATIC, 'dagger/hilt/android/internal/Contexts', 'getApplication', '(Landroid/content/Context;)Landroid/app/Application;', false)
                mv.visitInsn(ARETURN)
            }
        }
        classes[HiltPayloadTransformer.RECEIVER_MANAGER + '.class'] = receiver.toByteArray()
        ClassWriter accessors = writer(HiltPayloadTransformer.ACCESSORS)
        method(accessors, 'fromApplication', '(Landroid/content/Context;Ljava/lang/Class;)Ljava/lang/Object;') { mv ->
            mv.visitInsn(ACONST_NULL)
            mv.visitMethodInsn(INVOKESTATIC, 'dagger/hilt/android/internal/Contexts', 'getApplication', '(Landroid/content/Context;)Landroid/app/Application;', false)
            mv.visitInsn(ARETURN)
        }
        classes[HiltPayloadTransformer.ACCESSORS + '.class'] = accessors.toByteArray()
        ClassWriter module = writer(HiltPayloadTransformer.CONTEXT_MODULE)
        method(module, '<init>', '(Landroid/content/Context;)V') { mv ->
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESPECIAL, 'java/lang/Object', '<init>', '()V', false)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitVarInsn(ALOAD, 1)
            mv.visitFieldInsn(PUTFIELD, HiltPayloadTransformer.CONTEXT_MODULE, 'applicationContext', 'Landroid/content/Context;')
            mv.visitInsn(RETURN)
        }
        method(module, 'provideApplication', '()Landroid/app/Application;') { mv ->
            mv.visitInsn(ACONST_NULL)
            mv.visitMethodInsn(INVOKESTATIC, 'dagger/hilt/android/internal/Contexts', 'getApplication', '(Landroid/content/Context;)Landroid/app/Application;', false)
            mv.visitInsn(ARETURN)
        }
        classes[HiltPayloadTransformer.CONTEXT_MODULE + '.class'] = module.toByteArray()
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
