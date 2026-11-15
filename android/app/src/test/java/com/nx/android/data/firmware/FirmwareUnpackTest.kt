package com.nx.android.data.firmware

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固件包的解包：目录被摊平（移植器只扫一层），路径穿越的条目无处可去 */
class FirmwareUnpackTest {

    private fun zip(entries: Map<String, String>, dir: File): File {
        val f = File(dir, "Firmware.22.5.0.zip")
        ZipOutputStream(f.outputStream()).use { out ->
            for ((name, body) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `the files land flat in the target`() {
        val tmp = File.createTempFile("nx2a_fw", "")
        tmp.delete(); tmp.mkdirs()
        try {
            val target = File(tmp, "firmware")
            target.mkdirs()
            val z = zip(
                mapOf(
                    "e172fd618e026960258390a53678c853.nca" to "one",
                    "6619add009b46f535de5ac7a54e52feb.cnmt.nca" to "two",
                ), tmp,
            )
            val n = unpackZip(z, target)
            assertEquals(2, n)
            assertTrue(File(target, "e172fd618e026960258390a53678c853.nca").isFile)
            assertTrue(File(target, "6619add009b46f535de5ac7a54e52feb.cnmt.nca").isFile)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a folder inside the zip is flattened`() {
        val tmp = File.createTempFile("nx2a_fw", "")
        tmp.delete(); tmp.mkdirs()
        try {
            val target = File(tmp, "firmware")
            target.mkdirs()
            val z = zip(mapOf("registered/abc.nca" to "one"), tmp)
            unpackZip(z, target)
            assertTrue("the nca sits directly in the target", File(target, "abc.nca").isFile)
            assertFalse("no folder was made", File(target, "registered").exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `an entry cannot escape the target`() {
        val tmp = File.createTempFile("nx2a_fw", "")
        tmp.delete(); tmp.mkdirs()
        try {
            val target = File(tmp, "firmware")
            target.mkdirs()
            val z = zip(
                mapOf(
                    "../escaped.nca" to "bad",
                    "/absolute.nca" to "bad",
                    "good.nca" to "good",
                ), tmp,
            )
            val n = unpackZip(z, target)
            assertEquals("only the safe entry is written", 1, n)
            assertTrue(File(target, "good.nca").isFile)
            assertFalse("nothing outside", File(tmp, "escaped.nca").exists())
            assertFalse("nothing outside", File(tmp.parentFile, "absolute.nca").exists())
        } finally {
            tmp.deleteRecursively()
        }
    }
}
