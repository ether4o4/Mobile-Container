package com.ether4o4.mobilecontainer.terminal

/**
 * JNI bridge to the native pty terminal (pty.cpp -> libmcpty.so).
 * Spawns an interactive shell on a pseudo-terminal and exposes
 * read/write/resize/wait/close for the terminal emulator.
 */
object TerminalBridge {
    init {
        System.loadLibrary("mcpty")
    }

    data class Handle(val ptr: Long)

    fun spawn(cmd: String, cwd: String, env: Array<String>, rows: Int, cols: Int): Handle {
        val ptr = nativeSpawn(cmd, cwd, env, rows, cols)
        return Handle(ptr)
    }

    fun read(h: Handle, buf: ByteArray, off: Int, len: Int): Int =
        nativeRead(h.ptr, buf, off, len)

    fun write(h: Handle, buf: ByteArray, off: Int, len: Int): Int =
        nativeWrite(h.ptr, buf, off, len)

    fun resize(h: Handle, rows: Int, cols: Int) = nativeResize(h.ptr, rows, cols)
    fun wait(h: Handle): Int = nativeWait(h.ptr)
    fun close(h: Handle) = nativeClose(h.ptr)
    fun sendSignal(h: Handle, sig: Int) = nativeSendSignal(h.ptr, sig)

    @JvmStatic external fun nativeSpawn(cmd: String, cwd: String, env: Array<String>, rows: Int, cols: Int): Long
    @JvmStatic external fun nativeRead(handle: Long, buf: ByteArray, off: Int, len: Int): Int
    @JvmStatic external fun nativeWrite(handle: Long, buf: ByteArray, off: Int, len: Int): Int
    @JvmStatic external fun nativeResize(handle: Long, rows: Int, cols: Int)
    @JvmStatic external fun nativeWait(handle: Long): Int
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeSendSignal(handle: Long, sig: Int)
}
