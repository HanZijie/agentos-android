package org.agentos.runtime.pi

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * [BytecodeCache] in a directory. On Android use `File(context.codeCacheDir, "pi")`: the system
 * clears `codeCacheDir` when the app is upgraded, and the key changes with the bundle anyway.
 *
 * - One file per key, named after the key's hash. Keys already carry the bundle hash, engine and
 *   protocol version, so a new key makes older files garbage: [put] deletes them.
 * - Writes are atomic (temporary file, fsync, rename): a crash never leaves a truncated entry.
 * - Every file starts with the SHA-256 of its payload and [get] verifies it. QuickJS does not
 *   harden its bytecode reader, so a damaged file could crash the process natively instead of
 *   failing the evaluation, and then crash it again on every start. A mismatch deletes the file.
 * - I/O errors are not thrown: a failed [get] is a miss, a failed [put] leaves no entry.
 */
class FileBytecodeCache(private val dir: File) : BytecodeCache {

    override fun get(key: String): ByteArray? {
        val file = fileFor(key)
        val data = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return null
        if (data.size < DIGEST_BYTES) {
            file.delete()
            return null
        }
        val payload = data.copyOfRange(DIGEST_BYTES, data.size)
        if (!MessageDigest.isEqual(sha256(payload), data.copyOfRange(0, DIGEST_BYTES))) {
            file.delete()
            return null
        }
        return payload
    }

    override fun put(key: String, bytes: ByteArray) {
        val target = fileFor(key)
        val tmp = File(dir, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            dir.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(sha256(bytes))
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) return
            dir.listFiles()?.forEach { f -> if (f != target && f.name.endsWith(SUFFIX)) f.delete() }
        } catch (_: Exception) {
            // A cache: losing an entry only costs one cold start from source.
        } finally {
            tmp.delete()
        }
    }

    override fun remove(key: String) {
        fileFor(key).delete()
    }

    private fun fileFor(key: String): File =
        File(dir, sha256(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32) + SUFFIX)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val SUFFIX = ".qjsbc"
        const val DIGEST_BYTES = 32
    }
}
