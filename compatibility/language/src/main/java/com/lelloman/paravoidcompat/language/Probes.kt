package com.lelloman.paravoidcompat.language

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import java.io.*
import java.lang.reflect.Proxy
import java.util.ServiceLoader
import java.util.concurrent.Executors
import kotlin.reflect.full.*
import probe.library.Greeter

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Marker(val value: String)

@Marker("retained")
@Serializable
data class Model(val name: String, val count: Int = 7) : java.io.Serializable

@Serializable
sealed class Event {
    @Serializable @SerialName("message")
    data class Message(val item: Model, val labels: List<String>) : Event()
}

@Parcelize
data class ParcelModel(val label: String, val values: List<Int>) : Parcelable

object Probes {
    fun discovery(): Boolean = ServiceLoader.load(Greeter::class.java).single().greet("payload") == "Hello payload"

    fun all(): Map<String, () -> Unit> = linkedMapOf(
        "serialization.generated" to {
            val event: Event = Event.Message(Model("payload"), listOf("one", "two"))
            check(Json.decodeFromString<Event>(Json.encodeToString(event)) == event)
            check(Json.decodeFromString<Model>("{\"name\":\"default\"}").count == 7)
        },
        "reflection.kotlin" to {
            val constructor = Model::class.primaryConstructor!!
            val instance = constructor.callBy(mapOf(constructor.parameters.single { it.name == "name" } to "reflect"))
            check(instance == Model("reflect", 7))
            check(Model::class.memberProperties.single { it.name == "count" }.get(instance) == 7)
            check(Model::class.findAnnotation<Marker>()!!.value == "retained")
            check(Event.Message::class.memberProperties.single { it.name == "labels" }.returnType.arguments.single().type!!.classifier == String::class)
        },
        "reflection.java" to {
            val cls = Class.forName("probe.library.FriendlyGreeter")
            check(cls.getMethod("greet", String::class.java).invoke(cls.getConstructor().newInstance(), "reflect") == "Hello reflect")
        },
        "proxy.explicitLoader" to {
            val proxy = Proxy.newProxyInstance(Greeter::class.java.classLoader, arrayOf(Greeter::class.java)) { _, method, args ->
                check(method.name == "greet"); "proxy ${args!![0]}"
            } as Greeter
            check(proxy.greet("payload") == "proxy payload")
        },
        "parcelize.generated" to {
            val original = ParcelModel("payload", listOf(1, 2, 3))
            val parcel = Parcel.obtain()
            try {
                parcel.writeParcelable(original, 0)
                parcel.setDataPosition(0)
                check(parcel.readParcelable<ParcelModel>(ParcelModel::class.java.classLoader) == original)
                check(ParcelModel::class.java.getField("CREATOR").get(null) is Parcelable.Creator<*>)
            } finally { parcel.recycle() }
        },
        "serialization.java" to {
            val bytes = ByteArrayOutputStream()
            ObjectOutputStream(bytes).use { it.writeObject(Model("java")) }
            check(ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() } == Model("java"))
        },
        "service.explicitLoader" to {
            check(ServiceLoader.load(Greeter::class.java, Greeter::class.java.classLoader).single().greet("explicit") == "Hello explicit")
        },
        "service.inheritedThread" to { check(discovery()) },
        "service.executor" to {
            val executor = Executors.newSingleThreadExecutor()
            try { check(executor.submit<Boolean> { discovery() }.get()) } finally { executor.shutdown() }
        },
        "service.coroutines" to { runBlocking { withContext(Dispatchers.Default) { check(discovery()) } } },
        "proxy.contextLoader" to {
            val proxy = Proxy.newProxyInstance(Thread.currentThread().contextClassLoader, arrayOf(Greeter::class.java)) { _, _, _ -> "context" } as Greeter
            check(proxy.greet("payload") == "context")
        }
    )
}
