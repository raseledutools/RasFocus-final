package com.rasel.RasFocus.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredDevice(val name: String, val ip: String, val port: Int)

class P2PDiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_rasfocus._tcp."
    private var serviceName = "RasFocusDevice_${Build.MODEL}"

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
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                serviceName = NsdServiceInfo.serviceName
                Log.d("P2P", "Service registered: $serviceName")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("P2P", "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    // Discover other devices
    fun discoverServices() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("P2P", "Service discovery started")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("P2P", "Service discovery success $service")
                if (service.serviceType == serviceType) {
                    if (service.serviceName.contains("RasFocusDevice")) {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e("P2P", "Resolve failed: $errorCode")
                            }
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                Log.e("P2P", "Resolve Succeeded. $serviceInfo")
                                if (serviceInfo.serviceName == serviceName) {
                                    Log.d("P2P", "Same IP.")
                                    return
                                }
                                val host = serviceInfo.host.hostAddress
                                val port = serviceInfo.port
                                val device = DiscoveredDevice(serviceInfo.serviceName, host ?: "", port)
                                
                                val currentList = _discoveredDevices.value.toMutableList()
                                if (currentList.none { it.ip == device.ip }) {
                                    currentList.add(device)
                                    _discoveredDevices.value = currentList
                                }
                            }
                        })
                    }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("P2P", "service lost: $service")
                val currentList = _discoveredDevices.value.toMutableList()
                currentList.removeAll { it.name == service.serviceName }
                _discoveredDevices.value = currentList
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i("P2P", "Discovery stopped: $serviceType")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("P2P", "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("P2P", "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {}
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
    }
}
