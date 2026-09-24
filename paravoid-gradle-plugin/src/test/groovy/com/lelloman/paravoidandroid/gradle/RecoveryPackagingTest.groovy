package com.lelloman.paravoidandroid.gradle

import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.gradle.api.GradleException
import org.objectweb.asm.*
import java.util.zip.*
import static org.junit.Assert.*

class RecoveryPackagingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()
    static byte[] provider(boolean publicConstructor=true, String reference=null) {
        def writer=new ClassWriter(0)
        writer.visit(Opcodes.V11,Opcodes.ACC_PUBLIC,'example/Provider',null,'java/lang/Object',
            ['com/lelloman/paravoidandroid/recovery/RecoveryUpdateProvider'] as String[])
        writer.visitMethod(publicConstructor ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE,'<init>','()V',null,null).visitEnd()
        if(reference) writer.visitField(Opcodes.ACC_PUBLIC,'dependency','L'+reference+';',null,null).visitEnd()
        writer.visitEnd(); writer.toByteArray()
    }
    File jar(Map<String,byte[]> entries) {
        File file=temporary.newFile(UUID.randomUUID().toString()+'.jar')
        new ZipOutputStream(new FileOutputStream(file)).withCloseable { zip ->
            entries.each { name,bytes -> zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }; file
    }
    void fails(String message, Closure operation) {
        try { operation.call(); fail('Expected rejection') }
        catch(GradleException e) { assertTrue(e.message,e.message.contains(message)) }
    }
    @Test void validatesProviderAndRejectsPayloadReferences() {
        File android=jar(['java/lang/Object.class':new byte[0]])
        def classes=['com/lelloman/paravoidandroid/recovery/RecoveryUpdateProvider.class':new byte[0]]
        def names=RecoveryPackaging.collect([jar(['example/Provider.class':provider()])],classes)
        RecoveryPackaging.validate(names,classes,android,'example.Provider')
        classes['example/Provider.class']=provider(true,'example/Payload')
        fails('non-shell class example/Payload') { RecoveryPackaging.validate(names,classes,android,'example.Provider') }
        classes['example/Provider.class']=provider(false)
        fails('public no-argument') { RecoveryPackaging.validate(names,classes,android,'example.Provider') }
        fails('must be supplied') { RecoveryPackaging.validate(names,classes,android,'example.Missing') }
    }
    @Test void rejectsResourcesAndConflictingClasses() {
        fails('runtime resources') { RecoveryPackaging.collect([jar(['lib/x86_64/libnative.so':new byte[0]])],[:]) }
        fails('Conflicting') { RecoveryPackaging.collect([jar(['example/Provider.class':provider()])],['example/Provider.class':new byte[0]]) }
    }
}
