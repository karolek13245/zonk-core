package com.example.aiapp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** The real connection to a relay: an OkHttp WebSocket, plugged into [HostSession]. */
class OkHostSocketFactory(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Protocol-level pings: a connection that went dead without a goodbye (Wi-Fi lost, phone asleep) fails within about a minute.
        .pingInterval(30, TimeUnit.SECONDS)
        .build(),
) : HostSocketFactory {

    override fun open(url: String, token: String, listener: HostSocketListener): HostSocket {
        // The token travels only in this header, never in the address (addresses end up in logs).
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen(OkSocket(webSocket))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Answer the relay's goodbye. Always 1000: the code that came in (for example 1005) may not be sent back.
                webSocket.close(1000, null)
                listener.onClosed(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(response?.code, t)
            }
        })
        return OkSocket(socket)
    }
}

private class OkSocket(private val socket: WebSocket) : HostSocket {
    override fun send(text: String): Boolean = socket.send(text)

    override fun close(code: Int, reason: String) {
        if (!socket.close(code, reason)) socket.cancel()
    }
}
