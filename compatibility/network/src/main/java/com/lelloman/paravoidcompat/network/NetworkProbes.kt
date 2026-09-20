package com.lelloman.paravoidcompat.network

import android.content.Context
import android.os.Looper
import android.security.NetworkSecurityPolicy
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.Cache
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.moshi.MoshiConverterFactory

class NetworkProbes(private val context: Context, private val url: String, private val run: String) {
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
            .header("X-Probe-Run", run).header("X-Probe-Pid", android.os.Process.myPid().toString())
            .header("X-Probe-Interceptor", "payload").build()) }.build()
    private fun gson(c: OkHttpClient = client): GsonApi = Retrofit.Builder().baseUrl(url).client(c)
        .addConverterFactory(GsonConverterFactory.create()).build().create(GsonApi::class.java)
    private fun moshi(c: OkHttpClient = client, reflect: Boolean = false): MoshiApi {
        val builder = Moshi.Builder().add(QualifierAdapter())
        if (reflect) builder.addLast(KotlinJsonAdapterFactory())
        return Retrofit.Builder().baseUrl(url).client(c).addConverterFactory(MoshiConverterFactory.create(builder.build()))
            .build().create(MoshiApi::class.java)
    }
    private fun await(latch: CountDownLatch) { check(latch.await(10, TimeUnit.SECONDS)) { "Callback deadline exceeded" } }
    private fun item(value: GsonApi.Envelope<List<GsonApi.Item>>?) {
        check(value?.run == run && value.data.single().name == "caffè ☕" && value.data.single().count == 7)
    }

    fun execute(): JSONObject {
        val results = JSONObject()
        fun probe(name: String, block: () -> Unit) {
            try { block(); results.put(name, "PASS") }
            catch (error: Exception) { android.util.Log.e("NetworkProbe", name, error); results.put(name, error.toString()) }
            catch (error: LinkageError) { android.util.Log.e("NetworkProbe", name, error); results.put(name, error.toString()) }
        }
        try {
            probe("gson.genericGet") { item(gson().items().execute().body()) }
            probe("gson.post") {
                val value = GsonApi.Envelope<List<GsonApi.Item>>().also {
                    it.run = run; it.data = listOf(GsonApi.Item().also { v -> v.name = "posted ☕"; v.count = 11 })
                }
                val response = gson().echo(value).execute()
                check(response.isSuccessful && response.body()?.data?.single()?.name == "posted ☕")
                check(response.body()?.run == run && response.body()?.data?.single()?.count == 11)
            }
            probe("retrofit.defaultMethod") { check(gson().identity() == "default-method") }
            probe("retrofit.mainCallback") {
                val done = CountDownLatch(1)
                val error = AtomicReference<Throwable?>()
                gson().items().enqueue(object : Callback<GsonApi.Envelope<List<GsonApi.Item>>> {
                    override fun onResponse(call: Call<GsonApi.Envelope<List<GsonApi.Item>>>, response: Response<GsonApi.Envelope<List<GsonApi.Item>>>) {
                        try { check(Looper.myLooper() === Looper.getMainLooper()); item(response.body()) }
                        catch (failure: Throwable) { error.set(failure) }
                        finally { done.countDown() }
                    }
                    override fun onFailure(call: Call<GsonApi.Envelope<List<GsonApi.Item>>>, failure: Throwable) {
                        error.set(failure); done.countDown()
                    }
                })
                await(done); check(error.get() == null) { "Callback failed: ${error.get()}" }
            }
            probe("moshi.generatedSuspend") {
                val value = runBlocking { moshi().generated() }
                check(value.run == run && value.data.single() == GeneratedItem("caffè ☕", 7))
            }
            probe("moshi.reflectiveSuspend") {
                val value = runBlocking { moshi(reflect = true).reflected() }
                check(value.run == run && value.data.single() == ReflectiveItem("caffè ☕", 7))
            }
            probe("moshi.qualifier") {
                val value = runBlocking { moshi(reflect = true).qualified() }
                check(value.run == run && value.label == "MIXED")
            }
            probe("retrofit.errorResponse") {
                val response = gson().error().execute()
                check(response.code() == 422)
                response.errorBody()!!.use { check(JSONObject(it.string()).getString("run") == run) }
            }
            probe("retrofit.suspendHttpError") {
                try { runBlocking { moshi().error() }; error("Expected HTTP error") }
                catch (failure: HttpException) {
                    check(failure.code() == 422)
                    failure.response()!!.errorBody()!!.use { check(JSONObject(it.string()).getString("run") == run) }
                }
            }
            probe("moshi.malformed") {
                try { runBlocking { moshi().malformed() }; error("Expected JSON error") }
                catch (_: java.io.EOFException) { }
                catch (_: JsonDataException) { }
            }
            probe("okhttp.timeout") {
                val short = client.newBuilder().callTimeout(250, TimeUnit.MILLISECONDS).build()
                try { gson(short).slow().execute(); error("Expected timeout") }
                catch (_: InterruptedIOException) { }
            }
            probe("retrofit.cancelCallback") {
                val headers = CountDownLatch(1)
                val done = CountDownLatch(1)
                val error = AtomicReference<Throwable?>()
                val c = client.newBuilder().eventListener(object : EventListener() {
                    override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { headers.countDown() }
                }).build()
                val call = gson(c).slow()
                call.enqueue(object : Callback<GsonApi.Envelope<List<GsonApi.Item>>> {
                    override fun onResponse(call: Call<GsonApi.Envelope<List<GsonApi.Item>>>, response: Response<GsonApi.Envelope<List<GsonApi.Item>>>) {
                        error.set(IllegalStateException("Canceled call succeeded")); done.countDown()
                    }
                    override fun onFailure(call: Call<GsonApi.Envelope<List<GsonApi.Item>>>, failure: Throwable) {
                        try { check(call.isCanceled && failure is IOException && Looper.myLooper() === Looper.getMainLooper()) }
                        catch (problem: Throwable) { error.set(problem) }
                        finally { done.countDown() }
                    }
                })
                await(headers); call.cancel(); await(done)
                check(error.get() == null) { "Cancellation callback failed: ${error.get()}" }
            }
            probe("retrofit.cancelCoroutine") {
                val headers = CountDownLatch(1)
                val canceled = CountDownLatch(1)
                val c = client.newBuilder().eventListener(object : EventListener() {
                    override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { headers.countDown() }
                    override fun canceled(call: okhttp3.Call) { canceled.countDown() }
                }).build()
                runBlocking {
                    val job = launch(Dispatchers.IO) { moshi(c).slow() }
                    try { withContext(Dispatchers.IO) { await(headers) } }
                    finally { job.cancelAndJoin() }
                    check(job.isCancelled)
                }
                await(canceled)
            }
            probe("okhttp.cache") {
                Cache(File(context.cacheDir, "http-$run-${android.os.Process.myPid()}"), 1024 * 1024).use { cache ->
                    val c = client.newBuilder().cache(cache).build()
                    val request = Request.Builder().url(url + "cache").build()
                    c.newCall(request).execute().use {
                        check(it.networkResponse != null && JSONObject(it.body!!.string()).getString("run") == run)
                    }
                    c.newCall(request).execute().use {
                        check(it.cacheResponse != null && it.networkResponse == null)
                        check(JSONObject(it.body!!.string()).getString("run") == run)
                    }
                }
            }
            probe("loader.isolation") {
                val loader = javaClass.classLoader!!
                check((loader is InMemoryDexClassLoader) == context.packageName.endsWith(".paravoid"))
                check(Proxy.isProxyClass(gson().javaClass) && gson().javaClass.classLoader === loader)
                check(Class.forName("com.lelloman.paravoidcompat.network.GeneratedItemJsonAdapter").classLoader === loader)
                if (loader is InMemoryDexClassLoader) {
                    for (type in listOf(GeneratedItem::class.java, Retrofit::class.java, OkHttpClient::class.java)) {
                        check(runCatching { loader.parent.loadClass(type.name) }.isFailure)
                    }
                }
            }
            probe("network.securityConfig") {
                check(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
                check(!NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("example.com"))
            }
        } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        return results
    }
}
