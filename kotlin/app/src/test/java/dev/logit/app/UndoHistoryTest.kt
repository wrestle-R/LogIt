package dev.logit.app

import org.junit.Assert.*
import org.junit.Test

class UndoHistoryTest {
    @Test fun multipleUndoKeepsOriginalActivityAndDay() {
        val first = LogOp("one", "u", "guitar", "2026-11-16", 1)
        val second = LogOp("two", "u", "run", "2026-11-17", 1)
        var history = UndoHistory.record(emptyList(), first, false)
        history = UndoHistory.record(history, second, false)
        val undoSecond = UndoHistory.reverse(history, "two")
        assertEquals("run", undoSecond.habit)
        assertEquals(-1, undoSecond.delta)
        history = UndoHistory.record(history, null, true)
        val undoFirst = UndoHistory.reverse(history, "one")
        assertEquals("guitar", undoFirst.habit)
        assertEquals("2026-11-16", undoFirst.day)
        history = UndoHistory.record(history, null, true)
        assertTrue(history.isEmpty())
    }
    @Test(expected = IllegalStateException::class)
    fun staleTapCannotReverseTheNextLog() {
        val history = listOf(LogOp("one", "u", "guitar", "2026-11-16", 1))
        UndoHistory.reverse(history, "already-undone")
    }

    @Test fun redoKeepsOriginalActivityDateAndStableOperationId() {
        val first = LogOp("one", "u", "guitar", "2026-11-16", 1)
        val second = LogOp("two", "u", "run", "2026-11-17", 1, automatic = true)
        val redo = UndoHistory.reapply(listOf(second, first), "one")
        assertEquals("guitar", redo.habit)
        assertEquals("2026-11-16", redo.day)
        assertEquals(1, redo.delta)
        assertNotEquals(first.id, redo.id)
        assertEquals(redo, UndoHistory.reapply(listOf(second, first), "one"))
        assertTrue(UndoHistory.reapply(listOf(second), "two").automatic)
    }
}
