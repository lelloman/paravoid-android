package com.lelloman.paravoidandroid.work.gradle

import org.objectweb.asm.*
import static org.objectweb.asm.Opcodes.*

class WorkLookupGenerator {
    static final String NAME = 'com/lelloman/paravoidandroid/work/internal/WorkLookup'
    static byte[] generate() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
        writer.visit(V11, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, NAME, null, 'java/lang/Object', null)
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, 'owner', '(Landroid/content/Context;)Ljava/lang/Object;', null, null)
        method.visitCode()
        method.visitVarInsn(ALOAD, 0)
        method.visitTypeInsn(INSTANCEOF, 'com/lelloman/paravoidandroid/runtime/ShellApplication')
        Label ordinary = new Label()
        method.visitJumpInsn(IFEQ, ordinary)
        method.visitVarInsn(ALOAD, 0)
        method.visitTypeInsn(CHECKCAST, 'com/lelloman/paravoidandroid/runtime/ShellApplication')
        method.visitMethodInsn(INVOKEVIRTUAL, 'com/lelloman/paravoidandroid/runtime/ShellApplication', 'requirePayloadApplication', '()Lcom/lelloman/paravoidandroid/runtime/PayloadApplication;', false)
        method.visitInsn(ARETURN)
        method.visitLabel(ordinary)
        method.visitVarInsn(ALOAD, 0)
        method.visitInsn(ARETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
