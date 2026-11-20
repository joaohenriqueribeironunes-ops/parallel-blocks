package com.paradown.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado resumido da rede padrão do dispositivo.
 * Wi-Fi e Ethernet contam como rede local permitida para o modo "somente Wi-Fi".
 */
data class NetworkStatus(
    val connected: Boolean = false,
    val validated: Boolean = false,
    val isWifi: Boolean = false,
    val isEthernet: Boolean = false,
    val isMobile: Boolean = false,
    val isMetered: Boolean = true,
) {
    val isWifiLike: Boolean get() = isWifi || isEthernet

    val typeLabel: String
        get() = when {
            isWifi -> "Wi-Fi"
            isEthernet -> "Ethernet"
            isMobile -> "Dados móveis"
            else -> "Sem conexão"
        }
}

/**
 * Observa a rede padrão usando NetworkCallback, sem polling.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _status = MutableStateFlow(readCurrent())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            refresh()
        }

        override fun onUnavailable() {
            refresh()
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    fun refresh() {
        _status.value = readCurrent()
    }

    private fun readCurrent(): NetworkStatus {
        val active = runCatching { connectivityManager.activeNetwork }.getOrNull()
            ?: return NetworkStatus()
        val capabilities = runCatching {
            connectivityManager.getNetworkCapabilities(active)
        }.getOrNull() ?: return NetworkStatus()

        val hasInternet = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
        )
        val validated = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        )
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val mobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        return NetworkStatus(
            connected = hasInternet,
            validated = validated,
            isWifi = wifi,
            isEthernet = ethernet,
            isMobile = mobile,
            isMetered = !capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            ),
        )
    }
}
