# The sample's instrumentation tests and reflection load these classes by name.
-keep class com.lelloman.paravoidandroid.sample.ResourceProbe { *; }
-keep class com.lelloman.paravoidandroid.sample.SampleTypes$* { *; }

# ServiceLoader reads provider names from META-INF/services in the APK.
-keep class com.lelloman.paravoidsample.library.** { *; }
