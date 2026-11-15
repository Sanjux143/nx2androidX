package com.nx.android.domain

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The environment's checks: the chip's verdict (the two cheap facts) and the doctor's rows (the expensive ones).
 */
class EnvCheckTest {

    private fun rows(json: String): List<EnvCheck.Row> = EnvCheck.rows(JSONArray(json))

    @Test
    fun `the chip is ready when the environment and the keys are there`() {
        assertEquals(EnvCheck.Chip.READY, EnvCheck.chip(envReady = true, keysOk = true))
    }

    @Test
    fun `the chip is not ready when the environment is not there`() {
        assertEquals(EnvCheck.Chip.NOT_READY, EnvCheck.chip(envReady = false, keysOk = true))
    }

    @Test
    fun `the chip is not ready when the keys are not there`() {
        assertEquals(EnvCheck.Chip.NOT_READY, EnvCheck.chip(envReady = true, keysOk = false))
    }

    @Test
    fun `the chip is a hint when the child-process limit is on`() {
        assertEquals(
            EnvCheck.Chip.HINT,
            EnvCheck.chip(envReady = true, keysOk = true, phantom = PhantomCheck.Status.ON),
        )
    }

    @Test
    fun `the chip is a hint when the child-process limit cannot be read`() {
        assertEquals(
            EnvCheck.Chip.HINT,
            EnvCheck.chip(envReady = true, keysOk = true, phantom = PhantomCheck.Status.UNKNOWN),
        )
    }

    @Test
    fun `the chip is ready when the child-process limit is off`() {
        assertEquals(
            EnvCheck.Chip.READY,
            EnvCheck.chip(envReady = true, keysOk = true, phantom = PhantomCheck.Status.OFF),
        )
        assertEquals(
            EnvCheck.Chip.READY,
            EnvCheck.chip(envReady = true, keysOk = true, phantom = PhantomCheck.Status.NOT_NEEDED),
        )
    }

    @Test
    fun `the chip does not flash while the child-process limit is being read`() {
        // null: the answer is not here yet, so the chip says what it knows
        assertEquals(EnvCheck.Chip.READY, EnvCheck.chip(envReady = true, keysOk = true, phantom = null))
    }

    @Test
    fun `the chip is not ready when something is missing, whatever the limit says`() {
        assertEquals(
            EnvCheck.Chip.NOT_READY,
            EnvCheck.chip(envReady = true, keysOk = false, phantom = PhantomCheck.Status.ON),
        )
        assertEquals(
            EnvCheck.Chip.NOT_READY,
            EnvCheck.chip(envReady = false, keysOk = true, phantom = PhantomCheck.Status.ON),
        )
    }

    @Test
    fun `the chip is a hint when the machine's firmware is not imported`() {
        // 固件是可选的：缺了它 App 照样能用，所以是黄的，不是红的
        assertEquals(
            EnvCheck.Chip.HINT,
            EnvCheck.chip(envReady = true, keysOk = true, firmwareImported = false),
        )
    }

    @Test
    fun `the chip is ready again once the firmware is imported`() {
        assertEquals(
            EnvCheck.Chip.READY,
            EnvCheck.chip(envReady = true, keysOk = true, firmwareImported = true),
        )
    }

    @Test
    fun `a missing firmware does not hide a missing environment`() {
        assertEquals(
            EnvCheck.Chip.NOT_READY,
            EnvCheck.chip(envReady = false, keysOk = true, firmwareImported = false),
        )
        assertEquals(
            EnvCheck.Chip.NOT_READY,
            EnvCheck.chip(envReady = true, keysOk = false, firmwareImported = false),
        )
    }

    @Test
    fun `a row that passed is ok`() {
        val r = rows("""[{"name": "Python", "ok": true, "detail": "3.13.5", "required": true}]""").first()
        assertEquals(EnvCheck.Level.OK, r.level)
        assertEquals("Python", r.name)
        assertEquals("3.13.5", r.detail)
    }

    @Test
    fun `a row that passed says no fix`() {
        // the tool keeps the fix in the row even when the check passed ("pip install pillow"): the CLI prints
        // it only for a row that failed, and so does the checklist
        val r = rows("""[{"name": "Pillow", "ok": true, "detail": "the app icon", "required": false, "fix": "pip install pillow"}]""").first()
        assertEquals(EnvCheck.Level.OK, r.level)
        assertEquals("", r.fix)
    }

    @Test
    fun `a required row that failed is bad`() {
        val r = rows("""[{"name": "Android NDK", "ok": false, "detail": "not found", "required": true, "fix": "install it"}]""").first()
        assertEquals(EnvCheck.Level.BAD, r.level)
        assertEquals("install it", r.fix)
    }

    @Test
    fun `a row that is not required and failed is a hint`() {
        val r = rows("""[{"name": "Pillow", "ok": false, "detail": "missing", "required": false}]""").first()
        assertEquals(EnvCheck.Level.HINT, r.level)
    }

    @Test
    fun `a row without the required flag is a required one`() {
        val r = rows("""[{"name": "Python", "ok": false, "detail": "3.10"}]""").first()
        assertEquals(EnvCheck.Level.BAD, r.level)
    }

    @Test
    fun `the keys row is left out - the checklist has its own`() {
        val r = rows("""[{"name": "prod.keys", "ok": true, "detail": "/sdcard/switch/prod.keys"}, {"name": "Python", "ok": true, "detail": "3.13.5"}]""")
        assertEquals(1, r.size)
        assertEquals("Python", r[0].name)
    }

    @Test
    fun `the adb row is left out - the phone cannot run it`() {
        val r = rows("""[{"name": "adb", "ok": false, "detail": "not found"}, {"name": "Python", "ok": true, "detail": "3.13.5"}]""")
        assertEquals(1, r.size)
        assertEquals("Python", r[0].name)
    }

    @Test
    fun `the rows keep the order the doctor printed them in`() {
        val r = rows("""[{"name": "Python", "ok": true}, {"name": "Pillow", "ok": true}, {"name": ".NET", "ok": true}]""")
        assertEquals(listOf("Python", "Pillow", ".NET"), r.map { it.name })
    }

    @Test
    fun `a row without a name is not a row`() {
        assertTrue(rows("""[{"ok": true, "detail": "x"}, {"name": "Python", "ok": true}]""").size == 1)
    }
}
