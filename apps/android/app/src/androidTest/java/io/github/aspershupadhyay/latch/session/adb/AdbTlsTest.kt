// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Regression on a real Android runtime: wireless-debugging pairing binds its
 * code to TLS exported keying material. The app looked the exporter up as
 * `org.conscrypt.Conscrypt`, which exists only in the unbundled library, so
 * every pairing on a phone failed with "pairing failed: org.conscrypt.Conscrypt".
 * JVM tests could not see it. Here the app's own TLS client meets a TLS 1.3
 * server on the device, and both ends must export the same 64 bytes.
 */
@RunWith(AndroidJUnit4::class)
class AdbTlsTest {
    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun pairingCanExportKeyingMaterialOnAndroid() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val client = AdbKey.loadOrCreate(File(dir, "adbtls-client.key"))
        val server = AdbKey.loadOrCreate(File(dir, "adbtls-server.key"))
        val listener = serverContext(server).serverSocketFactory.createServerSocket(0) as SSLServerSocket
        listener.enabledProtocols = arrayOf("TLSv1.3")
        val pool = Executors.newSingleThreadExecutor()
        val serverSide = pool.submit<ByteArray> {
            listener.accept().use { s ->
                val tls = s as SSLSocket
                tls.startHandshake()
                android.net.ssl.SSLSockets.exportKeyingMaterial(tls, String(Spake2.EXPORT_KEY_LABEL, Charsets.US_ASCII), null, Spake2.EXPORT_KEY_LENGTH)
            }
        }
        val socket = AdbTls.socket(client, "127.0.0.1", listener.localPort)
        val mine = socket.use { AdbTls.exportKeyingMaterial(it) }
        val theirs = serverSide.get(10, TimeUnit.SECONDS)
        listener.close()
        pool.shutdown()
        assertEquals(Spake2.EXPORT_KEY_LENGTH, mine.size)
        assertTrue("not all zero", mine.any { it != 0.toByte() })
        assertArrayEquals(theirs, mine)
    }

    private fun serverContext(key: AdbKey): SSLContext {
        val keys = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("k")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = "k"
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("k")
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) = "k"
            override fun getCertificateChain(alias: String?) = arrayOf(key.certificate)
            override fun getPrivateKey(alias: String?) = key.privateKey()
        }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trustAll), null) }
    }
}
