package com.lelloman.paravoidandroid.hilt.gradle

import com.lelloman.paravoidandroid.gradle.PayloadTransformer
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.objectweb.asm.*

/** Version-checked payload adaptation, never applied to normal packaging. */
abstract class HiltPayloadTransformer implements PayloadTransformer {
    @Input abstract MapProperty<String, String> getRuntimeVersions()
    @Override void transform(Map<String, byte[]> classes) {
        HiltVersions.validate(runtimeVersions.get())
        adapt(classes)
    }
    static final String BRIDGE = HiltLookupGenerator.NAME
    static final String MANAGER = 'dagger/hilt/android/internal/managers/ActivityComponentManager'
    static final String ACCESSORS = 'dagger/hilt/android/EntryPointAccessors'
    static final String CONTEXT_MODULE = 'dagger/hilt/android/internal/modules/ApplicationContextModule'

    static void adapt(Map<String, byte[]> classes) {
        if (classes.containsKey(BRIDGE + '.class')) throw new GradleException("paravoid-hilt bridge class collision: ${BRIDGE}")
        [(MANAGER): 3, (ACCESSORS): 1, (CONTEXT_MODULE): 1].each { String owner, int expected ->
            byte[] bytes = classes.get(owner + '.class')
            if (bytes == null) throw new GradleException("paravoid-hilt missing required class: ${owner}")
            ClassWriter writer = new ClassWriter(0)
            int[] edits = [0] as int[]
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    MethodVisitor downstream = super.visitMethod(access, name, desc, signature, exceptions)
                    return new MethodVisitor(Opcodes.ASM9, downstream) {
                        @Override void visitMethodInsn(int opcode, String target, String method, String descriptor, boolean isInterface) {
                            if (owner == MANAGER && name == 'createComponent' && desc == '()Ljava/lang/Object;' &&
                                opcode == Opcodes.INVOKEVIRTUAL && target == 'android/app/Activity' &&
                                method == 'getApplication' && descriptor == '()Landroid/app/Application;') {
                                // Only component ownership checks/diagnostics, never ordinary app calls.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, 'manager', '(Landroid/content/Context;)Ljava/lang/Object;', false)
                                edits[0]++
                            } else if (owner == ACCESSORS && name == 'fromApplication' &&
                                desc == '(Landroid/content/Context;Ljava/lang/Class;)Ljava/lang/Object;' &&
                                opcode == Opcodes.INVOKESTATIC && target == 'dagger/hilt/android/internal/Contexts' &&
                                method == 'getApplication' && descriptor == '(Landroid/content/Context;)Landroid/app/Application;') {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, 'manager', '(Landroid/content/Context;)Ljava/lang/Object;', false)
                                edits[0]++
                            } else {
                                super.visitMethodInsn(opcode, target, method, descriptor, isInterface)
                            }
                        }

                        @Override void visitFieldInsn(int opcode, String target, String field, String descriptor) {
                            if (owner == CONTEXT_MODULE && name == '<init>' && desc == '(Landroid/content/Context;)V' &&
                                opcode == Opcodes.PUTFIELD && target == CONTEXT_MODULE && field == 'applicationContext' &&
                                descriptor == 'Landroid/content/Context;') {
                                // Bind Android Context/Application to the real shell, not the initializer wrapper.
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'android/content/Context', 'getApplicationContext', '()Landroid/content/Context;', false)
                                edits[0]++
                            }
                            super.visitFieldInsn(opcode, target, field, descriptor)
                        }
                    }
                }
            }, 0)
            if (edits[0] != expected) throw new GradleException("Unsupported Hilt bytecode in ${owner}: expected ${expected} edits, found ${edits[0]}")
            classes.put(owner + '.class', writer.toByteArray())
        }
        classes.put(BRIDGE + '.class', HiltLookupGenerator.generate())
    }
}
