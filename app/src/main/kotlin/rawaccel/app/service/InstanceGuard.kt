package rawaccel.app.service

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/** Keeps multiple Ace processes from competing over the driver state. */
class InstanceGuard private constructor(
    private val channel: FileChannel,
    private val lock: FileLock
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(lockFile: File): InstanceGuard? = runCatching {
            lockFile.parentFile.mkdirs()
            val channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
            val lock = channel.tryLock() ?: run {
                channel.close()
                return null
            }
            InstanceGuard(channel, lock)
        }.getOrNull()
    }
}