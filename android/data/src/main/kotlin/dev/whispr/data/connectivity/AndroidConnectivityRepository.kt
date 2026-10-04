package dev.whispr.data.connectivity

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.whispr.domain.repository.ConnectivityRepository
import java.util.Collections
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Emits whether any validated internet-capable network is available. */
class AndroidConnectivityRepository(private val cm: ConnectivityManager) : ConnectivityRepository {

    override val isOnline: Flow<Boolean> = callbackFlow {
        // Callbacks arrive on a binder thread.
        val validated = Collections.synchronizedSet(mutableSetOf<Network>())
        fun publish() = trySend(validated.isNotEmpty())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    validated += network
                } else {
                    validated -=
                        network
                }
                publish()
            }

            override fun onLost(network: Network) {
                validated -= network
                publish()
            }
        }
        // Initial value first, so a callback can never be overwritten by a stale snapshot.
        val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        trySend(active?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)

        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, callback)

        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().conflate()
}
