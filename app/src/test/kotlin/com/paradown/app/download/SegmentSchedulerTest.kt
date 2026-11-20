package com.paradown.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SegmentSchedulerTest {

    private val kb = 1024L
    private val mb = 1024L * kb

    private fun assertCoversExactRange(segments: List<SegmentSnapshot>, totalBytes: Long) {
        assertTrue("Nenhum segmento criado", segments.isNotEmpty())
        val sorted = segments.sortedBy { it.start }
        assertEquals("Primeiro segmento deve começar em zero", 0L, sorted.first().start)
        assertEquals(
            "Último segmento deve terminar no fim do arquivo",
            totalBytes - 1L,
            sorted.last().end,
        )
        sorted.zipWithNext().forEach { (current, next) ->
            assertEquals(
                "Segmentos devem ser contíguos, sem lacunas nem sobreposição",
                current.end + 1L,
                next.start,
            )
        }
        val sum = sorted.sumOf { it.totalBytes }
        assertEquals("Soma dos segmentos deve ser o tamanho total", totalBytes, sum)
    }

    @Test
    fun `cria segmentos cobrindo exatamente o arquivo`() {
        val total = 10L * mb
        val scheduler = SegmentScheduler(totalBytes = total, initialConnections = 4)

        assertEquals(4, scheduler.segmentCount())
        assertCoversExactRange(scheduler.snapshot(), total)
        assertEquals(0L, scheduler.downloadedBytes())
        assertEquals(total, scheduler.totalRemainingBytes())
        assertFalse(scheduler.isComplete())
    }

    @Test
    fun `arquivo pequeno gera um unico segmento`() {
        val total = 600L * kb
        val scheduler = SegmentScheduler(totalBytes = total, initialConnections = 8)

        assertEquals(1, scheduler.segmentCount())
        assertCoversExactRange(scheduler.snapshot(), total)
    }

    @Test
    fun `quantidade inicial respeita limite minimo por segmento`() {
        val total = 3L * 512L * kb
        val scheduler = SegmentScheduler(totalBytes = total, initialConnections = 64)

        assertEquals(3, scheduler.segmentCount())
        assertCoversExactRange(scheduler.snapshot(), total)
    }

    @Test
    fun `conexoes invalidas sao normalizadas`() {
        val total = 8L * mb
        val zero = SegmentScheduler(totalBytes = total, initialConnections = 0)
        assertEquals(1, zero.segmentCount())

        val many = SegmentScheduler(totalBytes = 128L * mb, initialConnections = 500)
        assertEquals(SegmentScheduler.MAX_INITIAL_SEGMENTS, many.segmentCount())
        assertCoversExactRange(many.snapshot(), 128L * mb)
    }

    @Test
    fun `workers recebem segmentos distintos`() {
        val total = 10L * mb
        val scheduler = SegmentScheduler(totalBytes = total, initialConnections = 4)

        val first = scheduler.claimNext(1)!!
        val second = scheduler.claimNext(2)!!
        val third = scheduler.claimNext(3)!!
        val fourth = scheduler.claimNext(4)!!

        assertEquals(4, setOf(first.index, second.index, third.index, fourth.index).size)
        assertEquals(4, scheduler.activeConnections())
        assertNull("Todos os segmentos já foram distribuídos", scheduler.claimNext(5))
    }

    @Test
    fun `work stealing divide o maior segmento em andamento`() {
        val total = 10L * mb
        val scheduler = SegmentScheduler(
            totalBytes = total,
            initialConnections = 1,
            minSplitBytes = 256L * kb,
        )

        val work = scheduler.claimNext(1)!!
        assertEquals(0L, work.start)
        assertEquals(total - 1L, work.end)
        assertTrue(scheduler.updatePosition(work.index, 1, 2L * mb))

        val stolen = scheduler.acquireWork(2)
        assertNotNull("O segundo worker deve conseguir roubar metade do segmento", stolen)
        stolen!!

        assertEquals(2, scheduler.segmentCount())
        assertCoversExactRange(scheduler.snapshot(), total)
        assertEquals(6L * mb, stolen.start)
        assertEquals(total - 1L, stolen.end)
        assertEquals(6L * mb, stolen.position)

        assertEquals("O segmento original deve terminar antes da metade roubada", 6L * mb - 1L, scheduler.currentEnd(work.index))

        assertTrue(scheduler.updatePosition(stolen.index, 2, stolen.position + 1L * mb))
        assertEquals(3L * mb, scheduler.downloadedBytes())
    }

    @Test
    fun `nao divide segmento pequeno demais`() {
        val total = 4L * mb
        val scheduler = SegmentScheduler(
            totalBytes = total,
            initialConnections = 1,
            minSplitBytes = 1L * mb,
        )

        val work = scheduler.claimNext(1)!!
        assertTrue(scheduler.updatePosition(work.index, 1, 2L * mb + 512L * kb))

        assertNull(
            "Restante menor que o dobro do mínimo não deve ser dividido",
            scheduler.acquireWork(2),
        )
    }

    @Test
    fun `updatePosition rejeita worker que nao e dono e limita ao fim do segmento`() {
        val total = 4L * mb
        val scheduler = SegmentScheduler(totalBytes = total, initialConnections = 1)

        val work = scheduler.claimNext(1)!!
        assertFalse(scheduler.updatePosition(work.index, 2, 1L * mb))
        assertEquals(0L, scheduler.downloadedBytes())

        assertTrue(scheduler.updatePosition(work.index, 1, total + 500L))
        assertEquals(total, scheduler.downloadedBytes())
        assertTrue(scheduler.snapshot().single().isComplete)
        assertNull("Segmento completo não deve ter fim corrente", scheduler.currentEnd(work.index))
    }

    @Test
    fun `complete e release apenas pelo dono`() {
        val total = 6L * mb
        val scheduler = SegmentScheduler(totalBytes = total, initialConnections = 2)

        val work = scheduler.claimNext(1)!!
        assertFalse(scheduler.complete(2, work.index))
        assertFalse(scheduler.release(2, work.index))
        assertTrue(scheduler.release(1, work.index))
        assertEquals(0, scheduler.activeConnections())

        val reclaimed = scheduler.claimNext(2)!!
        assertEquals(work.index, reclaimed.index)
        assertTrue(scheduler.complete(2, work.index))
        assertTrue(scheduler.snapshotForIndex(work.index)!!.isComplete)
        assertEquals(work.end - work.start + 1L, scheduler.downloadedBytes())

        val other = scheduler.claimNext(3)!!
        assertTrue(scheduler.complete(3, other.index))
        assertEquals(total, scheduler.downloadedBytes())
    }

    @Test
    fun `isComplete somente quando todos os segmentos terminam`() {
        val total = 4L * mb
        val scheduler = SegmentScheduler(totalBytes = total, initialConnections = 2)

        val a = scheduler.claimNext(1)!!
        val b = scheduler.claimNext(2)!!
        assertTrue(scheduler.complete(1, a.index))
        assertFalse(scheduler.isComplete())
        assertFalse(scheduler.hasUnclaimedWork())

        assertTrue(scheduler.complete(2, b.index))
        assertTrue(scheduler.isComplete())
        assertNull(scheduler.acquireWork(3))
    }

    @Test
    fun `retoma posicoes salvas sem perder progresso`() {
        val total = 10L * mb
        val original = SegmentScheduler(totalBytes = total, initialConnections = 3)
        val a = original.claimNext(1)!!
        val b = original.claimNext(2)!!
        original.claimNext(3)!!
        original.updatePosition(a.index, 1, 1L * mb)
        original.updatePosition(b.index, 2, b.position + 512L * kb)

        val saved = original.snapshot()
        val restored = SegmentScheduler(
            totalBytes = total,
            initialConnections = 4,
            existing = saved,
        )

        assertEquals(original.downloadedBytes(), restored.downloadedBytes())
        assertEquals(saved.map { it.copy(claimedBy = null) }, restored.snapshot())
        assertCoversExactRange(restored.snapshot(), total)
        assertEquals(1L * mb, restored.snapshotForIndex(a.index)!!.position)
        assertEquals(b.position + 512L * kb, restored.snapshotForIndex(b.index)!!.position)
    }

    @Test
    fun `retomada rejeita lacunas sobreposicoes e cobertura incompleta`() {
        val total = 10L * mb

        assertInvalid(
            listOf(
                SegmentSnapshot(0, 0L, 4L * mb - 1L, 1L * mb),
                SegmentSnapshot(1, 4L * mb + 1L, total - 1L, 4L * mb + 1L),
            ),
            total,
        )

        assertInvalid(
            listOf(
                SegmentSnapshot(0, 0L, 5L * mb, 0L),
                SegmentSnapshot(1, 5L * mb, total - 1L, 5L * mb),
            ),
            total,
        )

        assertInvalid(
            listOf(SegmentSnapshot(0, 0L, total - 2L, 0L)),
            total,
        )

        assertInvalid(
            listOf(SegmentSnapshot(0, 0L, total - 1L, total + 10L)),
            total,
        )

        assertInvalid(
            listOf(
                SegmentSnapshot(1, 0L, total - 1L, 0L),
                SegmentSnapshot(1, 0L, total - 1L, 0L),
            ),
            total,
        )
    }

    private fun assertInvalid(segments: List<SegmentSnapshot>, total: Long) {
        try {
            SegmentScheduler(
                totalBytes = total,
                initialConnections = 2,
                existing = segments,
            )
            fail("Segmentos inválidos deveriam ser rejeitados")
        } catch (_: IllegalArgumentException) {
            // Esperado.
        }
    }
}
