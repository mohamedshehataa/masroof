package eg.masroof.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the device currently has a network that claims internet access. Only a hint: reaching the server may still fail. */
interface NetworkMonitor {
    val online: Flow<Boolean>
}

@Singleton
class ConnectivityNetworkMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : NetworkMonitor {
        override val online: Flow<Boolean> =
            callbackFlow {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            trySend(true)
                        }

                        override fun onLost(network: Network) {
                            trySend(false)
                        }
                    }
                val active = cm.activeNetwork
                trySend(
                    active != null && cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                )
                cm.registerDefaultNetworkCallback(callback)
                awaitClose { cm.unregisterNetworkCallback(callback) }
            }.distinctUntilChanged()
    }
