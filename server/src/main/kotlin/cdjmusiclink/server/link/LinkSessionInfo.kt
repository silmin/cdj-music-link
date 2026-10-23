package cdjmusiclink.server.link

import cdjmusiclink.server.http.SessionInfo
import cdjmusiclink.server.state.Store

class LinkSessionInfo(
    private val session: LinkSession,
    private val store: Store,
    override val version: String,
) : SessionInfo {
    override val online: Boolean get() = store.isOnline()
    override val deviceNumber: Int? get() = session.deviceNumber
}
