package com.ether4o4.mobilecontainer.util

/**
 * In-memory ring buffer of recent log lines, used by pull/run flows to
 * record events that the Logs screen surfaces. Singleton.
 */
object LogStore {
    private const val MAX = 2000
    private val buf = ArrayDeque<String>()
    private val lock = Any()

    fun add(line: String) {
        synchronized(lock) {
            buf.addLast(line)
            while (buf.size > MAX) buf.removeFirst()
        }
    }

    fun lines(): List<String> = synchronized(lock) { buf.toList() }

    fun clear() = synchronized(lock) { buf.clear() }

    fun size(): Int = synchronized(lock) { buf.size }
}
