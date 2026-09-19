package com.lelloman.paravoidandroid.gradle

/** Optional build-time adapter, invoked after dependency instrumentation and before DEX.
 * Entries are class-file paths to bytes. Implementations may replace/add entries and
 * must declare their configuration with Gradle input annotations (the task nests them).
 * Do not retain Project or other live Gradle model objects in an implementation.
 */
interface PayloadTransformer {
    void transform(Map<String, byte[]> classes)
}
