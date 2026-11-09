package io.github.otto

/**
 * An echo canceller (Speex's, in src/main/cpp): given what the microphone heard and what the phone was playing at
 * that moment (the reference, a little ahead of its echo), it gives back the microphone with the phone's own sound
 * taken out. Frames of [frame] samples of 16-bit mono PCM at [rate]; [tail] samples is how late an echo can be.
 * One thread at a time; [close] when done.
 */
class Echo(val frame: Int, tail: Int, rate: Int) : AutoCloseable {
    private var handle = create(frame, tail, rate)

    fun process(mic: ShortArray, ref: ShortArray, out: ShortArray) {
        check(handle != 0L) { "closed" }
        process(handle, mic, ref, out)
    }

    override fun close() {
        if (handle != 0L) destroy(handle)
        handle = 0L
    }

    private companion object {
        init { System.loadLibrary("otto_echo") }
        @JvmStatic external fun create(frame: Int, tail: Int, rate: Int): Long
        @JvmStatic external fun process(handle: Long, mic: ShortArray, ref: ShortArray, out: ShortArray)
        @JvmStatic external fun destroy(handle: Long)
    }
}
