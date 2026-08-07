package com.rasel.RasFocus.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class DiscoveredDevice(val name: String, val ip: String, val port: Int)

class P2PDiscoveryManager(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val serviceType = "_rasfocus._tcp."
    private var serviceName = "RasFocus_${Build.MODEL.replace(" ", "_")}"

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Register this device so others can find it
    fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = this@P2PDiscoveryManager.serviceName
            this.serviceType = this@P2PDiscoveryManager.serviceType
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredServiceInfo: NsdServiceInfo) {
                serviceName = registeredServiceInfo.serviceName
                Log.d("P2P", "Service registered: $serviceName on port $port")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("P2P", "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("P2P", "Failed to register service: ${e.message}")
        }
    }

    // Discover other devices
    fun discoverServices() {
        // Acquire Multicast Lock to receive mDNS packets on Wi-Fi
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("RasFocusP2PMulticast")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
            Log.d("P2P", "Multicast lock acquired")
        } catch (e: Exception) {
            Log.e("P2P", "Failed to acquire MulticastLock: ${e.message}")
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("P2P", "Service discovery started for $regType")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("P2P", "Service found: ${service.serviceName}, type=${service.serviceType}")
                if (service.serviceType.contains("_rasfocus._tcp")) {
                    if (service.serviceName == serviceName || service.serviceName == this@P2PDiscoveryManager.serviceName) {
                        Log.d("P2P", "Discovered own device service. Skipping.")
                        return
                    }
                    
                    try {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e("P2P", "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                            }
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                Log.d("P2P", "Resolve Succeeded: ${serviceInfo.serviceName} (${serviceInfo.host?.hostAddress}:${serviceInfo.port})")
                                val host = serviceInfo.host?.hostAddress ?: return
                                if (host == "127.0.0.1" || host == getLocalIpAddress()) {
                                    Log.d("P2P", "Resolved host is local device IP. Skipping.")
                                    return
                                }
                                val device = DiscoveredDevice(serviceInfo.serviceName, host, serviceInfo.port)
                                
                                val currentList = _discoveredDevices.value.toMutableList()
                                if (currentList.none { it.ip == device.ip }) {
                                    currentList.add(device)
                                    _discoveredDevices.value = currentList
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.e("P2P", "Error resolving service: ${e.message}")
                    }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d("P2P", "Service lost: ${service.serviceName}")
                val currentList = _discoveredDevices.value.toMutableList()
                currentList.removeAll { it.name == service.serviceName }
                _discoveredDevices.value = currentList
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i("P2P", "Discovery stopped: $serviceType")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("P2P", "Discovery start failed: Error code:$errorCode")
                try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("P2P", "Discovery stop failed: Error code:$errorCode")
            }
        }
        
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("P2P", "Failed to start service discovery: ${e.message}")
        }
    }

    fun stop() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {}
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d("P2P", "Multicast lock released")
            }
        } catch (e: Exception) {}
    }

    companion object {
        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress
                            if (ip != null && !ip.startsWith("127.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("P2P", "Error getting local IP: ${e.message}")
            }
            return "127.0.0.1"
        }
    }
}

