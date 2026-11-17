package dev.logit.app

/** Device-local completion history survives sync and app restarts. */
object UndoHistory {
    fun record(history: List<LogOp>, added: LogOp?, removeLatest: Boolean): List<LogOp> {
        val remaining = if (removeLatest) history.dropLast(1) else history
        return if (added == null) remaining else remaining + added
    }

    fun reverse(history: List<LogOp>, expectedId: String): LogOp {
        val last = history.lastOrNull()
        check(last != null && last.id == expectedId) { "That log has already been undone" }
        return last.copy(id = java.util.UUID.randomUUID().toString(), delta = -1)
    }
}
