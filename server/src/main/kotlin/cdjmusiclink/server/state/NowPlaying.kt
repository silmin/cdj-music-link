package cdjmusiclink.server.state

import cdjmusiclink.server.model.Player

/** `/v1/now-playing` の選択規則(openapi.yaml の説明と同じ)。 */
object NowPlaying {
    fun pick(players: List<Player>, mixerPresent: Boolean): Player? {
        val candidates = players.filter { it.isPlaying && (!mixerPresent || it.isOnAir == true) }
        return candidates.filter { it.isTempoMaster }.minByOrNull { it.number }
            ?: candidates.minByOrNull { it.number }
    }
}
