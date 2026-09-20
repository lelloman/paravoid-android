package com.lelloman.paravoidandroid.gradle

import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class PayloadConfigurationContextTest {
    private static final String DESC = '(Landroid/content/res/Configuration;)Landroid/content/Context;'

    @Test void delegatesToSuperclassThenWrapsWithActivityLoaderExactlyOnce() {
        Map<String, byte[]> classes = ['example/Screen.class': type('example/Screen', 'example/Base', false),
                                      'example/Base.class': type('example/Base', 'android/app/Activity', false)]
        PayloadConfigurationContext.adapt(classes, 'example/Screen')
        List<List> calls = []
        new ClassReader(classes['example/Screen.class']).accept(new ClassVisitor(ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                assertEquals('createConfigurationContext', name)
                assertEquals(DESC, desc)
                return new MethodVisitor(ASM9) {
                    @Override void visitMethodInsn(int opcode, String owner, String target, String descriptor, boolean isInterface) {
                        calls.add([opcode, owner, target, descriptor])
                    }
                }
            }
        }, 0)
        assertEquals([
            [INVOKESPECIAL, 'example/Base', 'createConfigurationContext', DESC],
            [INVOKEVIRTUAL, 'example/Screen', 'getClassLoader', '()Ljava/lang/ClassLoader;'],
            [INVOKESTATIC, 'com/lelloman/paravoidandroid/runtime/PayloadContext', 'wrap',
             '(Landroid/content/Context;Ljava/lang/ClassLoader;)Landroid/content/Context;']
        ], calls)
        byte[] once = classes['example/Screen.class'].clone()
        PayloadConfigurationContext.adapt(classes, 'example/Screen')
        assertArrayEquals(once, classes['example/Screen.class'])
    }

    @Test void preservesDirectOverride() {
        Map<String, byte[]> classes = ['example/Screen.class': type('example/Screen', 'android/app/Activity', true)]
        byte[] before = classes['example/Screen.class'].clone()
        PayloadConfigurationContext.adapt(classes, 'example/Screen')
        assertArrayEquals(before, classes['example/Screen.class'])
    }

    @Test void preservesInheritedFinalOverride() {
        Map<String, byte[]> classes = ['example/Screen.class': type('example/Screen', 'example/Base', false),
                                      'example/Base.class': type('example/Base', 'android/app/Activity', true)]
        byte[] screen = classes['example/Screen.class'].clone()
        byte[] base = classes['example/Base.class'].clone()
        PayloadConfigurationContext.adapt(classes, 'example/Screen')
        assertArrayEquals(screen, classes['example/Screen.class'])
        assertArrayEquals(base, classes['example/Base.class'])
    }

    private static byte[] type(String name, String parent, boolean override) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V11, ACC_PUBLIC, name, null, parent, null)
        if (override) {
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, 'createConfigurationContext', DESC, null, null)
            method.visitCode()
            method.visitVarInsn(ALOAD, 0)
            method.visitInsn(ARETURN)
            method.visitMaxs(1, 2)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
