# Loaded by a literal name and queried by method name at runtime.
-keep class com.lelloman.paravoidcompat.minification.ReflectiveProbe { *; }

# META-INF/services lists the provider by its original class name.
-keep interface com.lelloman.paravoidcompat.minification.Greeting { *; }
-keep class com.lelloman.paravoidcompat.minification.GreetingProvider { *; }

# Keep this otherwise unused class alive while allowing R8 to rename it. The
# build checker verifies that obfuscation took place.
-keep,allowobfuscation class com.lelloman.paravoidcompat.minification.RenameProbe { *; }
