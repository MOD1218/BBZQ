package io.github.bbzq.hooks

import java.util.concurrent.ConcurrentHashMap

internal object AutoLikeState {
    @Volatile
    private var detail: Pair<Long, Int>? = null

    private val likedVideos = ConcurrentHashMap.newKeySet<Long>()

    fun update(aid: Long, like: Int) {
        if (aid > 0) {
            detail = aid to like
        }
    }

    fun canClick(): Boolean {
        val (aid, like) = detail ?: return false
        if (aid <= 0L || like != 0) return false
        return aid !in likedVideos
    }

    fun markClicked() {
        val (aid, like) = detail ?: return
        if (aid > 0L && like == 0) {
            likedVideos.add(aid)
        }
    }

    fun hasDetail(): Boolean {
        val (aid, _) = detail ?: return false
        return aid > 0L
    }
}
