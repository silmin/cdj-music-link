package cdjmusiclink.fakecdj

fun main() {
    val config = FakeConfig.fromEnv(System.getenv())
    val deck = Deck(config.bpm, config.trackId, config.slot, config.trackType)
    if (config.autoplay) deck.play(System.currentTimeMillis())
    val peers = Peers(selfAddresses = Peers.localAddresses())
    val fake = FakeCdj(config, deck, peers)
    val control = Control(config, deck, peers)
    peers.start()
    fake.start()
    control.start()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            control.stop()
            fake.stop()
            peers.stop()
        },
    )
    Thread.currentThread().join()
}
