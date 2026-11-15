package com.nx.android.data

import com.nx.android.domain.environment.GuestProcessLauncher
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RuntimeCompilerTest {
    private fun compile(output: String, code: Int): RuntimeUpdateResult {
        val launcher = object : GuestProcessLauncher {
            override fun guestEnv() = mutableMapOf<String, String>()
            override fun guest(args: List<String>, extra: Map<String, String>): Process {
                assertEquals(listOf("-m", "porter", "rebuild-runtime", "/packs/Game-data", "--progress", "json", "--no-lsfg"), args)
                assertTrue(extra.isEmpty())
                return object : Process() {
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = ByteArrayInputStream(output.toByteArray())
                    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                    override fun waitFor() = code
                    override fun exitValue() = code
                    override fun destroy() = Unit
                }
            }
        }
        return RuntimeCompiler(launcher).rebuild("/packs/Game-data", true) {}
    }

    @Test fun `runtime update needs both successful exit and the matching commit result`() {
        assertTrue(compile("""{"ev":"result","data":"/packs/Game-data"}""", 0).success)
        assertFalse(compile("""{"ev":"result","data":"/packs/Game-data"}""", 1).success)
        assertFalse(compile("compiler exited without a result", 0).success)
        assertFalse(compile("""{"ev":"result","data":"/packs/Other-data"}""", 0).success)
    }

    @Test fun `the porter's failure line is the reason the player sees`() {
        val failed = compile("""{"ev":"say","msg":"[RED] The game profile changed since this pack was built."}""", 1)
        assertFalse(failed.success)
        assertEquals("The game profile changed since this pack was built.", failed.reason)
        val worked = compile("""{"ev":"result","data":"/packs/Game-data"}""", 0)
        assertTrue(worked.success)
        assertEquals("", worked.reason)
    }

    @Test fun `a changed profile is a failure the player can continue from`() {
        val failed = compile("""{"ev":"profile-drift","msg":"The game profile changed since this pack was built."}
{"ev":"say","msg":"[RED] The game profile changed since this pack was built."}""", 1)
        assertFalse(failed.success)
        assertTrue(failed.profileDrift)
        val other = compile("""{"ev":"say","msg":"[RED] The Android compiler is unavailable."}""", 1)
        assertFalse(other.profileDrift)
    }

    @Test fun `the drift flag reaches the porter only when the player asked for it`() {
        assertFalse(RuntimeCompiler.args("/packs/Game-data", true).contains("--allow-profile-drift"))
        assertTrue(RuntimeCompiler.args("/packs/Game-data", true, true).contains("--allow-profile-drift"))
    }

    @Test fun `runtime command follows current frame generation policy without source keys`() {
        assertFalse(RuntimeCompiler.args("/packs/Game-data", false).contains("--no-lsfg"))
        assertFalse(RuntimeCompiler.args("/packs/Game-data", false).contains("-k"))
    }
}
