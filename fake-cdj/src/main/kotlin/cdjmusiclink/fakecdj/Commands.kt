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
    private val ownIp: ByteArray,
    private val log: (String) -> Unit = ::println,
) {
    private val seen = HashSet<String>()
    @Volatile private var rekordbox: Pair<java.net.InetAddress, Int>? = null

    /**
     * What a real player does when rekordbox appears (observed with rekordbox 7): greet it (0x10), ask about its
     * collection (0x05 media query, slot 4) and send one link ping (0x46). rekordbox answers 0x11 / 0x06 / 0x47 and
     * from then on treats us as a linked player it can load tracks onto.
     */
    fun linkTo(addr: java.net.InetAddress, number: Int) {
        rekordbox = addr to number
        Thread({
            try {
                send(Packets.hello(config.name, config.number), addr, "hello 0x10")
                Thread.sleep(300)
                send(Packets.mediaQuery(config.name, config.number, ownIp, number, Packets.SLOT_COLLECTION), addr, "media query 0x05")
                Thread.sleep(100)
                send(Packets.linkPing(config.name, config.number), addr, "link ping 0x46")
            } catch (_: InterruptedException) {
            }
        }, "link-handshake").apply { isDaemon = true }.start()
    }

    private fun send(bytes: ByteArray, to: java.net.InetAddress, what: String) {
        try {
            socket.send(DatagramPacket(bytes, bytes.size, to, Peers.STATUS_PORT))
            log("sent $what to ${to.hostAddress}")
        } catch (e: java.io.IOException) {
            if (!socket.isClosed) log("send $what failed: ${e.message}")
        }
    }

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
            0x16 -> {
                // rekordbox: "re-read my export list" -> a player re-queries the collection
                if (seen.add("16/${p.address.hostAddress}")) {
                    log("packet type 0x16 from ${p.address.hostAddress}: ${Packets.hex(p.data, p.length)}")
                }
                rekordbox?.let { (addr, number) ->
                    if (addr == p.address) send(Packets.mediaQuery(config.name, config.number, ownIp, number, Packets.SLOT_COLLECTION), addr, "media query 0x05 (after 0x16)")
                }
                probeReply(type, p)
            }
            else -> {
                if (seen.add("$type/${p.address.hostAddress}")) {
                    log("packet type 0x%02x from %s:%d (%d bytes): %s".format(type, p.address.hostAddress, p.port, p.length, Packets.hex(p.data, p.length)))
                }
                probeReply(type, p)
            }
        }
    }

    private fun probeReply(type: Int, p: DatagramPacket) {
        if (type != config.linkRequestType) return
        val reply = Packets.raw(config.linkReplyType, config.name, config.number, config.linkReplyPayload)
        socket.send(DatagramPacket(reply, reply.size, p.address, p.port))
        log("replied type 0x%02x to %s:%d: %s".format(config.linkReplyType, p.address.hostAddress, p.port, Packets.hex(reply)))
    }

    fun stop() {
        socket.close()
    }
}
