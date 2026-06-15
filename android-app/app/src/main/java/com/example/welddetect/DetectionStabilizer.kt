package com.example.welddetect

import android.graphics.RectF
import kotlin.math.sqrt

data class StableFrame(
    val detections: List<Detection>,
    val newlyConfirmed: List<Detection>,
)

/**
 * 检测框时序稳定器。
 *
 * 设计目标:把"是否显示"(门控)与"显示在哪"(平滑)彻底解耦,避免老版本
 * "短暂缺陷被攒帧逻辑抹掉" + "缺陷离开后残影停在旧位置"两个问题:
 *
 *  - 高置信度检测 (score >= [instantThreshold]) 第一帧即确认显示,不被去抖逻辑吞掉;
 *  - 低置信度检测需累计 [minHits] 帧确认,仅用于压制抖动闪烁;
 *  - 只绘制最近 [displayMisses] 帧内仍被检出的 track,缺陷消失后立即收框,杜绝幽灵残影;
 *    track 本身保留到 [maxMisses] 帧,用于短暂丢失后的重新关联(不绘制);
 *  - 位置用自适应 EMA:帧间位移大时少平滑(跟手),位移小时多平滑(去抖);
 *  - 关联同时看 IoU 与中心距离,抗手持相机抖动与帧间大位移导致的 IoU 骤降。
 */
class DetectionStabilizer(
    private val minHits: Int = 2,
    private val instantThreshold: Float = 0.5f,
    private val maxMisses: Int = 3,
    private val displayMisses: Int = 1,
    private val matchIou: Float = 0.2f,
    private val matchCenterRatio: Float = 0.6f,
    private val minSmoothing: Float = 0.2f,
    private val maxSmoothing: Float = 0.6f,
) {
    private data class Track(
        val id: Int,
        val label: String,
        var box: RectF,
        var score: Float,
        var hits: Int = 1,
        var misses: Int = 0,
        var confirmed: Boolean = false,
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun update(raw: List<Detection>): StableFrame {
        tracks.forEach { it.misses++ }
        val matched = mutableSetOf<Int>()
        val newlyConfirmed = mutableListOf<Detection>()

        for (detection in raw.sortedByDescending { it.score }) {
            val existing = bestMatch(detection, matched)
            val track = if (existing == null) {
                Track(
                    id = nextId++,
                    label = detection.label,
                    box = RectF(detection.box),
                    score = detection.score,
                ).also { tracks += it }
            } else {
                existing.box = smooth(existing.box, detection.box)
                existing.score = maxOf(existing.score * 0.6f + detection.score * 0.4f, detection.score)
                existing.hits++
                existing.misses = 0
                existing
            }
            matched += track.id

            // 门控:高分立即确认,低分攒够帧数再确认。一旦确认不再回退。
            if (!track.confirmed && (track.hits >= minHits || track.score >= instantThreshold)) {
                track.confirmed = true
                newlyConfirmed += track.toDetection()
            }
        }

        tracks.removeAll { it.misses > maxMisses }
        return StableFrame(
            // 只画近期仍被检出的框,缺陷一旦离开立刻收框(不等到 maxMisses)
            detections = tracks
                .filter { it.confirmed && it.misses <= displayMisses }
                .map { it.toDetection() },
            newlyConfirmed = newlyConfirmed,
        )
    }

    /** 在未匹配的 track 中选与该检测关联度最高者;IoU 达标或中心足够近即可关联。 */
    private fun bestMatch(detection: Detection, matched: Set<Int>): Track? {
        var best: Track? = null
        var bestScore = 0f
        for (track in tracks) {
            if (track.label != detection.label || track.id in matched) continue
            val overlap = iou(track.box, detection.box)
            val close = centerCloseness(track.box, detection.box)
            if (overlap < matchIou && close <= 0f) continue
            val s = maxOf(overlap, close)
            if (s > bestScore) {
                bestScore = s
                best = track
            }
        }
        return best
    }

    /** 中心接近度:中心距 < 平均框尺度 * [matchCenterRatio] 时返回 (0,1],否则 0。 */
    private fun centerCloseness(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        val dist = sqrt(dx * dx + dy * dy)
        val scale = (a.width() + a.height() + b.width() + b.height()) / 4f
        if (scale <= 0f) return 0f
        val limit = scale * matchCenterRatio
        return if (dist >= limit) 0f else 1f - dist / limit
    }

    private fun Track.toDetection(): Detection =
        Detection(RectF(box), label, score)

    /** 自适应 EMA:位移越大保留旧框越少(跟手),位移越小保留越多(去抖)。 */
    private fun smooth(previous: RectF, current: RectF): RectF {
        val dx = previous.centerX() - current.centerX()
        val dy = previous.centerY() - current.centerY()
        val dist = sqrt(dx * dx + dy * dy)
        val scale = (current.width() + current.height()) / 2f
        val motion = if (scale <= 0f) 0f else (dist / scale).coerceIn(0f, 1f)
        val keep = maxSmoothing - (maxSmoothing - minSmoothing) * motion
        val add = 1f - keep
        return RectF(
            previous.left * keep + current.left * add,
            previous.top * keep + current.top * add,
            previous.right * keep + current.right * add,
            previous.bottom * keep + current.bottom * add,
        )
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
