package com.lelloman.paravoidcompat.network

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import org.json.JSONObject

class ProbeActivity : Activity() {
    private lateinit var run: String
    private lateinit var baseUrl: String
    private lateinit var tlsUrl: String
    private lateinit var certificate: String
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        run = state?.getString("run") ?: intent.getStringExtra("probeRun") ?: error("Missing run token")
        baseUrl = state?.getString("baseUrl") ?: intent.getStringExtra("baseUrl") ?: error("Missing local server")
        tlsUrl = state?.getString("tlsUrl") ?: intent.getStringExtra("tlsUrl") ?: error("Missing TLS server")
        certificate = state?.getString("certificate") ?: intent.getStringExtra("certificate") ?: error("Missing test certificate")
        val view = TextView(this).also { it.text = "Running network probes" }
        setContentView(view)
        Thread({
            val results = NetworkProbes(this, baseUrl, run, tlsUrl, certificate).execute()
            val report = JSONObject().put("run", run).put("pid", android.os.Process.myPid())
                .put("restored", state != null).put("results", results)
            check(getSharedPreferences("network-probe", MODE_PRIVATE).edit().putString("report", report.toString()).commit())
            runOnUiThread { view.text = results.toString(2) }
        }, "network-probe").start()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("run", run)
        state.putString("baseUrl", baseUrl)
        state.putString("tlsUrl", tlsUrl)
        state.putString("certificate", certificate)
        super.onSaveInstanceState(state)
        getSharedPreferences("network-probe", MODE_PRIVATE).edit().putInt("savedPid", android.os.Process.myPid()).commit()
    }
}
