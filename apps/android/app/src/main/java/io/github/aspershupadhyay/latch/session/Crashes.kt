package io.github.aspershupadhyay.latch.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers the last failure that would have closed Latch, so the owner (and
 * whoever fixes it) can see what happened after a restart. Only the kind of
 * failure and Latch's own code locations are kept: never a message, which
 * could hold a file name, a path, or screen text.
 */
object Crashes {
    private const val PACKAGE = "io.github.aspershupadhyay.latch"
    @Volatile private var prefs: SharedPreferences? = null

    /** Records failures that reach the top of a thread, then lets Android handle them as before. */
    fun install(context: Context) {
        prefs = context.getSharedPreferences("latch_crashes", Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(error, fatal = true) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A content-free summary: "IllegalStateException at PhoneFiles.write:412". */
    fun describe(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith(PACKAGE) }
        val where = frame?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        return error.javaClass.simpleName.ifEmpty { "Error" } + where
    }

    fun record(error: Throwable, fatal: Boolean = false) {
        val p = prefs ?: return
        // commit(): a fatal failure ends the process right after this.
        p.edit(commit = fatal) {
            putString("last", describe(error))
            putLong("at", System.currentTimeMillis())
            putBoolean("fatal", fatal)
        }
    }

    /** The last fatal failure not yet shown to the owner, once. */
    fun takeLastFatal(): Pair<String, Long>? {
        val p = prefs ?: return null
        if (!p.getBoolean("fatal", false)) return null
        val what = p.getString("last", null) ?: return null
        val at = p.getLong("at", 0)
        p.edit { putBoolean("fatal", false) }
        return what to at
    }
}
