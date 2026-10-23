package cdjmusiclink.server.link

import cdjmusiclink.server.Config
import cdjmusiclink.server.model.Device
import cdjmusiclink.server.model.deviceKindOf
import cdjmusiclink.server.state.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.deepsymmetry.beatlink.BeatFinder
import org.deepsymmetry.beatlink.DeviceFinder
import org.deepsymmetry.beatlink.LifecycleListener
import org.deepsymmetry.beatlink.LifecycleParticipant
import org.deepsymmetry.beatlink.VirtualCdj
import org.deepsymmetry.beatlink.data.ArtFinder
import org.deepsymmetry.beatlink.data.BeatGridFinder
import org.deepsymmetry.beatlink.data.MetadataFinder
import org.deepsymmetry.beatlink.data.TimeFinder
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.slf4j.LoggerFactory

/**
 * beat-link の起動・停止・再試行を担う。ネットワーク依存のため単体テストはしない（判断部分は [Recovery]）。
 *
 * beat-link の LifecycleListener 通知は毎回新しいスレッドで非同期に配送される。さらに MetadataFinder などの
 * 停止ログは `VirtualCdj.toString()`（同期化された `getDeviceNumber()`）を呼ぶため、次の `VirtualCdj.start()`
 * がデバイス待ちでロックを握っている間は配送が止まり、起動直後に古い停止通知が届いて新しい Finder を止める。
 * これを避けるため、停止は Finder を逆順に同期停止し、通知配送が終わるまで待ってから再起動する。
 * また停止通知や deviceLost は「再評価のきっかけ」としてだけ扱い、落とすかどうかは評価時点の実状態で決める。
 */
class LinkSession(
    private val config: Config,
    private val store: Store,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(LinkSession::class.java)
    private val _online = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var attached: Listeners.Attached? = null

    val online: StateFlow<Boolean> = _online

    val deviceNumber: Int?
        get() = VirtualCdj.getInstance().takeIf { it.isRunning }?.deviceNumber?.toInt()

    private val lifecycle = object : LifecycleListener {
        override fun started(sender: LifecycleParticipant) = Unit
        override fun stopped(sender: LifecycleParticipant) {
            wake.trySend(Unit)
        }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        attached = Listeners.attach(store, scope, onAllDevicesLost = { wake.trySend(Unit) })
        VirtualCdj.getInstance().addLifecycleListener(lifecycle)
        job = scope.launch(Dispatchers.IO) { supervise() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        VirtualCdj.getInstance().removeLifecycleListener(lifecycle)
        attached?.detach()
        attached = null
        stopAll()
        setOnline(false)
    }

    private fun setOnline(flag: Boolean) {
        store.online(flag)
        _online.value = flag
    }

    private suspend fun supervise() {
        while (currentCoroutineContext().isActive) {
            try {
                if (!bringUp()) {
                    delay(RETRY_MS)
                    continue
                }
                awaitDown()
                log.info("link went offline, restarting")
            } finally {
                withContext(NonCancellable) { tearDown() }
            }
        }
    }

    /** 通知またはポーリングのたびに実状態を見て、落ちていれば戻る。 */
    private suspend fun awaitDown() {
        while (true) {
            withTimeoutOrNull(POLL_MS) { wake.receive() }
            val cdj = VirtualCdj.getInstance().isRunning
            val devices = DeviceFinder.getInstance().currentDevices.size
            if (Recovery.isDown(cdj, devices)) return
        }
    }

    /** 成功なら true。`VirtualCdj.start()` は最大 100 秒ほどブロックする。 */
    private fun bringUp(): Boolean {
        try {
            DeviceFinder.getInstance().start()
            val cdj = VirtualCdj.getInstance()
            cdj.setDeviceName(config.deviceName)
            cdj.setUseStandardPlayerNumber(config.deviceNumber == null)
            config.deviceNumber?.let { cdj.setDeviceNumber(it.toByte()) }
            if (!cdj.start()) {
                log.warn("VirtualCdj did not start, retrying in {} ms", RETRY_MS)
                return false
            }
            MetadataFinder.getInstance().setPassive(cdj.deviceNumber > MAX_ACTIVE_NUMBER)
            BeatFinder.getInstance().start()
            MetadataFinder.getInstance().start()
            ArtFinder.getInstance().start()
            BeatGridFinder.getInstance().start()
            WaveformFinder.getInstance().apply {
                setPreferredStyle(WaveformFinder.WaveformStyle.RGB)
                setFindDetails(true)
                start()
            }
            TimeFinder.getInstance().start()
            DeviceFinder.getInstance().currentDevices.forEach {
                store.upsertDevice(Device(it.deviceNumber, it.deviceName, it.address.hostAddress, deviceKindOf(it.deviceNumber)))
            }
            setOnline(true)
            log.info("link online as player {}", cdj.deviceNumber)
            return true
        } catch (e: Exception) {
            log.warn("failed to start beat-link", e)
            return false
        }
    }

    /** 同期停止したうえで、beat-link の停止カスケードと通知配送が静まるまで待ち、溜まった通知を捨てる。 */
    private suspend fun tearDown() {
        stopAll()
        setOnline(false)
        val quiet = Recovery.awaitQuiet(SETTLE_TIMEOUT_MS, SETTLE_POLL_MS) { anyRunning() || lifecycleDeliveryInFlight() }
        if (!quiet) log.warn("beat-link did not settle within {} ms after stop", SETTLE_TIMEOUT_MS)
        wake.tryReceive()
    }

    /**
     * 起動の逆順で同期的に止める。依存先より先に止めておくことで、後から届く停止通知は
     * `isRunning()` が false の Finder に当たって何もしない（ログ出力による VirtualCdj ロック待ちも起きない）。
     * DeviceFinder は再出現を検知するため止めない。
     */
    private fun stopAll() {
        listOf<Pair<String, () -> Unit>>(
            "TimeFinder" to { TimeFinder.getInstance().stop() },
            "WaveformFinder" to { WaveformFinder.getInstance().stop() },
            "BeatGridFinder" to { BeatGridFinder.getInstance().stop() },
            "ArtFinder" to { ArtFinder.getInstance().stop() },
            "MetadataFinder" to { MetadataFinder.getInstance().stop() },
            "BeatFinder" to { BeatFinder.getInstance().stop() },
            "VirtualCdj" to { VirtualCdj.getInstance().stop() },
        ).forEach { (name, stop) ->
            try {
                stop()
            } catch (e: Exception) {
                log.warn("failed to stop {}", name, e)
            }
        }
    }

    private fun anyRunning(): Boolean =
        TimeFinder.getInstance().isRunning ||
            WaveformFinder.getInstance().isRunning ||
            BeatGridFinder.getInstance().isRunning ||
            ArtFinder.getInstance().isRunning ||
            MetadataFinder.getInstance().isRunning ||
            BeatFinder.getInstance().isRunning ||
            VirtualCdj.getInstance().isRunning

    /** beat-link の LifecycleParticipant が通知配送用に立てるスレッドがまだ生きているか。 */
    private fun lifecycleDeliveryInFlight(): Boolean =
        Thread.getAllStackTraces().keys.any { it.isAlive && it.name == LIFECYCLE_DELIVERY_THREAD }

    private companion object {
        const val RETRY_MS = 5_000L
        const val POLL_MS = 2_000L
        const val SETTLE_TIMEOUT_MS = 10_000L
        const val SETTLE_POLL_MS = 100L
        const val MAX_ACTIVE_NUMBER = 4
        const val LIFECYCLE_DELIVERY_THREAD = "Lifecycle announcement delivery"
    }
}
