package cdjmusiclink.fakecdj

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** The network interface the fake announces on. */
data class Iface(val name: String, val ip: Inet4Address, val broadcast: InetAddress, val mac: ByteArray) {
    companion object {
        fun resolve(wanted: String?, number: Int): Iface {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (wanted == null || it.name == wanted) }
            for (ni in candidates) {
                val ia = ni.interfaceAddresses.firstOrNull { it.address is Inet4Address } ?: continue
                val broadcast = ia.broadcast ?: continue
                val mac = ni.hardwareAddress?.takeIf { it.size == 6 }
                    ?: byteArrayOf(0x02, 0, 0, 0, 0, number.toByte())
                return Iface(ni.name, ia.address as Inet4Address, broadcast, mac)
            }
            error("no usable IPv4 interface" + (wanted?.let { " named $it" } ?: ""))
        }
    }
}

/** Runs the keep-alive, status and beat loops for one simulated CDJ. */
class FakeCdj(
    private val config: FakeConfig,
    private val deck: Deck,
    private val peers: Peers,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    val iface: Iface = Iface.resolve(config.iface, config.number)

    fun start() {
        val sock = DatagramSocket(0).apply { broadcast = true }
        socket = sock
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        println(
            "fake-cdj #${config.number} '${config.name}' iface=${iface.name} ip=${iface.ip.hostAddress} " +
                "broadcast=${iface.broadcast.hostAddress}",
        )
        s.launch { keepAliveLoop(sock) }
        s.launch { statusLoop(sock) }
        s.launch { beatLoop(sock) }
    }

    fun stop() {
        scope?.cancel()
        socket?.close()
    }

    private fun send(sock: DatagramSocket, data: ByteArray, to: InetAddress, port: Int) {
        try {
            sock.send(DatagramPacket(data, data.size, to, port))
        } catch (e: java.io.IOException) {
            if (!sock.isClosed) System.err.println("send to $to:$port failed: ${e.message}")
        }
    }

    private suspend fun keepAliveLoop(sock: DatagramSocket) {
        val packet = Packets.keepAlive(config.name, config.number, iface.mac, iface.ip.address)
        while (currentCoroutineContext().isActive) {
            send(sock, packet, iface.broadcast, 50000)
            delay(1500)
        }
    }

    private suspend fun statusLoop(sock: DatagramSocket) {
        var counter = 0
        while (currentCoroutineContext().isActive) {
            val st = deck.snapshot(clock())
            val packet = Packets.status(
                StatusParams(
                    name = config.name, number = config.number, playing = st.playing, master = st.master,
                    synced = st.synced, onAir = st.onAir, bpmX100 = st.bpmX100, beatNumber = st.beatNumber,
                    beatWithinBar = st.beatWithinBar, trackId = st.trackId, trackLoaded = st.trackLoaded,
                    slot = st.slot, trackType = st.trackType, packetCounter = counter++, syncCounter = 1,
                ),
            )
            for (peer in peers.addresses()) send(sock, packet, peer, 50002)
            delay(200)
        }
    }

    private suspend fun beatLoop(sock: DatagramSocket) {
        var lastBeat = -1
        while (currentCoroutineContext().isActive) {
            val now = clock()
            val st = deck.snapshot(now)
            if (!st.playing) {
                lastBeat = -1
                delay(50)
                continue
            }
            if (st.beatNumber != lastBeat) {
                lastBeat = st.beatNumber
                val interval = st.beatIntervalMs
                val packet = Packets.beat(
                    config.name, config.number, st.bpmX100, Packets.PITCH_NEUTRAL, st.beatWithinBar,
                    interval, (5 - st.beatWithinBar) * interval,
                )
                send(sock, packet, iface.broadcast, 50001)
            }
            delay(deck.msUntilNextBeat(clock()).toLong())
        }
    }
}
