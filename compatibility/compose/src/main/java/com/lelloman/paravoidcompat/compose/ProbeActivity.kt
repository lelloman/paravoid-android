package com.lelloman.paravoidcompat.compose

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.*
import com.lelloman.paravoidandroid.runtime.ParavoidAndroidApplication
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.InMemoryDexClassLoader
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import java.util.UUID

@Singleton
class SharedGraph @Inject constructor(@ApplicationContext val context: Context, val application: Application) {
    val id = UUID.randomUUID().toString()
}

@HiltAndroidApp
class ProbeApplication : ParavoidAndroidApplication() {
    @Inject lateinit var graph: SharedGraph
    override fun onCreate() {
        super.onCreate()
        check(graph.application === applicationContext && graph.context === applicationContext)
        initializedGraph = graph
    }
    companion object { lateinit var initializedGraph: SharedGraph }
}

@HiltViewModel
class ScreenModel @Inject constructor(val graph: SharedGraph, val state: SavedStateHandle) : ViewModel() {
    val id = UUID.randomUUID().toString()
    val count = state.getStateFlow("count", 0)
    fun increment() { state["count"] = count.value + 1 }
}

@AndroidEntryPoint
class ProbeActivity : ComponentActivity() {
    val instance = UUID.randomUUID().toString()
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        check(getSharedPreferences("compose-probe", MODE_PRIVATE).edit()
            .putString("savedActivity", instance).commit())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "list") {
                        composable("list") { Screen("list", instance, "Open detail") { nav.navigate("detail") } }
                        composable("detail") { Screen("detail", instance, "Back to list") { nav.popBackStack() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen(route: String, activity: String, next: String, navigate: () -> Unit) {
    val model: ScreenModel = hiltViewModel()
    val count by model.count.collectAsState()
    var local by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val title = stringResource(R.string.probe_title)
    check(model.graph === ProbeApplication.initializedGraph)
    check(model.graph.application === context.applicationContext)
    check((model.javaClass.classLoader is InMemoryDexClassLoader) == context.packageName.endsWith(".paravoid"))
    // A write-only observation channel: restoration must come from Android/Compose, never this file.
    SideEffect {
        val report = JSONObject().put("route", route).put("count", count).put("local", local)
            .put("model", model.id).put("graph", model.graph.id).put("activity", activity)
            .put("pid", android.os.Process.myPid()).put("resource", title)
        check(context.getSharedPreferences("compose-probe", Context.MODE_PRIVATE).edit()
            .putString("report", report.toString()).commit())
    }
    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("$title — $route", style = MaterialTheme.typography.headlineSmall)
        Text("SavedStateHandle: $count / rememberSaveable: $local")
        Button(onClick = { model.increment(); local++ }, modifier = Modifier.semantics { contentDescription = "Increment" }) {
            Text("Increment")
        }
        Button(onClick = navigate, modifier = Modifier.semantics { contentDescription = next }) { Text(next) }
    }
}
