package com.lelloman.paravoidandroid.runtime;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarFile;

/** Resource-only parent of the defining DEX loader; classes still come from shell or DEX. */
final class PayloadResourceClassLoader extends ClassLoader {
    private final JarFile resources;
    private final String location;

    PayloadResourceClassLoader(ClassLoader parent, File archive) throws IOException {
        super(parent);
        resources = new JarFile(archive, false);
        location = archive.toURI().toASCIIString();
    }

    @Override protected URL findResource(String name) {
        if (name.startsWith("/") || resources.getJarEntry(name) == null) return null;
        try {
            String encoded = new URI(null, null, "/" + name, null).getRawPath().substring(1);
            return new URL("jar:" + location + "!/" + encoded);
        } catch (java.net.URISyntaxException | java.net.MalformedURLException error) {
            return null;
        }
    }

    @Override protected Enumeration<URL> findResources(String name) {
        URL resource = findResource(name);
        return Collections.enumeration(resource == null ? Collections.emptyList() : Collections.singletonList(resource));
    }
}
