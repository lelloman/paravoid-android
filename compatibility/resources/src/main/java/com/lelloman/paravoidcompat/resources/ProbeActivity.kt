package com.lelloman.paravoidcompat.resources

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication
import com.lelloman.paravoidcompat.resources.payload.R as PackR
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/** Fixture scaffolding, not a new downstream integration requirement or update API. */
class ProbeApplication : ParavoidAndroidApplication() {
    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as Application
        try {
            val selected = File(filesDir, "resource-probe/selected").readText().trim()
            check(selected == "A" || selected == "B") { "Unknown resource selection" }
            val loader = ResourcesLoader()
            ParcelFileDescriptor.open(File(filesDir, "resource-probe/$selected.apk"), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                check(Os.fstat(fd.fileDescriptor).st_mode and 146 == 0) { "Resource pack must be read-only" }
                val digest = MessageDigest.getInstance("SHA-256")
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { input ->
                    val buffer = ByteArray(8192)
                    var size = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        size += count
                        check(size <= 1024 * 1024) { "Resource pack exceeds fixture limit" }
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(hash == if (selected == "A") TrustedPacks.A else TrustedPacks.B) { "Resource pack hash mismatch" }
                Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
                loader.addProvider(ResourcesProvider.loadFromApk(fd))
            }
            app.resources.addLoaders(loader)
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(activity: Activity, state: Bundle?) { activity.resources.addLoaders(loader) }
                override fun onActivityCreated(activity: Activity, state: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        } catch (error: Exception) {
            getSharedPreferences("resources-probe", MODE_PRIVATE).edit()
                .putString("failure", error.message).putInt("failurePid", android.os.Process.myPid()).commit()
            throw error
        }
    }
}

class ProbeActivity : ComponentActivity() {
    private val instance = UUID.randomUUID().toString()
    override fun onCreate(state: Bundle?) {
        setTheme(PackR.style.PayloadTheme)
        super.onCreate(state)
        val view = LayoutInflater.from(this).inflate(PackR.layout.panel, null) as TextView
        val accent = TypedValue()
        check(theme.resolveAttribute(android.R.attr.colorAccent, accent, true))
        check(accent.data == getColor(PackR.color.accent))
        val config = Configuration(resources.configuration).apply { setLocale(Locale.ITALIAN) }
        val italian = createConfigurationContext(config).getString(PackR.string.title)
        val applicationTitle = applicationContext.getString(PackR.string.title)
        val asset = assets.open("probe/message.txt").bufferedReader().use { it.readText().trim() }
        val onlyA = try { getString(PackR.string.only_a) } catch (_: Resources.NotFoundException) { "absent" }
        val addedId = resources.getIdentifier("a_new", "string", "com.lelloman.paravoidcompat.resources.payload")
        val added = if (addedId == 0) "absent" else getString(addedId)
        check((javaClass.classLoader is InMemoryDexClassLoader) == packageName.endsWith(".paravoid"))
        setContent {
            MaterialTheme {
                val title = stringResource(PackR.string.title)
                check(title == view.text.toString() && title == applicationTitle)
                SideEffect {
                    val report = JSONObject().put("title", title).put("view", view.text)
                        .put("asset", asset).put("italian", italian).put("accent", accent.data)
                        .put("removed", onlyA).put("added", added).put("instance", instance)
                        .put("pid", android.os.Process.myPid())
                    check(getSharedPreferences("resources-probe", Context.MODE_PRIVATE).edit()
                        .putString("report", report.toString()).commit())
                }
                Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                    Text("Compose: $title", style = MaterialTheme.typography.headlineSmall)
                    AndroidView(factory = { view })
                    Text("$italian / $asset")
                }
            }
        }
    }
}
