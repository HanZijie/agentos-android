package org.agentos.runtime.pi

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBytecodeCacheTest {

    private val dir: File = Files.createTempDirectory("pi-bytecode").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun files() = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun roundTripsAndSurvivesANewInstance() {
        val bytes = ByteArray(100_000) { (it * 31).toByte() }
        FileBytecodeCache(dir).put("pi-agent:abc:quickjs-kt-android 1.0.15 aarch64:p1", bytes)
        assertContentEquals(bytes, FileBytecodeCache(dir).get("pi-agent:abc:quickjs-kt-android 1.0.15 aarch64:p1"))
        assertNull(FileBytecodeCache(dir).get("pi-agent:other:p1"))
    }

    @Test
    fun aNewKeyReplacesOlderEntries() {
        val cache = FileBytecodeCache(dir)
        cache.put("k1", byteArrayOf(1))
        cache.put("k2", byteArrayOf(2))
        assertNull(cache.get("k1"))
        assertContentEquals(byteArrayOf(2), cache.get("k2"))
        assertEquals(1, files().size, "${files()}")
        assertTrue(files().none { it.endsWith(".tmp") })
    }

    @Test
    fun damagedEntriesAreMissesAndDeleted() {
        val cache = FileBytecodeCache(dir)
        cache.put("k", ByteArray(1_000) { it.toByte() })
        val file = dir.listFiles()!!.single()

        val data = file.readBytes()
        data[500] = (data[500] + 1).toByte()
        file.writeBytes(data)
        assertNull(cache.get("k"), "a flipped byte fails the checksum")
        assertFalse(file.exists())

        cache.put("k", ByteArray(1_000))
        file.writeBytes(ByteArray(10))
        assertNull(cache.get("k"), "shorter than the checksum")
        assertFalse(file.exists())
    }

    @Test
    fun removeAndUnwritableDirectoryDoNotThrow() {
        val cache = FileBytecodeCache(dir)
        cache.put("k", byteArrayOf(1, 2, 3))
        cache.remove("k")
        assertNull(cache.get("k"))
        cache.remove("never-stored")

        val notADir = File(dir, "file").apply { writeText("x") }
        val broken = FileBytecodeCache(File(notADir, "sub"))
        broken.put("k", byteArrayOf(1))
        assertNull(broken.get("k"))
    }
}
