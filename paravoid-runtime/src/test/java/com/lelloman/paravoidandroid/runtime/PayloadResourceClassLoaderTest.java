package com.lelloman.paravoidandroid.runtime;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.jar.JarOutputStream;
import java.util.jar.JarEntry;
import static org.junit.Assert.*;

public class PayloadResourceClassLoaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void findsEncodedResourcePathsEnumeratesOnceAndDoesNotDefineClasses() throws Exception {
        File jar = temporary.newFile("archive #%.jar");
        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(jar))) {
            output.putNextEntry(new JarEntry("probe/space #%.txt"));
            output.write("hello".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        ClassLoader loader = new PayloadResourceClassLoader(getClass().getClassLoader(), jar);
        assertEquals(getClass(), loader.loadClass(getClass().getName()));
        assertNull(loader.getResource("probe/missing.txt"));
        assertFalse(loader.getResources("probe/missing.txt").hasMoreElements());
        assertEquals(1, Collections.list(loader.getResources("probe/space #%.txt")).size());
        try (var input = loader.getResourceAsStream("probe/space #%.txt")) {
            assertNotNull(input);
            assertEquals("hello", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
