package cdjmusiclink.server.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 再接続判断の純粋な部分。beat-link に依存しない。 */
internal object Recovery {
    /**
     * リンクを落として待機に戻すべきか。通知の種類や発生元では判断せず、評価時点の実状態だけで決める。
     * beat-link の停止通知は別スレッドで遅れて届くため、自分で止めた分の通知が再起動後に来ても誤判定しない。
     */
    fun isDown(virtualCdjRunning: Boolean, deviceCount: Int): Boolean = !virtualCdjRunning || deviceCount == 0

    /** [busy] が false になるまで [pollMs] 間隔で待つ。[timeoutMs] 以内に静まれば true。 */
    suspend fun awaitQuiet(timeoutMs: Long, pollMs: Long, busy: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (busy()) delay(pollMs)
            true
        } ?: false
}
