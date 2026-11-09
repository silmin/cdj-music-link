package cdjmusiclink.fakecdj

fun main() {
    val config = FakeConfig.fromEnv(System.getenv())
    Packets.template = config.template
    val deck = Deck(config.bpm, config.trackId, config.slot, config.trackType, config.number, config.sourcePlayer)
    if (config.autoplay) deck.play(System.currentTimeMillis())
    // Real CDJs send and receive on the three fixed ports; binding them lets rekordbox treat us like one.
    val announce = Peers.bound(Peers.ANNOUNCE_PORT)
    val beats = Peers.bound(Peers.BEAT_PORT)
    val status = Peers.bound(Peers.STATUS_PORT)
    lateinit var commands: Commands
    val peers = Peers(announce, selfAddresses = Peers.localAddresses()) { addr, number ->
        println("rekordbox seen: device $number at ${addr.hostAddress}; linking")
        commands.linkTo(addr, number)
    }
    val fake = FakeCdj(config, deck, peers, beats, status)
    commands = Commands(status, deck, config, fake.iface.ip.address)
    val control = Control(config, deck, peers)
    peers.start()
    commands.start()
    fake.start()
    control.start()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            control.stop()
            fake.stop()
            commands.stop()
            peers.stop()
        },
    )
    Thread.currentThread().join()
}
