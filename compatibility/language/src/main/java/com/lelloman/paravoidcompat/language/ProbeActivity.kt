package com.lelloman.paravoidcompat.language

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication
import dalvik.system.InMemoryDexClassLoader
import org.json.JSONObject

class ProbeApplication : ParavoidAndroidApplication() {
    override fun onCreate() {
        super.onCreate()
        mainResult = outcome { check(Probes.discovery()) }
    }
    companion object { var mainResult = "not run" }
}

private fun outcome(block: () -> Unit): String = try {
    block(); "PASS"
} catch (error: Throwable) {
    android.util.Log.e("LanguageProbe", "Probe failure", error)
    "${error.javaClass.name}: ${error.message}"
}

class ProbeActivity : Activity() {
    private lateinit var run: String
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        run = state?.getString("run") ?: intent.getStringExtra("probeRun") ?: "manual"
        val results = JSONObject().put("service.applicationMain", ProbeApplication.mainResult)
        results.put("state.payloadObjects", outcome {
            if (state != null) {
                check(state.getParcelable<ParcelModel>("parcel") == ParcelModel(run, listOf(8, 9)))
                check(state.getSerializable("serial") == Model(run))
            }
        })
        results.put("loader.isolation", outcome {
            val loader = javaClass.classLoader!!
            val shell = packageName.endsWith(".paravoid")
            check((loader is InMemoryDexClassLoader) == shell)
            if (shell) {
                check(runCatching { loader.parent.loadClass(Model::class.java.name) }.isFailure)
                check(Model::class.java.classLoader === loader)
            }
        })
        val view = TextView(this)
        view.text = "Running language probes"
        setContentView(view)
        Thread({
            Probes.all().forEach { (name, probe) -> results.put(name, outcome(probe)) }
            val report = JSONObject().put("run", run).put("pid", android.os.Process.myPid())
                .put("restored", state != null).put("results", results)
            check(getSharedPreferences("language-probe", MODE_PRIVATE).edit().putString("report", report.toString()).commit())
            runOnUiThread { view.text = results.toString(2) }
        }, "language-probe").start()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("run", run)
        state.putParcelable("parcel", ParcelModel(run, listOf(8, 9)))
        state.putSerializable("serial", Model(run))
        super.onSaveInstanceState(state)
        getSharedPreferences("language-probe", MODE_PRIVATE).edit()
            .putInt("savedPid", android.os.Process.myPid()).commit()
    }
}
