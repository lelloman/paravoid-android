package com.lelloman.paravoidcompat.network

import android.util.Base64
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.json.JSONObject

object TlsProbes {
    fun cases(client: OkHttpClient, url: String, certificate: String, run: String): Map<String, () -> Unit> {
        // Only this fixture client trusts the ephemeral test certificate; platform trust is unchanged.
        val trusted by lazy {
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(
                Base64.decode(certificate, Base64.DEFAULT).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).also {
                it.load(null, null); it.setCertificateEntry("local-fixture", cert)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(keys) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").also { it.init(null, arrayOf(trust), null) }
            client.newBuilder().sslSocketFactory(ssl.socketFactory, trust).build()
        }
        return linkedMapOf(
            "tls.trusted" to {
                trusted.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful)
                    check(response.handshake!!.tlsVersion in listOf(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3))
                    val body = JSONObject(response.body!!.string())
                    check(body.getString("run") == run && body.getString("transport") == "tls")
                }
            },
            "tls.untrustedRejected" to {
                try {
                    client.newCall(Request.Builder().url(url).build()).execute().close()
                    error("Untrusted certificate accepted")
                } catch (_: SSLHandshakeException) { }
            },
            "tls.hostnameMismatchRejected" to {
                val mismatch = trusted.newBuilder().dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        check(hostname == "mismatch.invalid")
                        return listOf(InetAddress.getByName("127.0.0.1"))
                    }
                }).build()
                try {
                    mismatch.newCall(Request.Builder().url(url.replace("127.0.0.1", "mismatch.invalid")).build())
                        .execute().close()
                    error("Wrong hostname accepted")
                } catch (_: SSLPeerUnverifiedException) { }
            }
        )
    }
}
