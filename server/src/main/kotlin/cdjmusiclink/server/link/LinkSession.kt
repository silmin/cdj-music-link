package cdjmusiclink.server.link

import cdjmusiclink.server.Config
import cdjmusiclink.server.model.Device
import cdjmusiclink.server.model.deviceKindOf
import cdjmusiclink.server.state.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** beat-link の起動・停止・再試行を担う。ネットワーク依存のため単体テストはしない。 */
class LinkSession(
    private val config: Config,
    private val store: Store,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(LinkSession::class.java)
    private val _online = MutableStateFlow(false)
    private val offline = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var attached: Listeners.Attached? = null

    val online: StateFlow<Boolean> = _online

    val deviceNumber: Int?
        get() = VirtualCdj.getInstance().takeIf { it.isRunning }?.deviceNumber?.toInt()

    private val lifecycle = object : LifecycleListener {
        override fun started(sender: LifecycleParticipant) = Unit
        override fun stopped(sender: LifecycleParticipant) {
            offline.trySend(Unit)
        }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        attached = Listeners.attach(store, scope, onAllDevicesLost = { offline.trySend(Unit) })
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
        val cdj = VirtualCdj.getInstance()
        if (cdj.isRunning) cdj.stop()
        setOnline(false)
    }

    private fun setOnline(flag: Boolean) {
        store.online(flag)
        _online.value = flag
    }

    private suspend fun supervise() {
        while (scope.isActive && job?.isActive != false) {
            try {
                if (!bringUp()) {
                    delay(RETRY_MS)
                    continue
                }
                offline.receive()
                log.info("link went offline, restarting")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { tearDown() }
            }
        }
    }

    /** 成功なら true。`VirtualCdj.start()` は最大 100 秒ほどブロックする。 */
    private fun bringUp(): Boolean {
        offline.tryReceive()
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

    private fun tearDown() {
        try {
            val cdj = VirtualCdj.getInstance()
            if (cdj.isRunning) cdj.stop()
        } catch (e: Exception) {
            log.warn("failed to stop VirtualCdj", e)
        }
        setOnline(false)
    }

    private companion object {
        const val RETRY_MS = 5_000L
        const val MAX_ACTIVE_NUMBER = 4
    }
}
