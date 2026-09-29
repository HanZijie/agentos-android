package org.agentos.test.acp.inapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 测试 key 的投递点（C4）。主机端 run.py 用
 * `adb shell content write --uri content://org.agentos.test.acp.inapp.keydrop/<name>` 把 key 从 stdin 写进
 * App 私有目录 `files/test/<name>`，场景读完立即删除。
 *
 * - key 不经过任何命令行（API 37 的 adbd 会把 adb shell 的命令行写进 logcat）；
 * - releaseTest 包不可调试、没有 run-as，也能用；
 * - Manifest 要求 android.permission.DUMP：只有 shell 和系统能写。只能写，不能读、不能列目录。
 */
class KeyDropProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no name")
        if (name !in NAMES) throw FileNotFoundException("unknown key slot")
        if (!mode.startsWith("w")) throw SecurityException("write only")
        val ctx = context ?: throw FileNotFoundException("no context")
        val f = File(ctx.filesDir, "test/$name")
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(
            f,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "org.agentos.test.acp.inapp.keydrop"

        /** byok_key：BYOK 用例的随机测试 key；live_key：真实对话用的 key（只在主机端设置了环境变量时投递）。 */
        val NAMES = setOf("byok_key", "live_key")
    }
}
