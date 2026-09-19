package com.lelloman.paravoidandroid.gradle

import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class PayloadActivityLoaderTest {
    @Test void addsPayloadClassloaderDelegationOnce() {
        Map<String, byte[]> classes = ['example/Screen.class': type('example/Screen', 'android/app/Activity', false)]
        PayloadActivityLoader.adapt(classes, 'example/Screen')
        List<String> calls = []
        new ClassReader(classes['example/Screen.class']).accept(new ClassVisitor(ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                assertEquals('getClassLoader', name)
                return new MethodVisitor(ASM9) {
                    @Override void visitMethodInsn(int opcode, String owner, String target, String descriptor, boolean isInterface) {
                        calls.add(owner + '.' + target)
                    }
                }
            }
        }, 0)
        assertEquals(['java/lang/Object.getClass', 'java/lang/Class.getClassLoader'], calls)
        byte[] once = classes['example/Screen.class'].clone()
        PayloadActivityLoader.adapt(classes, 'example/Screen')
        assertArrayEquals(once, classes['example/Screen.class'])
    }

    @Test void preservesInheritedFinalOverride() {
        Map<String, byte[]> classes = [
            'example/Screen.class': type('example/Screen', 'example/Base', false),
            'example/Base.class': type('example/Base', 'android/app/Activity', true)]
        byte[] original = classes['example/Screen.class'].clone()
        PayloadActivityLoader.adapt(classes, 'example/Screen')
        assertArrayEquals(original, classes['example/Screen.class'])
    }

    private static byte[] type(String name, String parent, boolean override) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V11, ACC_PUBLIC, name, null, parent, null)
        if (override) {
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, 'getClassLoader', '()Ljava/lang/ClassLoader;', null, null)
            method.visitCode()
            method.visitInsn(ACONST_NULL)
            method.visitInsn(ARETURN)
            method.visitMaxs(1, 1)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
