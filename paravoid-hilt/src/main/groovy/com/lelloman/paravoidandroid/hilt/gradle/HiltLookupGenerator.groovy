package com.lelloman.paravoidandroid.hilt.gradle

import org.objectweb.asm.*
import static org.objectweb.asm.Opcodes.*

/** Generated directly into the payload: no Android library or app-owned bridge needed. */
class HiltLookupGenerator {
    static final String NAME = 'com/lelloman/paravoidandroid/hilt/internal/HiltLookup'

    static byte[] generate() {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V11, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, NAME, null, 'java/lang/Object', null)
        MethodVisitor constructor = writer.visitMethod(ACC_PRIVATE, '<init>', '()V', null, null)
        constructor.visitCode()
        constructor.visitVarInsn(ALOAD, 0)
        constructor.visitMethodInsn(INVOKESPECIAL, 'java/lang/Object', '<init>', '()V', false)
        constructor.visitInsn(RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()
        // Only adapted shell bytecode calls this method. Preserve real Context access;
        // return the attached payload object solely for Hilt component-owner lookup.
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, 'manager', '(Landroid/content/Context;)Ljava/lang/Object;', null, null)
        method.visitCode()
        method.visitVarInsn(ALOAD, 0)
        method.visitMethodInsn(INVOKEVIRTUAL, 'android/content/Context', 'getApplicationContext', '()Landroid/content/Context;', false)
        method.visitTypeInsn(CHECKCAST, 'com/lelloman/paravoidandroid/runtime/ShellApplication')
        method.visitMethodInsn(INVOKEVIRTUAL, 'com/lelloman/paravoidandroid/runtime/ShellApplication', 'requirePayloadApplication', '()Lcom/lelloman/paravoidandroid/runtime/PayloadApplication;', false)
        method.visitInsn(ARETURN)
        method.visitMaxs(1, 1)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
