package com.nx.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game profiles package as a release carries it: where it is and what it is called.
 */
class ProfilesPackageTest {

    @Test
    fun `the tag of a release carries the v the version name does not`() {
        assertEquals("v0.3.0-alpha15", ProfilesPackage.tag("0.3.0-alpha15"))
        assertEquals("v0.3.0-alpha15", ProfilesPackage.tag("v0.3.0-alpha15"))
    }

    @Test
    fun `the url of a package is the release's asset`() {
        assertEquals(
            "https://github.com/zFitness/nx2androidX/releases/download/v0.3.0-alpha15/nx2a-profiles-r1-bf1e4edd.zip",
            ProfilesPackage.assetUrl("v0.3.0-alpha15", "nx2a-profiles-r1-bf1e4edd.zip"),
        )
    }

    @Test
    fun `the assets page is a plain web page`() {
        assertEquals(
            "https://github.com/zFitness/nx2androidX/releases/expanded_assets/v0.3.0-alpha15",
            ProfilesPackage.assetsPage("v0.3.0-alpha15"),
        )
    }

    @Test
    fun `the packages a release carries are found in its assets page`() {
        val html = """
            <div><a href="/zFitness/nx2androidX/releases/download/v0.3.0-alpha15/nx2a-profiles-r1-bf1e4edd.zip">nx2a-profiles-r1-bf1e4edd.zip</a></div>
            <div><a href="/zFitness/nx2androidX/releases/download/v0.3.0-alpha15/app-release.apk">app-release.apk</a></div>
        """.trimIndent()
        assertEquals(listOf("nx2a-profiles-r1-bf1e4edd.zip"), ProfilesPackage.packageNamesIn(html))
    }

    @Test
    fun `a release without a package says so`() {
        val html = """<div><a href="/zFitness/nx2androidX/releases/download/v0.3.0-alpha15/app-release.apk">app-release.apk</a></div>"""
        assertTrue(ProfilesPackage.packageNamesIn(html).isEmpty())
    }
}
