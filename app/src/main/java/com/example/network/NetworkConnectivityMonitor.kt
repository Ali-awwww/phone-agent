package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkTier(val labelAr: String) {
    OFFLINE("غير متصل بالإنترنت"),
    WEAK_E_2G("اتصال ضعيف (E/2G)"),
    NORMAL("متصل بالشبكة")
}

/**
 * مراقب حالة الشبكة الفعلي باستخدام ConnectivityManager فقط (ACCESS_NETWORK_STATE):
 * يميّز بين انقطاع الشبكة (OFFLINE) والشبكات البطيئة E/2G (WEAK_E_2G) والشبكات العادية (NORMAL)
 * دون طلب أي أذونات هاتف حساسة.
 */
class NetworkConnectivityMonitor(
    context: Context,
    private val initialOverride: NetworkTier? = null
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkTierFlow = MutableStateFlow(
        initialOverride ?: evaluateNetworkTier(connectivityManager)
    )
    val networkTierFlow: StateFlow<NetworkTier> = _networkTierFlow.asStateFlow()

    val currentTier: NetworkTier
        get() = _networkTierFlow.value

    @Volatile
    private var manualOverride: NetworkTier? = initialOverride

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNow()
        }

        override fun onLost(network: Network) {
            refreshNow()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshNow()
        }
    }

    init {
        if (initialOverride == null) {
            runCatching {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            }
        }
    }

    fun refreshNow(): NetworkTier {
        val resolved = manualOverride ?: evaluateNetworkTier(connectivityManager)
        _networkTierFlow.value = resolved
        return resolved
    }

    /**
     * للاختبارات والتحقق من التحول الفعلي بين الحالات (OFFLINE -> WEAK_E_2G -> NORMAL).
     */
    internal fun setTestOverride(tier: NetworkTier?) {
        manualOverride = tier
        _networkTierFlow.value = tier ?: evaluateNetworkTier(connectivityManager)
    }

    fun unregister() {
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
    }

    companion object {
        const val WEAK_DOWNSTREAM_THRESHOLD_KBPS = 400
        const val WEAK_UPSTREAM_THRESHOLD_KBPS = 150

        @Suppress("DEPRECATION")
        fun evaluateNetworkTier(cm: ConnectivityManager?): NetworkTier {
            if (cm == null) return NetworkTier.OFFLINE

            val activeNetwork = cm.activeNetwork
            val caps = activeNetwork?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            val legacyInfo = runCatching { cm.activeNetworkInfo }.getOrNull()

            val hasInternetCap = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isLegacyConnected = legacyInfo?.isConnected == true

            if (!hasInternetCap && !isLegacyConnected) {
                return NetworkTier.OFFLINE
            }

            if (caps != null) {
                val downKbps = caps.linkDownstreamBandwidthKbps
                val upKbps = caps.linkUpstreamBandwidthKbps
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    if ((downKbps in 1..WEAK_DOWNSTREAM_THRESHOLD_KBPS) ||
                        (upKbps in 1..WEAK_UPSTREAM_THRESHOLD_KBPS)
                    ) {
                        return NetworkTier.WEAK_E_2G
                    }
                    if (isLegacyWeak2GSubtype(legacyInfo?.subtype)) {
                        return NetworkTier.WEAK_E_2G
                    }
                } else if (downKbps in 1..WEAK_DOWNSTREAM_THRESHOLD_KBPS) {
                    return NetworkTier.WEAK_E_2G
                }
            } else if (legacyInfo != null && legacyInfo.type == ConnectivityManager.TYPE_MOBILE) {
                if (isLegacyWeak2GSubtype(legacyInfo.subtype)) {
                    return NetworkTier.WEAK_E_2G
                }
            }

            return NetworkTier.NORMAL
        }

        private fun isLegacyWeak2GSubtype(subtype: Int?): Boolean {
            return subtype == TelephonyManager.NETWORK_TYPE_GPRS ||
                subtype == TelephonyManager.NETWORK_TYPE_EDGE ||
                subtype == TelephonyManager.NETWORK_TYPE_CDMA ||
                subtype == TelephonyManager.NETWORK_TYPE_1xRTT ||
                subtype == TelephonyManager.NETWORK_TYPE_IDEN ||
                subtype == TelephonyManager.NETWORK_TYPE_GSM
        }
    }
}
