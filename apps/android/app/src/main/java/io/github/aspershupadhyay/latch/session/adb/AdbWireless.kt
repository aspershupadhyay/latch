// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
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
            else -> discover(SERVICE_PAIRING) ?: return Result.Failed("No wireless-debugging pairing service found on this phone. Keep the pairing code dialog open and try again.")
        }
        runCatching { PairingSession.pair(key, pairing.host, pairing.port, code.trim()) }
            .onFailure { return Result.Failed("Pairing failed: ${it.message ?: it.javaClass.simpleName}") }
        val connect = connectEndpoint()
            ?: return Result.Failed("Paired, but could not find the connect service; enter the connect address shown under Wireless debugging")
        return Result.Paired(connect)
    }

    /**
     * This phone's current wireless-debugging connect endpoint as
     * `tls://127.0.0.1:port`, or null when wireless debugging is off. Android
     * picks a new port whenever wireless debugging restarts (a reboot, a toggle,
     * a new network), so the bridge calls this to reconnect without a cable or
     * a new pairing. Loopback keeps working when the Wi-Fi address changes.
     */
    suspend fun connectEndpoint(): String? =
        discover(SERVICE_CONNECT)?.let { "tls://$LOOPBACK:${it.port}" }

    /**
     * Resolves the first instance of an adbd mDNS [serviceType] that this phone
     * itself advertises, to a host and port. Other phones on the same Wi-Fi with
     * wireless debugging on advertise the same service types; they are skipped,
     * so pairing and connecting stay bound to this device.
     */
    // resolveService/host are deprecated at API 34; the callback replacement is API 34+ while minSdk is 30.
    @Suppress("DEPRECATION")
    private suspend fun discover(serviceType: String): SocketAdbTransport.Endpoint? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val own = localAddresses()
        val resolved = CompletableDeferred<SocketAdbTransport.Endpoint?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        val address = resolvedInfo.host ?: return
                        if (own.isNotEmpty() && address !in own && !address.isLoopbackAddress) return
                        val host = address.hostAddress ?: return
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

    /** Every address of this phone's network interfaces, to tell its own adbd from another device's. */
    private fun localAddresses(): Set<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .toSet()
    }.getOrDefault(emptySet())

    companion object {
        private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
        private const val SERVICE_CONNECT = "_adb-tls-connect._tcp"
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
        private const val LOOPBACK = "127.0.0.1"
    }
}
