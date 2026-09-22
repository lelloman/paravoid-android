package com.lelloman.paravoidcompat.automaticresources;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ServiceLoader;
import java.util.TreeSet;

public final class JavaProbe {
    public interface Greeting { String value(); }
    public static final class AppGreeting implements Greeting { public String value() { return "app"; } }
    public static final class LibraryGreeting implements Greeting { public String value() { return "library"; } }
    static String text(String name) throws Exception {
        try (InputStream input = JavaProbe.class.getResourceAsStream("/" + name)) {
            if (input == null) return "absent";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }
    static String verify() {
        return verify(true);
    }
    static String verify(boolean defaultLoader) {
        try {
            TreeSet<String> greetings = new TreeSet<>();
            ServiceLoader<Greeting> services = defaultLoader ? ServiceLoader.load(Greeting.class)
                : ServiceLoader.load(Greeting.class, JavaProbe.class.getClassLoader());
            for (Greeting greeting : services) greetings.add(greeting.value());
            if (!greetings.toString().equals("[app, library]")) throw new AssertionError(greetings);
            if (Collections.list(JavaProbe.class.getClassLoader().getResources("probe/value.txt")).size() != 1)
                throw new AssertionError("Duplicate/missing Java resource");
            if (!text("probe/excluded.txt").equals("absent")) throw new AssertionError("AGP exclude ignored");
            if (!text("probe/space #%.txt").equals("escaped path")) throw new AssertionError("URL escaping failed");
            String value = text("probe/value.txt");
            if (!value.equals("Java A") && !value.equals("Java B")) throw new AssertionError(value);
            return value;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
