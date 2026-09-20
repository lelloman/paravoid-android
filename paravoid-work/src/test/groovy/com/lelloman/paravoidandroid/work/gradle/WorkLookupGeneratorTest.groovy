package com.lelloman.paravoidandroid.work.gradle

import org.junit.Test
import static org.junit.Assert.*

class WorkLookupGeneratorTest {
    @Test void resolvesPayloadOwnerWithoutChangingOrdinaryContexts() {
        // Small JVM stubs execute the generated bridge without Android dependencies.
        GroovyClassLoader loader = new GroovyClassLoader(getClass().classLoader)
        try {
            Class context = loader.parseClass('package android.content; class Context {}')
            Class payload = loader.parseClass('package com.lelloman.paravoidandroid.runtime; class PayloadApplication extends android.content.Context {}')
            Class shell = loader.parseClass('''package com.lelloman.paravoidandroid.runtime
                class ShellApplication extends android.content.Context {
                    PayloadApplication payload
                    PayloadApplication requirePayloadApplication() { payload }
                }
            ''')
            Class bridge = loader.defineClass(WorkLookupGenerator.NAME.replace('/', '.'), WorkLookupGenerator.generate())
            def method = bridge.getMethod('owner', context)
            Object ordinary = context.getConstructor().newInstance()
            Object app = shell.getConstructor().newInstance()
            Object owner = payload.getConstructor().newInstance()
            app.payload = owner
            assertSame(ordinary, method.invoke(null, ordinary))
            assertSame(owner, method.invoke(null, app))
            assertSame(owner, app.payload)
        } finally { loader.close() }
    }
}
