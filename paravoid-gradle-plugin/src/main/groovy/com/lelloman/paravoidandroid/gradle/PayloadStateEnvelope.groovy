package com.lelloman.paravoidandroid.gradle

import org.objectweb.asm.*
import static org.objectweb.asm.Opcodes.*

/** Android 9 eagerly reads root saved state before the Activity onCreate hook. */
class PayloadStateEnvelope {
    static final String SAVE = '(Landroid/os/Bundle;)V'
    static final String HELPER = 'com/lelloman/paravoidandroid/runtime/PayloadSavedState'

    static void adapt(Map<String, byte[]> classes, String activity, Set<String> patched = new HashSet<>()) {
        Set<String> remaining = [SAVE, '(Landroid/os/Bundle;Landroid/os/PersistableBundle;)V'] as Set
        Set<String> visited = [] as Set
        String parent = activity
        while (parent != null && visited.add(parent) && classes.containsKey(parent + '.class')) {
            ClassReader reader = new ClassReader(classes[parent + '.class'])
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
            reader.accept(new ClassVisitor(ASM9, writer) {
                @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions)
                    if (name != 'onSaveInstanceState' || !remaining.remove(desc)) return target
                    if (!patched.add(reader.className + '#' + name + desc)) return target
                    return new MethodVisitor(ASM9, target) {
                        @Override void visitInsn(int opcode) {
                            if (opcode == RETURN) protect(this)
                            super.visitInsn(opcode)
                        }
                    }
                }
            }, 0)
            classes[parent + '.class'] = writer.toByteArray()
            parent = reader.superName
        }
        if (remaining.contains(SAVE)) {
            patched.add(activity + '#onSaveInstanceState' + SAVE)
            ClassReader reader = new ClassReader(classes[activity + '.class'])
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
            reader.accept(new ClassVisitor(ASM9, writer) {
                @Override void visitEnd() {
                    MethodVisitor method = super.visitMethod(ACC_PROTECTED, 'onSaveInstanceState', SAVE, null, null)
                    method.visitCode()
                    method.visitVarInsn(ALOAD, 0)
                    method.visitVarInsn(ALOAD, 1)
                    method.visitMethodInsn(INVOKESPECIAL, reader.superName, 'onSaveInstanceState', SAVE, false)
                    protect(method)
                    method.visitInsn(RETURN)
                    method.visitMaxs(0, 0)
                    method.visitEnd()
                    super.visitEnd()
                }
            }, 0)
            classes[activity + '.class'] = writer.toByteArray()
        }
    }

    private static void protect(MethodVisitor method) {
        method.visitVarInsn(ALOAD, 0)
        method.visitVarInsn(ALOAD, 1)
        method.visitMethodInsn(INVOKESTATIC, HELPER, 'protect', '(Landroid/app/Activity;Landroid/os/Bundle;)V', false)
    }
}
