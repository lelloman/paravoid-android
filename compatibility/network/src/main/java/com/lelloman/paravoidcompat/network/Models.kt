package com.lelloman.paravoidcompat.network

import com.squareup.moshi.*
import retrofit2.http.GET

@JsonClass(generateAdapter = true)
data class Envelope<T>(val data: T, val run: String)
@JsonClass(generateAdapter = true)
data class GeneratedItem(@Json(name = "display_name") val name: String, val count: Int = 7)
@JsonClass(generateAdapter = false)
data class ReflectiveItem(@Json(name = "display_name") val name: String, val count: Int = 7)
@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class Uppercase
@JsonClass(generateAdapter = false)
data class Qualified(@Uppercase val label: String, val run: String)
class QualifierAdapter {
    @FromJson @Uppercase fun fromJson(value: String): String = value.uppercase(java.util.Locale.ROOT)
    @ToJson fun toJson(@Uppercase value: String): String = value.lowercase(java.util.Locale.ROOT)
}
interface MoshiApi {
    @GET("defaults") suspend fun generated(): Envelope<List<GeneratedItem>>
    @GET("defaults") suspend fun reflected(): Envelope<List<ReflectiveItem>>
    @GET("qualified") suspend fun qualified(): Qualified
    @GET("error") suspend fun error(): Envelope<List<GeneratedItem>>
    @GET("malformed") suspend fun malformed(): Envelope<List<GeneratedItem>>
    @GET("slow") suspend fun slow(): Envelope<List<GeneratedItem>>
}
