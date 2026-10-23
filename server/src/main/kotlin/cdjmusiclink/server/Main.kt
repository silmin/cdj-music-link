package cdjmusiclink.server

import cdjmusiclink.server.http.apiModule
import cdjmusiclink.server.link.LinkAssetSource
import cdjmusiclink.server.link.LinkSession
import cdjmusiclink.server.link.LinkSessionInfo
import cdjmusiclink.server.state.EventBus
import cdjmusiclink.server.state.Store
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object BuildInfo {
    const val VERSION = "0.1.0"
}

fun main() {
    val config = Config.fromEnv()
    // slf4j-simple はロガー初期化時に読むため、何かがログを出す前に設定する。
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", config.logLevel)

    val bus = EventBus()
    val store = Store(bus, System::currentTimeMillis)
    val session = LinkSession(config, store, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    session.start()

    embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
        apiModule(store, LinkSessionInfo(session, store, BuildInfo.VERSION), LinkAssetSource(), bus)
    }.start(wait = true)
}
