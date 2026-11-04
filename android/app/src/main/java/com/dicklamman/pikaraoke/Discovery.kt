package com.dicklamman.pikaraoke

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import org.json.JSONObject

/**
 * Finds a PiKaraoke server on the local network.
 *
 * The player is often on a television with a remote control and no keyboard, so
 * asking for the address has to work without one. The server answers on UDP:
 * a question goes out, an answer comes back, and the answer says where the web
 * interface lives.
 */
class Discovery(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "PiKaraokeDiscovery"

        /** The question the server recognises. */
        private const val REQUEST = "PIKARAOKE_DISCOVER_V1"

        /** The port a player tries first. It matches the web port. */
        const val DEFAULT_PORT = 5555

        /** How long to wait for an answer before giving up on a port. */
        private const val TIMEOUT_MS = 1500

        /** How many times to ask before giving up on a port. */
        private const val ATTEMPTS = 2
    }

    /** One server that answered. */
    data class Server(
        val name: String,
        /** The address the server says it lives at. */
        val url: String,
        /** The address the answer came from, which reached the server. */
        val sourceUrl: String,
        val port: Int
    )

    /**
     * Look for a PiKaraoke server.
     *
     * The broadcast address is asked first, because a server on the same
     * network answers it, and then the local network's own address and its
     * gateway as fallbacks for networks where broadcast is switched off.
     *
     * @param port The UDP port to ask on.
     * @return The servers that answered, nearest first. Empty if none did.
     */
    fun findServers(port: Int = DEFAULT_PORT): List<Server> {
        val found = LinkedHashMap<String, Server>()

        for (address in broadcastTargets()) {
            for (attempt in 1..ATTEMPTS) {
                val server = ask(address, port) ?: continue
                found.putIfAbsent(server.url, server)
                break
            }
        }

        return found.values.toList()
    }

    /**
     * The addresses worth asking.
     *
     * The broadcast address reaches every server on the network. The device's
     * own address reaches a server running on this device, and the network's
     * gateway reaches a server on the machine that routes for this one --
     * which is where a Home Assistant usually is, and the one place a broadcast
     * that was blocked by the network still gets through.
     */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()

        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve the broadcast address", e)
        }

        try {
            val local = InetAddress.getLocalHost()
            if (local != null && !local.isLoopbackAddress && !local.isAnyLocalAddress) {
                targets.add(local)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve the local address", e)
        }

        targets.addAll(gatewayTargets())

        return targets.toList()
    }

    /**
     * The gateways this device routes through.
     *
     * The gateway is read from the network's own connection rather than from
     * the routing table in procfs, which Android does not let an app read.
     */
    private fun gatewayTargets(): List<InetAddress> {
        val gateways = LinkedHashSet<InetAddress>()

        try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return gateways.toList()
            val properties = manager.getLinkProperties(network) ?: return gateways.toList()

            for (route in properties.routes) {
                val gateway = route.gateway ?: continue
                // The unspecified address is not a gateway anyone can be
                // reached on, and asking it fails outright.
                if (gateway.isLoopbackAddress || gateway.isAnyLocalAddress) {
                    continue
                }
                gateways.add(gateway)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the network's gateway", e)
        }

        return gateways.toList()
    }

    /**
     * Ask one address for a server.
     *
     * The address the answer came from is kept, because the server's own idea
     * of its address may be one that cannot be reached from here: a server
     * behind a router reports its private address, and a container reports the
     * address on its own network rather than the one its port is published on.
     *
     * @return The server that answered, or null if it did not.
     */
    private fun ask(address: InetAddress, port: Int): Server? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = TIMEOUT_MS

            val request = REQUEST.toByteArray()
            // Sending can fail outright rather than simply going unanswered:
            // an address that is not on any network gives an error the socket
            // raises, and one address failing must not stop the others being
            // tried.
            try {
                socket.send(
                    DatagramPacket(
                        request,
                        request.size,
                        InetSocketAddress(address, port)
                    )
                )
            } catch (e: Exception) {
                Log.d(TAG, "Could not ask $address:$port", e)
                return null
            }

            val buffer = ByteArray(4096)
            val reply = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(reply)
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "No answer from $address:$port")
                return null
            } catch (e: Exception) {
                Log.w(TAG, "Discovery failed against $address:$port", e)
                return null
            }

            val server = parse(String(reply.data, 0, reply.length)) ?: return null

            // The address the answer came from is the one that reached the
            // server, so it is the one that can reach it back.
            val sourceUrl = "http://${reply.address.hostAddress}:${server.port}"
            return server.copy(sourceUrl = sourceUrl)
        }
    }

    /**
     * Read the server's answer.
     *
     * @return The server, or null if the answer made no sense.
     */
    private fun parse(payload: String): Server? {
        return try {
            val json = JSONObject(payload)
            val url = json.optString("url")
            if (url.isNullOrBlank()) {
                null
            } else {
                Server(
                    name = json.optString("name", "PiKaraoke"),
                    url = url,
                    sourceUrl = url,
                    port = json.optInt("port", DEFAULT_PORT)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the discovery answer", e)
            null
        }
    }
}
