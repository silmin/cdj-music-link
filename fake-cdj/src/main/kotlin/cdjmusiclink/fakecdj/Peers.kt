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
    private val bind: InetAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)),
    private val selfAddresses: Set<InetAddress>,
    private val port: Int = ANNOUNCE_PORT,
    private val staleMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seen = ConcurrentHashMap<InetAddress, Long>()
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(bind, port))
        socket = s
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
        socket?.close()
    }

    fun addresses(): Set<InetAddress> {
        val now = clock()
        seen.entries.removeIf { now - it.value > staleMs }
        return seen.keys.toSet()
    }

    companion object {
        const val ANNOUNCE_PORT = 50000

        /** Every address bound to a local interface, so our own broadcasts are never mistaken for peers. */
        fun localAddresses(): Set<InetAddress> =
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    }
}
