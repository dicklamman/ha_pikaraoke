package com.dicklamman.pikaraoke

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
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
object Discovery {

    private const val TAG = "PiKaraokeDiscovery"

    /** The question the server recognises. */
    private const val REQUEST = "PIKARAOKE_DISCOVER_V1"

    /** The port a player tries first. It matches the web port. */
    const val DEFAULT_PORT = 5555

    /** How long to wait for an answer before giving up on a port. */
    private const val TIMEOUT_MS = 1500

    /** How many times to ask before giving up on a port. */
    private const val ATTEMPTS = 2

    /** One server that answered. */
    data class Server(
        val name: String,
        val url: String,
        val port: Int
    )

    /**
     * Look for a PiKaraoke server.
     *
     * The broadcast address is asked first, because a server on the same
     * network answers it, and then the local network's own address as a
     * fallback for networks where broadcast is switched off.
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
            if (local != null && !local.isLoopbackAddress) {
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
     * The gateway is read from the routing table rather than assumed, because
     * its address differs from one network to the next.
     */
    private fun gatewayTargets(): List<InetAddress> {
        val gateways = LinkedHashSet<InetAddress>()

        try {
            val reader = BufferedReader(FileReader("/proc/net/route"))
            try {
                // The routing table's columns are interface, destination,
                // gateway, flags and so on. The gateway is the third column,
                // and it is in host byte order.
                reader.forEachLine { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    if (columns.size > 2 && columns[2] != "00000000") {
                        val address = littleEndianAddress(columns[2])
                        if (address != null) {
                            gateways.add(address)
                        }
                    }
                }
            } finally {
                reader.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the routing table", e)
        }

        return gateways.toList()
    }

    /**
     * Read a gateway address from the routing table.
     *
     * The table writes addresses as a little-endian hex number, so the bytes
     * have to be read in the opposite order to the way they are written.
     */
    private fun littleEndianAddress(hex: String): InetAddress? {
        return try {
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                bytes[3 - i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            InetAddress.getByAddress(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the gateway address $hex", e)
            null
        }
    }

    /**
     * Ask one address for a server.
     *
     * @return The server that answered, or null if it did not.
     */
    private fun ask(address: InetAddress, port: Int): Server? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = TIMEOUT_MS

            val request = REQUEST.toByteArray()
            socket.send(
                DatagramPacket(
                    request,
                    request.size,
                    InetSocketAddress(address, port)
                )
            )

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

            return parse(String(reply.data, 0, reply.length))
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
                    port = json.optInt("port", DEFAULT_PORT)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the discovery answer", e)
            null
        }
    }
}
