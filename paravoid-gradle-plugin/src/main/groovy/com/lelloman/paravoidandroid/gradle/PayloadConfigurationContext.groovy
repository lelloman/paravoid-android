package com.lelloman.paravoidandroid.gradle

import org.objectweb.asm.*
import static org.objectweb.asm.Opcodes.*

/** Android's derived ContextImpl otherwise falls back to the installed shell loader. */
class PayloadConfigurationContext {
    private static final String METHOD = 'createConfigurationContext'
    private static final String DESC = '(Landroid/content/res/Configuration;)Landroid/content/Context;'

    static void adapt(Map<String, byte[]> classes, String activity) {
        // Explicit downstream overrides keep their semantics, including inherited final ones.
        String cursor = activity
        Set<String> visited = new HashSet<>()
        while (cursor != null && visited.add(cursor) && classes.containsKey(cursor + '.class')) {
            ClassReader reader = new ClassReader(classes[cursor + '.class'])
            boolean[] declared = [false] as boolean[]
            reader.accept(new ClassVisitor(ASM9) {
                @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (name == METHOD && desc == DESC) declared[0] = true
                    return null
                }
            }, ClassReader.SKIP_CODE)
            if (declared[0]) return
            cursor = reader.superName
        }
        ClassReader reader = new ClassReader(classes[activity + '.class'])
        ClassWriter writer = new ClassWriter(0)
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override void visitEnd() {
                MethodVisitor method = super.visitMethod(ACC_PUBLIC, METHOD, DESC, null, null)
                method.visitCode()
                method.visitVarInsn(ALOAD, 0)
                method.visitVarInsn(ALOAD, 1)
                method.visitMethodInsn(INVOKESPECIAL, reader.superName, METHOD, DESC, false)
                method.visitVarInsn(ALOAD, 0)
                method.visitMethodInsn(INVOKEVIRTUAL, activity, 'getClassLoader', '()Ljava/lang/ClassLoader;', false)
                method.visitMethodInsn(INVOKESTATIC, 'com/lelloman/paravoidandroid/runtime/PayloadContext', 'wrap',
                    '(Landroid/content/Context;Ljava/lang/ClassLoader;)Landroid/content/Context;', false)
                method.visitInsn(ARETURN)
                method.visitMaxs(2, 2)
                method.visitEnd()
                super.visitEnd()
            }
        }, 0)
        classes[activity + '.class'] = writer.toByteArray()
    }
}
