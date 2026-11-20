package com.paradown.app.download

/**
 * Visão imutável de um segmento. Offsets são inclusivos.
 */
data class SegmentSnapshot(
    val index: Int,
    val start: Long,
    val end: Long,
    val position: Long,
    val claimedBy: Int? = null,
) {
    val totalBytes: Long
        get() = (end - start + 1).coerceAtLeast(0L)

    val downloadedBytes: Long
        get() = (position - start).coerceIn(0L, totalBytes)

    val remainingBytes: Long
        get() = (end + 1 - position).coerceAtLeast(0L)

    val isComplete: Boolean
        get() = position > end

    val progressFraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

data class SegmentWork(
    val index: Int,
    val start: Long,
    val end: Long,
    val position: Long,
)

/**
 * Divisor e coordenador puro (sem Android) dos segmentos de um arquivo.
 *
 * Regras principais:
 *  - cobre exatamente [0, totalBytes - 1] sem lacunas ou sobreposição;
 *  - um worker só trabalha em um segmento por vez;
 *  - ao terminar, o worker tenta "roubar" metade do maior segmento ainda em
 *    andamento (work stealing), mantendo a mesma quantidade de conexões;
 *  - ao reduzir conexões, o segmento é liberado e pode ser assumido por outro
 *    worker, sem perder o offset já gravado.
 */
class SegmentScheduler(
    val totalBytes: Long,
    initialConnections: Int,
    existing: List<SegmentSnapshot> = emptyList(),
    private val minSplitBytes: Long = DEFAULT_MIN_SPLIT_BYTES,
) {
    private data class MutableSegment(
        val index: Int,
        val start: Long,
        var end: Long,
        var position: Long,
        var claimedBy: Int?,
    ) {
        val total: Long get() = end - start + 1
        val downloadedBytes: Long get() = (position - start).coerceIn(0L, total)
        val remaining: Long get() = (end + 1 - position).coerceAtLeast(0L)
        val isComplete: Boolean get() = position > end
    }

    private val lock = Any()
    private val segments = LinkedHashMap<Int, MutableSegment>()
    private var nextIndex = 0

    init {
        require(totalBytes > 0L) { "O tamanho do arquivo deve ser positivo." }
        if (existing.isEmpty()) {
            createInitialSegments(initialConnections)
        } else {
            loadExistingSegments(existing)
        }
    }

    private fun createInitialSegments(initialConnections: Int) {
        val bySize = (totalBytes / MIN_SEGMENT_BYTES).coerceAtLeast(1L)
        val wanted = initialConnections.coerceIn(1, MAX_INITIAL_SEGMENTS).toLong()
        val count = minOf(wanted, bySize).toInt().coerceAtLeast(1)
        val base = totalBytes / count
        val remainder = totalBytes % count
        var start = 0L
        repeat(count) { i ->
            val size = base + if (i < remainder) 1L else 0L
            val end = start + size - 1
            segments[i] = MutableSegment(
                index = i,
                start = start,
                end = end,
                position = start,
                claimedBy = null,
            )
            start = end + 1
        }
        nextIndex = count
    }

    private fun loadExistingSegments(existing: List<SegmentSnapshot>) {
        val sorted = existing.sortedBy { it.start }
        require(sorted.isNotEmpty()) { "Lista de segmentos vazia." }
        require(sorted.first().start == 0L) { "Os segmentos não começam em zero." }
        var expectedStart = 0L
        val seenIndexes = HashSet<Int>()
        for (segment in sorted) {
            require(segment.index >= 0 && seenIndexes.add(segment.index)) {
                "Índice de segmento duplicado ou inválido."
            }
            require(segment.end >= segment.start) { "Faixa de segmento inválida." }
            require(segment.start == expectedStart) { "Há lacuna ou sobreposição entre segmentos." }
            require(segment.position in segment.start..(segment.end + 1)) {
                "Posição salva fora da faixa do segmento."
            }
            segments[segment.index] = MutableSegment(
                index = segment.index,
                start = segment.start,
                end = segment.end,
                position = segment.position,
                claimedBy = null,
            )
            expectedStart = segment.end + 1
        }
        require(expectedStart == totalBytes) { "Os segmentos não cobrem o arquivo inteiro." }
        nextIndex = (sorted.maxOf { it.index }) + 1
    }

    @Synchronized
    fun claimNext(workerId: Int): SegmentWork? {
        val candidate = segments.values
            .asSequence()
            .filter { it.claimedBy == null && !it.isComplete }
            .maxWithOrNull(
                compareBy<MutableSegment> { it.remaining }
                    .thenByDescending { it.start },
            )
            ?: return null
        candidate.claimedBy = workerId
        return candidate.toWork()
    }

    /**
     * Tenta obter trabalho: primeiro um segmento livre; se não houver, divide o
     * maior segmento em andamento e assume a metade superior.
     */
    @Synchronized
    fun acquireWork(workerId: Int): SegmentWork? {
        claimNext(workerId)?.let { return it }
        return splitLargestClaimed(workerId)
    }

    @Synchronized
    fun splitLargestClaimed(workerId: Int): SegmentWork? {
        if (totalRemainingBytesLocked() <= 0L) return null
        val candidate = segments.values
            .asSequence()
            .filter {
                it.claimedBy != null &&
                    it.claimedBy != workerId &&
                    !it.isComplete &&
                    it.remaining >= minSplitBytes * 2L
            }
            .maxByOrNull { it.remaining }
            ?: return null

        val remaining = candidate.remaining
        val splitAt = candidate.position + remaining / 2L
        if (splitAt <= candidate.position || splitAt > candidate.end) return null
        val oldEnd = candidate.end
        candidate.end = splitAt - 1L
        val newIndex = nextIndex++
        val newSegment = MutableSegment(
            index = newIndex,
            start = splitAt,
            end = oldEnd,
            position = splitAt,
            claimedBy = workerId,
        )
        segments[newIndex] = newSegment
        return newSegment.toWork()
    }

    /**
     * Restringe a escrita ao fim atual do segmento, que pode ter sido reduzido
     * por um split concorrente. Retorna -1 se o worker não é mais o dono.
     */
    @Synchronized
    fun currentEnd(index: Int): Long? {
        val segment = segments[index] ?: return null
        return if (segment.isComplete) null else segment.end
    }

    @Synchronized
    fun updatePosition(index: Int, workerId: Int, newPosition: Long): Boolean {
        val segment = segments[index] ?: return false
        if (segment.claimedBy != workerId) return false
        if (segment.isComplete) return false
        val clamped = newPosition.coerceIn(segment.start, segment.end + 1)
        if (clamped > segment.position) segment.position = clamped
        return true
    }

    @Synchronized
    fun complete(workerId: Int, index: Int): Boolean {
        val segment = segments[index] ?: return false
        if (segment.claimedBy != workerId) return false
        segment.position = segment.end + 1
        segment.claimedBy = null
        return true
    }

    @Synchronized
    fun release(workerId: Int, index: Int): Boolean {
        val segment = segments[index] ?: return false
        if (segment.claimedBy != workerId) return false
        segment.claimedBy = null
        return true
    }

    @Synchronized
    fun isComplete(): Boolean = segments.values.all { it.isComplete }

    @Synchronized
    fun hasUnclaimedWork(): Boolean =
        segments.values.any { it.claimedBy == null && !it.isComplete }

    @Synchronized
    fun activeConnections(): Int =
        segments.values.mapNotNull { it.claimedBy }.distinct().size

    @Synchronized
    fun downloadedBytes(): Long =
        segments.values.sumOf { it.downloadedBytes }

    @Synchronized
    fun totalRemainingBytes(): Long = totalRemainingBytesLocked()

    private fun totalRemainingBytesLocked(): Long =
        segments.values.sumOf { it.remaining }

    @Synchronized
    fun segmentCount(): Int = segments.size

    @Synchronized
    fun snapshot(): List<SegmentSnapshot> =
        segments.values
            .sortedBy { it.start }
            .map {
                SegmentSnapshot(
                    index = it.index,
                    start = it.start,
                    end = it.end,
                    position = it.position,
                    claimedBy = it.claimedBy,
                )
            }

    @Synchronized
    fun snapshotForIndex(index: Int): SegmentSnapshot? =
        segments[index]?.let {
            SegmentSnapshot(
                index = it.index,
                start = it.start,
                end = it.end,
                position = it.position,
                claimedBy = it.claimedBy,
            )
        }

    private fun MutableSegment.toWork(): SegmentWork =
        SegmentWork(index = index, start = start, end = end, position = position)

    companion object {
        const val MIN_SEGMENT_BYTES: Long = 512L * 1024L
        const val DEFAULT_MIN_SPLIT_BYTES: Long = 512L * 1024L
        const val MAX_INITIAL_SEGMENTS = 64
    }
}
