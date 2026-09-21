package com.lelloman.paravoidandroid.gradle

import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class PayloadSavedStateTest {
    @Test void sharedBaseCallbackIsPatchedOnceAcrossActivities() {
        def classes = ['example/First.class': fixture('example/First', 'example/Base', -1),
                       'example/Second.class': fixture('example/Second', 'example/Base', -1),
                       'example/Base.class': fixture('example/Base', 'android/app/Activity', ACC_PROTECTED | ACC_FINAL)]
        Set<String> patched = new HashSet<>()
        ['example/First', 'example/Second', 'example/Base'].each { PayloadSavedState.adapt(classes, it, patched) }
        assertEquals(1, calls(classes['example/Base.class']).count { it == PayloadSavedState.HELPER + '.prepare' })
    }
    @Test void preparesStateBeforeExistingOnCreateCode() {
        def classes = ['example/Main.class': fixture('example/Main', 'android/app/Activity', ACC_PROTECTED)]
        PayloadSavedState.adapt(classes, 'example/Main')
        assertEquals([PayloadSavedState.HELPER + '.prepare', 'android/app/Activity.onCreate'], calls(classes['example/Main.class']))
    }

    @Test void adaptsInheritedFinalCallbackWithoutOverridingIt() {
        def classes = ['example/Main.class': fixture('example/Main', 'example/Base', -1),
                       'example/Base.class': fixture('example/Base', 'android/app/Activity', ACC_PROTECTED | ACC_FINAL)]
        PayloadSavedState.adapt(classes, 'example/Main')
        assertTrue(calls(classes['example/Main.class']).isEmpty())
        assertEquals([PayloadSavedState.HELPER + '.prepare', 'android/app/Activity.onCreate'], calls(classes['example/Base.class']))
    }

    @Test void generatesCallbackWhenOnlyPlatformDeclaresIt() {
        def classes = ['example/Main.class': fixture('example/Main', 'android/app/Activity', -1)]
        PayloadSavedState.adapt(classes, 'example/Main')
        assertEquals([PayloadSavedState.HELPER + '.prepare', 'android/app/Activity.onCreate'], calls(classes['example/Main.class']))
    }

    @Test void alsoPreparesPersistableCallbackAndLeavesOtherClassesAlone() {
        def classes = ['example/Main.class': fixture('example/Main', 'android/app/Activity', ACC_PUBLIC,
            '(Landroid/os/Bundle;Landroid/os/PersistableBundle;)V'),
            'example/Other.class': fixture('example/Other', 'android/app/Activity', ACC_PROTECTED)]
        byte[] other = classes['example/Other.class'].clone()
        PayloadSavedState.adapt(classes, 'example/Main')
        assertEquals(2, calls(classes['example/Main.class']).count { it == PayloadSavedState.HELPER + '.prepare' })
        assertArrayEquals(other, classes['example/Other.class'])
    }

    private static byte[] fixture(String name, String parent, int access, String desc = PayloadSavedState.CREATE) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(V11, ACC_PUBLIC, name, null, parent, null)
        if (access != -1) {
            MethodVisitor method = writer.visitMethod(access, 'onCreate', desc, null, null)
            method.visitCode()
            method.visitVarInsn(ALOAD, 0)
            method.visitVarInsn(ALOAD, 1)
            if (desc.contains('Persistable')) method.visitVarInsn(ALOAD, 2)
            method.visitMethodInsn(INVOKESPECIAL, parent, 'onCreate', desc, false)
            method.visitInsn(RETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static List<String> calls(byte[] bytes) {
        List<String> result = []
        new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(ASM9) {
                    @Override void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                        result.add(owner + '.' + method)
                    }
                }
            }
        }, 0)
        return result
    }
}
