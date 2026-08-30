package moe.qwreey.powerdirect.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the port adbd is listening on, by asking mDNS.
 *
 * Ported from Key Mapper's `AdbMdns` (GPL-3.0). Wireless debugging picks a
 * fresh random port every time it is enabled and shows it in Settings, which is
 * no use to us -- but adbd also advertises it over mDNS, on the loopback
 * interface among others, so the app can find it without the user reading
 * anything off the screen.
 */
internal class AdbMdns(context: Context, private val service: Service) {
    enum class Service(val type: String) {
        /** The port that accepts connections from an already-paired key. */
        Connect("_adb-tls-connect._tcp"),

        /** The port that only exists while the pairing dialog is open. */
        Pair("_adb-tls-pairing._tcp"),
    }

    private val nsd: NsdManager = context.getSystemService(NsdManager::class.java)

    private val found = Channel<NsdServiceInfo>(capacity = Channel.BUFFERED)
    private val resolved = Channel<NsdServiceInfo?>(capacity = 1)

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            resolved.trySendBlocking(null)
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            resolved.trySendBlocking(info)
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "cannot discover $serviceType: error $errorCode")
            found.close()
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            found.trySendBlocking(info)
        }

        override fun onServiceLost(info: NsdServiceInfo) = Unit
    }

    /**
     * @return the port, or null if nothing answered in time. Only services on
     *   this device count: an adbd advertised by another machine on the network
     *   is somebody else's, and its port would be meaningless here.
     */
    // resolveService and NsdServiceInfo.host are deprecated in favour of
    // registerServiceInfoCallback, which only exists from API 34. This has to
    // work from 30, and the replacement buys nothing here.
    @Suppress("DEPRECATION")
    suspend fun discoverPort(timeoutMillis: Long = DISCOVERY_TIMEOUT_MILLIS): Int? {
        nsd.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        return try {
            withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    val info = found.receive()

                    // Only one resolution may be in flight at a time.
                    nsd.resolveService(info, resolveListener)
                    val service = resolved.receive() ?: continue

                    val onThisDevice = service.host?.isLoopbackAddress == true ||
                        service.host?.isAnyLocalAddress == true ||
                        isThisDevice(service)
                    if (!onThisDevice) continue

                    // 광고된다고 살아 있는 것은 아니다.
                    //
                    // 무선 디버깅을 껐다 켜면 포트가 바뀌는데 안드로이드의 NSD 캐시에는
                    // 옛 레코드가 한동안 남는다. 먼저 해석된 것을 그대로 믿으면 이미 사라진
                    // 포트를 들고 가서 ECONNREFUSED 로 죽는다 — 실기기에서 같은 죽은 포트가
                    // 세 번 반복해 나왔다. 첫 시도만 실패하고 다시 누르면 되는 증상이
                    // 여기서 나온다.
                    //
                    // 루프백으로 한 번 두드려 보면 끝난다. 거절은 즉시 돌아온다.
                    if (!accepts(service.port)) {
                        Log.d(TAG, "port ${service.port} is advertised but dead; looking further")
                        continue
                    }

                    Log.d(TAG, "adbd is on port ${service.port}")
                    return@withTimeoutOrNull service.port
                }

                @Suppress("UNREACHABLE_CODE")
                null
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
    }

    /** 이 포트가 실제로 연결을 받는가. 죽은 광고를 걸러낸다. */
    private fun accepts(port: Int): Boolean = runCatching {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MILLIS)
        }
        true
    }.getOrDefault(false)

    /**
     * mDNS also carries the advertisements of every other device on the LAN, and
     * adbd on a colleague's laptop is not ours to connect to.
     */
    private fun isThisDevice(info: NsdServiceInfo): Boolean {
        val address = info.host?.hostAddress ?: return false
        return java.net.NetworkInterface.getNetworkInterfaces().asSequence().any { networkInterface ->
            networkInterface.inetAddresses.asSequence().any { it.hostAddress == address }
        }
    }

    private companion object {
        const val TAG = "AdbMdns"
        const val DISCOVERY_TIMEOUT_MILLIS = 10_000L
        const val LOOPBACK = "127.0.0.1"
        const val PROBE_TIMEOUT_MILLIS = 400
    }
}
