package com.nx.android.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The app-private paths shared by the porter, library and storage features. */
@Singleton
class AppPaths @Inject constructor(
    @ApplicationContext context: Context,
) {
    val files = context.filesDir
    val rootfs = File(files, "rootfs")
    val tool = File(files, "tool")
    val home = File(files, "home")
    val nxHome = File(files, "nx2a")
    val tmp = File(files, "tmp")
    val ports = File(files, "ports")
    val work = File(files, "work")
    val runtimes = File(files, "runtimes")   // the games' runtimes, one folder per title ID (the launch loads it in place)
}
