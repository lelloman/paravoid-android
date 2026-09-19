package com.lelloman.paravoidsample.library;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class DefaultGreetingProvider implements GreetingProvider {
    @Override public String greeting() {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/sample-library/greeting.properties")) {
            if (stream == null) throw new IllegalStateException("Missing dependency Java resource");
            properties.load(stream);
            return properties.getProperty("greeting");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
