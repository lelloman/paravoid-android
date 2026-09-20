package com.lelloman.paravoidandroid.work.gradle

import com.lelloman.paravoidandroid.gradle.PayloadTransformer
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.objectweb.asm.*

abstract class WorkPayloadTransformer implements PayloadTransformer {
    static final String IMPL = 'androidx/work/impl/WorkManagerImpl'
    static final String PROVIDER = 'androidx/work/Configuration$Provider'
    static final String GET_INSTANCE = '(Landroid/content/Context;)Landroidx/work/impl/WorkManagerImpl;'
    @Input abstract MapProperty<String, String> getRuntimeVersions()
    @Override void transform(Map<String, byte[]> classes) {
        WorkVersions.validate(runtimeVersions.get())
        adapt(classes)
    }
    static void adapt(Map<String, byte[]> classes) {
        if (classes.containsKey(WorkLookupGenerator.NAME + '.class')) throw new GradleException('paravoid-work bridge class collision')
        byte[] bytes = classes[IMPL + '.class']
        if (bytes == null) throw new GradleException("paravoid-work missing required class: ${IMPL}")
        int[] edits = [0, 0] as int[]
        ClassWriter writer = new ClassWriter(0)
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, desc, signature, exceptions)
                if (name != 'getInstance' || desc != GET_INSTANCE) return next
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override void visitTypeInsn(int opcode, String type) {
                        if (type == PROVIDER && opcode in [Opcodes.INSTANCEOF, Opcodes.CHECKCAST]) {
                            // Substitute only the configuration-owner operand. The appContext
                            // local passed to initialize remains the real Android Application.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, WorkLookupGenerator.NAME,
                                'owner', '(Landroid/content/Context;)Ljava/lang/Object;', false)
                            edits[opcode == Opcodes.INSTANCEOF ? 0 : 1]++
                        }
                        super.visitTypeInsn(opcode, type)
                    }
                }
            }
        }, 0)
        if (edits[0] != 1 || edits[1] != 1) throw new GradleException("Unsupported WorkManager bytecode: expected one provider check and cast, found ${edits.toList()}")
        classes[IMPL + '.class'] = writer.toByteArray()
        classes[WorkLookupGenerator.NAME + '.class'] = WorkLookupGenerator.generate()
    }
}
