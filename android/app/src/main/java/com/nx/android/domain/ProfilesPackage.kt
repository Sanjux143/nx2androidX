package com.nx.android.domain

/**
 * The game profiles package as a release carries it.
 *
 * The package is the release's second artifact: the release that carries this build's version has it as an
 * asset, under the name the app's own manifest gives. The app can therefore fetch it without asking anybody
 * where it is - and it can check what came back against the digest in its own manifest.
 *
 * Free of Android, so the tests run on the JVM.
 */
object ProfilesPackage {

    /** The tag of a release: the app's version name with the v in front (the tags carry it, the version does not) */
    fun tag(versionName: String): String = if (versionName.startsWith("v")) versionName else "v$versionName"

    /** The url of a package as a release carries it */
    fun assetUrl(releaseTag: String, packageName: String): String =
        "${UpdateVersion.REPO_URL}/releases/download/$releaseTag/$packageName"

    /** The page that lists what a release carries: a plain web page, so no API's rate limit */
    fun assetsPage(releaseTag: String): String = "${UpdateVersion.REPO_URL}/releases/expanded_assets/$releaseTag"

    /** The names of the packages a release carries (the assets page says them), empty when it carries none */
    fun packageNamesIn(html: String): List<String> =
        Regex("""(nx2a-profiles-[A-Za-z0-9._-]+\.zip)""").findAll(html).map { it.groupValues[1] }.distinct().toList()

    /** What the online update of the package is doing */
    sealed interface Update {
        /** Nothing is going on */
        data object Idle : Update

        /** The package is on its way (0 to 1) */
        data class Downloading(val progress: Float) : Update

        /** It did not work out; the reason is the tool's own word */
        data class Failed(val reason: String) : Update
    }
}
