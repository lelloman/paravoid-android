package com.lelloman.paravoidandroid.gradle

import org.objectweb.asm.*
import static org.objectweb.asm.Opcodes.*

/** ActivityThread assigns the installed loader to state; repair it before payload onCreate. */
class PayloadSavedState {
    static final String HELPER = 'com/lelloman/paravoidandroid/runtime/PayloadSavedState'
    static final String CREATE = '(Landroid/os/Bundle;)V'

    static void adapt(Map<String, byte[]> classes, String activity) {
        Set<String> remaining = [CREATE, '(Landroid/os/Bundle;Landroid/os/PersistableBundle;)V'] as Set
        Set<String> visited = [] as Set
        String parent = activity
        while (parent != null && visited.add(parent) && classes.containsKey(parent + '.class')) {
            ClassReader reader = new ClassReader(classes[parent + '.class'])
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
            reader.accept(new ClassVisitor(ASM9, writer) {
                @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions)
                    if (name != 'onCreate' || !remaining.remove(desc)) return target
                    return new MethodVisitor(ASM9, target) {
                        @Override void visitCode() {
                            super.visitCode()
                            prepare(this)
                        }
                    }
                }
            }, 0)
            classes[parent + '.class'] = writer.toByteArray()
            parent = reader.superName
        }
        if (remaining.contains(CREATE)) {
            ClassReader reader = new ClassReader(classes[activity + '.class'])
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
            reader.accept(new ClassVisitor(ASM9, writer) {
                @Override void visitEnd() {
                    MethodVisitor method = super.visitMethod(ACC_PROTECTED, 'onCreate', CREATE, null, null)
                    method.visitCode()
                    prepare(method)
                    method.visitVarInsn(ALOAD, 0)
                    method.visitVarInsn(ALOAD, 1)
                    method.visitMethodInsn(INVOKESPECIAL, reader.superName, 'onCreate', CREATE, false)
                    method.visitInsn(RETURN)
                    method.visitMaxs(0, 0)
                    method.visitEnd()
                    super.visitEnd()
                }
            }, 0)
            classes[activity + '.class'] = writer.toByteArray()
        }
    }

    private static void prepare(MethodVisitor method) {
        method.visitVarInsn(ALOAD, 0)
        method.visitVarInsn(ALOAD, 1)
        method.visitMethodInsn(INVOKESTATIC, HELPER, 'prepare', '(Landroid/app/Activity;Landroid/os/Bundle;)V', false)
    }
}
