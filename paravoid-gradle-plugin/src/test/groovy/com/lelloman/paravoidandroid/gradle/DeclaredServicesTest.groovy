package com.lelloman.paravoidandroid.gradle

import org.junit.Test
import org.objectweb.asm.*
import static org.junit.Assert.*

class DeclaredServicesTest {
    private static byte[] type(String name, String parent) {
        def writer = new ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, parent, null)
        writer.visitEnd(); writer.toByteArray()
    }
    @Test void mapsIndirectJobsAndChangesInstalledBytesWithServiceKind() {
        Map<String, byte[]> classes = [
            'example/Base.class': type('example/Base', 'android/app/job/JobService'),
            'example/Job.class': type('example/Job', 'example/Base'),
            'example/Service.class': type('example/Service', 'android/app/Service')]
        byte[] bytes = DeclaredServices.generate(classes, ['example.Job', 'example.Service'])
        def loader = new GroovyClassLoader(getClass().classLoader)
        Class dispatch = loader.defineClass(DeclaredServices.NAME.replace('/', '.'), bytes)
        assertTrue(dispatch.getMethod('isJobService', String).invoke(null, 'example.Job'))
        assertFalse(dispatch.getMethod('isJobService', String).invoke(null, 'example.Service'))
        classes['example/Base.class'] = type('example/Base', 'android/app/Service')
        assertFalse(Arrays.equals(bytes, DeclaredServices.generate(classes, ['example.Job', 'example.Service'])))
    }
}
