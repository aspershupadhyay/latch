// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cable-free ADB wireless-debugging setup (R27, ADR-046): discovers the phone's
 * own adbd services over mDNS and runs a pairing against the pairing server,
 * then records the connect endpoint so [SocketAdbTransport] can reach adbd over
 * TLS. No USB at any point — the owner reads the pairing code from
 * Settings → Developer options → Wireless debugging → Pair device with a code.
 */
class AdbWireless(
    private val context: Context,
    filesDir: File,
) {
    private val key = SocketAdbTransport.key(filesDir)

    /** The result of a pairing attempt: the connect endpoint to store, or an error message. */
    sealed interface Result {
        data class Paired(val connectEndpoint: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Pairs with the adbd pairing server (discovered, or at the given
     * [pairingHost]:[pairingPort]) using [code], then finds the connect service
     * and returns a `tls://host:port` endpoint. Everything is cable-free.
     */
    suspend fun pair(code: String, pairingHost: String?, pairingPort: Int?): Result {
        val pairing = when {
            pairingHost != null && pairingPort != null -> SocketAdbTransport.Endpoint(pairingHost, pairingPort, tls = true)
            else -> discover(SERVICE_PAIRING) ?: return Result.Failed("No wireless-debugging pairing service found on this network")
        }
        runCatching { PairingSession.pair(key, pairing.host, pairing.port, code) }
            .onFailure { return Result.Failed("Pairing failed: ${it.message ?: it.javaClass.simpleName}") }
        val connect = discover(SERVICE_CONNECT)
            ?: return Result.Failed("Paired, but could not find the connect service; enter the connect address shown under Wireless debugging")
        return Result.Paired("tls://${connect.host}:${connect.port}")
    }

    /** Resolves the first instance of an adbd mDNS [serviceType] to a host and port. */
    // resolveService/host are deprecated at API 34; the callback replacement is API 34+ while minSdk is 30.
    @Suppress("DEPRECATION")
    private suspend fun discover(serviceType: String): SocketAdbTransport.Endpoint? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val resolved = CompletableDeferred<SocketAdbTransport.Endpoint?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        val host = resolvedInfo.host?.hostAddress ?: return
                        if (!resolved.isCompleted) resolved.complete(SocketAdbTransport.Endpoint(host, resolvedInfo.port, tls = true))
                    }
                    override fun onResolveFailed(resolvedInfo: NsdServiceInfo, errorCode: Int) {}
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { if (!resolved.isCompleted) resolved.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { resolved.await() }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    companion object {
        private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
        private const val SERVICE_CONNECT = "_adb-tls-connect._tcp"
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
    }
}
