package com.yourname.cloudlink.data.ws

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.yourname.cloudlink.data.model.Device
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

sealed class DeviceEvent {
    data class StateChanged(
        val entityId: String,
        val state: String,
        val attributes: Map<String, Any?>,
    ) : DeviceEvent()
    data class DeviceAdded(val device: Device) : DeviceEvent()
    data class DeviceRemoved(val entityId: String) : DeviceEvent()
    data object Connected : DeviceEvent()
    data object Disconnected : DeviceEvent()
}

@Singleton
class DeviceWebSocket @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
) {
    private val _events = MutableSharedFlow<DeviceEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<DeviceEvent> = _events

    private var socket: WebSocket? = null

    private val stateAdapter: JsonAdapter<Map<String, Any?>> =
        moshi.adapter(Map::class.java as Class<Map<String, Any?>>)

    fun connect(baseWsUrl: String, token: String) {
        disconnect()
        val url = "$baseWsUrl/api/ws/client?token=$token"
        val request = Request.Builder().url(url).build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _events.tryEmit(DeviceEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                _events.tryEmit(DeviceEvent.Disconnected)
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                _events.tryEmit(DeviceEvent.Disconnected)
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
    }

    private fun handleMessage(text: String) {
        try {
            val map = moshi.adapter(Map::class.java).fromJson(text) as? Map<*, *> ?: return
            when (map["type"]) {
                "state_change" -> {
                    val entityId = map["entity_id"] as? String ?: return
                    val state = map["state"] as? String ?: return
                    val attrs = (map["attributes"] as? Map<*, *>)
                        ?.mapKeys { it.key.toString() }
                        ?.mapValues { it.value }
                        ?: emptyMap()
                    _events.tryEmit(
                        DeviceEvent.StateChanged(entityId, state, attrs),
                    )
                }
                "device_added" -> {
                    val dev = map["device"] as? Map<*, *> ?: return
                    val device = Device(
                        id = (dev["id"] as? Number)?.toLong() ?: 0L,
                        entity_id = dev["entity_id"] as? String ?: "",
                        domain = dev["domain"] as? String ?: "",
                        name = dev["name"] as? String ?: "",
                        state = dev["state"] as? String ?: "",
                        attributes = (dev["attributes"] as? Map<*, *>)
                            ?.mapKeys { it.key.toString() }
                            ?.mapValues { it.value }
                            ?: emptyMap(),
                        haInstanceId = (dev["ha_instance_id"] as? Number)?.toLong() ?: 0L,
                    )
                    _events.tryEmit(DeviceEvent.DeviceAdded(device))
                }
                "device_removed" -> {
                    val entityId = map["entity_id"] as? String ?: return
                    _events.tryEmit(DeviceEvent.DeviceRemoved(entityId))
                }
            }
        } catch (_: Exception) {
            // ignore parse errors
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object WsModule {
    @Provides
    @Singleton
    fun provideDeviceWebSocket(
        client: OkHttpClient,
        moshi: Moshi,
    ): DeviceWebSocket = DeviceWebSocket(client, moshi)
}
