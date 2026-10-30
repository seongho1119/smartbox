package io.smartbox.core

/**
 * 루프 녹화 조각(segment) 관리 로직. 파일 입출력은 하지 않고 "무엇을 보관/삭제할지"만 정한다.
 *
 * 스마트폰은 짧은 조각(예: 20초)으로 계속 녹화하고, 저장 공간 한도를 넘으면 오래된 조각부터 지운다.
 * 이벤트가 생기면 이벤트 앞뒤 구간과 겹치는 조각을 '보호'해 지워지지 않게 한다.
 */
class SegmentStore(private val maxUnprotectedBytes: Long) {

    data class Segment(
        val name: String,
        val startMs: Long,
        val endMs: Long,
        val bytes: Long,
        val protectedBy: MutableSet<String> = mutableSetOf(),
    ) {
        val isProtected get() = protectedBy.isNotEmpty()
        fun overlaps(from: Long, to: Long) = startMs < to && endMs > from
    }

    private val segments = mutableListOf<Segment>()

    /** 아직 조각이 끝나지 않아 보호하지 못한 이벤트 구간. */
    private val pending = mutableListOf<Triple<String, Long, Long>>()

    val all: List<Segment> get() = segments.toList()

    /**
     * 녹화가 끝난 조각을 등록한다.
     * @return 공간 확보를 위해 삭제해야 할 조각 이름 목록
     */
    fun add(seg: Segment): List<String> {
        segments += seg
        segments.sortBy { it.startMs }
        pending.forEach { (id, from, to) -> if (seg.overlaps(from, to)) seg.protectedBy += id }
        return evict()
    }

    /**
     * 이벤트 구간 [fromMs, toMs]를 보호한다. 이미 끝난 조각은 바로 보호하고,
     * 앞으로 녹화될 조각을 위해 구간을 기억해 둔다.
     */
    fun protect(eventId: String, fromMs: Long, toMs: Long) {
        segments.filter { it.overlaps(fromMs, toMs) }.forEach { it.protectedBy += eventId }
        pending += Triple(eventId, fromMs, toMs)
    }

    /** 이벤트 구간이 모두 녹화되었으면 더 이상 기다릴 필요가 없다. */
    fun settle(nowMs: Long) {
        pending.removeAll { it.third < nowMs }
    }

    /** 이벤트 영상을 따로 복사해 둔 뒤, 루프 녹화 조각의 보호를 푼다. */
    fun release(eventId: String): List<String> {
        pending.removeAll { it.first == eventId }
        segments.forEach { it.protectedBy -= eventId }
        return evict()
    }

    fun segmentsFor(eventId: String): List<Segment> =
        segments.filter { eventId in it.protectedBy }.sortedBy { it.startMs }

    fun isComplete(eventId: String, nowMs: Long): Boolean =
        pending.none { it.first == eventId } || pending.first { it.first == eventId }.third < nowMs

    private fun evict(): List<String> {
        val removed = mutableListOf<String>()
        var total = segments.filter { !it.isProtected }.sumOf { it.bytes }
        val it = segments.iterator()
        while (total > maxUnprotectedBytes && it.hasNext()) {
            val s = it.next()
            if (s.isProtected) continue
            it.remove()
            total -= s.bytes
            removed += s.name
        }
        return removed
    }
}
