package cdjmusiclink.fakecdj

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Learns peers by listening on the Pro DJ Link announce port. Every datagram from an address that is not ours marks
 * that address as a peer; peers silent for [staleMs] are dropped.
 */
class Peers(
    /** Socket bound to the announce port (50000); also used to send our own keep-alives from that port. */
    val socket: DatagramSocket,
    private val selfAddresses: Set<InetAddress>,
    private val staleMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seen = ConcurrentHashMap<InetAddress, Long>()

    fun start() {
        val s = socket
        Thread({
            val buf = ByteArray(2048)
            while (!s.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (p.address !in selfAddresses) seen[p.address] = clock()
                } catch (_: java.io.IOException) {
                    // closed by stop()
                }
            }
        }, "peers-listener").apply { isDaemon = true }.start()
    }

    fun stop() {
        socket.close()
    }

    fun addresses(): Set<InetAddress> {
        val now = clock()
        seen.entries.removeIf { now - it.value > staleMs }
        return seen.keys.toSet()
    }

    companion object {
        const val ANNOUNCE_PORT = 50000
        const val BEAT_PORT = 50001
        const val STATUS_PORT = 50002

        /** A UDP socket bound to [port] on all interfaces, with broadcast allowed, like a real CDJ's fixed ports. */
        fun bound(port: Int): DatagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), port))
        }

        /** Every address bound to a local interface, so our own broadcasts are never mistaken for peers. */
        fun localAddresses(): Set<InetAddress> =
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    }
}
