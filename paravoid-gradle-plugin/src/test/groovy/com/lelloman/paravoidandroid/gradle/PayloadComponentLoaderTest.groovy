package com.lelloman.paravoidandroid.gradle

import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class PayloadComponentLoaderTest {
    @Test void addsPayloadClassloaderDelegationOnce() {
        Map<String, byte[]> classes = ['example/Screen.class': type('example/Screen', 'android/app/Activity', false)]
        PayloadComponentLoader.adapt(classes, 'example/Screen')
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
        PayloadComponentLoader.adapt(classes, 'example/Screen')
        assertArrayEquals(once, classes['example/Screen.class'])
    }

    @Test void preservesInheritedFinalOverride() {
        Map<String, byte[]> classes = [
            'example/Screen.class': type('example/Screen', 'example/Base', false),
            'example/Base.class': type('example/Base', 'android/app/Activity', true)]
        byte[] original = classes['example/Screen.class'].clone()
        PayloadComponentLoader.adapt(classes, 'example/Screen')
        assertArrayEquals(original, classes['example/Screen.class'])
    }

    @Test void adaptsServiceWithoutChangingItsBaseOrUnrelatedTypes() {
        def classes = ['example/Job.class': type('example/Job', 'android/app/job/JobService', false),
                       'example/Other.class': type('example/Other', 'android/app/Service', false)]
        byte[] other = classes['example/Other.class'].clone()
        PayloadComponentLoader.adapt(classes, 'example/Job')
        assertEquals('android/app/job/JobService', new ClassReader(classes['example/Job.class']).superName)
        int count = 0
        new ClassReader(classes['example/Job.class']).accept(new ClassVisitor(ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (name == 'getClassLoader') { assertEquals('()Ljava/lang/ClassLoader;', desc); count++ }
                return null
            }
        }, 0)
        assertEquals(1, count)
        assertArrayEquals(other, classes['example/Other.class'])
    }

    @Test void preservesServiceInheritedAndDirectFinalOverrides() {
        def classes = ['example/Job.class': type('example/Job', 'example/Base', false),
                       'example/Base.class': type('example/Base', 'android/app/Service', true)]
        byte[] job = classes['example/Job.class'].clone()
        byte[] base = classes['example/Base.class'].clone()
        PayloadComponentLoader.adapt(classes, 'example/Job')
        PayloadComponentLoader.adapt(classes, 'example/Base')
        assertArrayEquals(job, classes['example/Job.class'])
        assertArrayEquals(base, classes['example/Base.class'])
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
