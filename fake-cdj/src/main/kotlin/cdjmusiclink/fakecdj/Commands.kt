package cdjmusiclink.fakecdj

import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Listens on the status port (50002) for load-track commands and applies them to the deck, like a real CDJ does when
 * rekordbox (LINK EXPORT) or another device asks it to load a track. Status packets (0x0a / 0x29) are ignored;
 * other types are logged once per (type, sender) as hex so unknown exchanges can be studied.
 *
 * Experimental: [FakeConfig.linkRequestType] makes it answer that type with a configurable packet (see FakeConfig).
 */
class Commands(
    private val socket: DatagramSocket,
    private val deck: Deck,
    private val config: FakeConfig,
    private val log: (String) -> Unit = ::println,
) {
    private val seen = HashSet<String>()

    fun start() {
        Thread({
            val buf = ByteArray(2048)
            while (!socket.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    handle(p)
                } catch (_: java.io.IOException) {
                    // closed by stop()
                }
            }
        }, "commands-listener").apply { isDaemon = true }.start()
    }

    private fun handle(p: DatagramPacket) {
        val type = Packets.typeOf(p.data, p.length) ?: return
        when (type) {
            0x0a, 0x29 -> return // status from players / mixers / rekordbox
            0x19 -> {
                val cmd = Packets.parseLoadTrack(p.data, p.length) ?: return
                val note = if (cmd.targetPlayer != config.number) " (addressed to player ${cmd.targetPlayer}, loading anyway)" else ""
                log(
                    "load command from ${p.address.hostAddress} (device ${cmd.requester}): track ${cmd.trackId} " +
                        "slot ${cmd.slot} type ${cmd.trackType} source ${cmd.sourcePlayer}$note",
                )
                deck.load(cmd.trackId, cmd.slot, cmd.trackType, cmd.sourcePlayer)
            }
            else -> {
                if (seen.add("$type/${p.address.hostAddress}")) {
                    log("packet type 0x%02x from %s:%d (%d bytes): %s".format(type, p.address.hostAddress, p.port, p.length, Packets.hex(p.data, p.length)))
                }
                if (type == config.linkRequestType) {
                    val reply = Packets.raw(config.linkReplyType, config.name, config.number, config.linkReplyPayload)
                    socket.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    log("replied type 0x%02x to %s:%d: %s".format(config.linkReplyType, p.address.hostAddress, p.port, Packets.hex(reply)))
                }
            }
        }
    }

    fun stop() {
        socket.close()
    }
}
