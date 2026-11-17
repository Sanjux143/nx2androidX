package com.nx.android.domain

/**
 * Where the player's game file sits.
 *
 * A netdisk that syncs into the phone's own storage (Quark's CloudDrive, Baidu, Aliyun, OneDrive, Dropbox) hands
 * a file that is not downloaded yet away in pieces: its beginning is there, its middle is not. A port that reads
 * such a file does not see a broken download but zeros in the middle of it, and the extractor says that the
 * game's own archive cannot be read (OCTOPATH TRAVELER 0: the cartridge's NCA, and the file itself was the same as
 * the one that worked, byte for byte, once it was moved out of the cloud folder).
 *
 * The folder is named, so the player is told to download the file to the phone before the port reads it.
 */
object GameSource {

    /** The netdisk folders that sync into the phone's own storage (the names are the netdisks' own) */
    private val CLOUD_FOLDERS = listOf(
        "/QuarkDownloads/CloudDrive",   // 夸克网盘
        "/QuarkCloudDrive",
        "/BaiduNetdiskDownload",        // 百度网盘
        "/AliyunDrive",                 // 阿里云盘
        "/OneDrive",
        "/Dropbox",
    )

    /**
     * The cloud folder a file is in, or null when it is in an ordinary one.
     *
     * @param path the file's path on the phone
     */
    fun cloudFolder(path: String): String? {
        val p = path.replace('\\', '/')
        return CLOUD_FOLDERS.firstOrNull { p.contains(it) }
    }
}
