package cdjmusiclink.fakecdj

import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Listens on the status port (50002) for load-track commands and applies them to the deck, like a real CDJ does when
 * rekordbox (LINK EXPORT) or another device asks it to load a track. Everything else arriving there is ignored.
 */
class Commands(
    private val socket: DatagramSocket,
    private val deck: Deck,
    private val number: Int,
    private val log: (String) -> Unit = ::println,
) {
    fun start() {
        Thread({
            val buf = ByteArray(2048)
            while (!socket.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val cmd = Packets.parseLoadTrack(p.data, p.length) ?: continue
                    val note = if (cmd.targetPlayer != number) " (addressed to player ${cmd.targetPlayer}, loading anyway)" else ""
                    log(
                        "load command from ${p.address.hostAddress} (device ${cmd.requester}): track ${cmd.trackId} " +
                            "slot ${cmd.slot} type ${cmd.trackType} source ${cmd.sourcePlayer}$note",
                    )
                    deck.load(cmd.trackId, cmd.slot, cmd.trackType, cmd.sourcePlayer)
                } catch (_: java.io.IOException) {
                    // closed by stop()
                }
            }
        }, "commands-listener").apply { isDaemon = true }.start()
    }

    fun stop() {
        socket.close()
    }
}
