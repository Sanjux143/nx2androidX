package com.nx.android.data

import com.nx.android.domain.Ported
import java.io.File
import java.io.IOException

/** Copy the manifest-selected runtime and its matching hooks, never reusing a size-matched binary. */
object RuntimeLibraries {
    val hooks = setOf("libhook_impl.so", "libmain_hook.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")

    fun copyForLaunch(port: Ported, destination: File) {
        destination.mkdirs()
        // A pack made by the older runtime update keeps its runtime in a .runtime- folder; the current
        // one puts it back in the pack's own places, where the hooks sit beside the drivers.
        val generation = port.host.parentFile?.name?.startsWith(".runtime-") == true
        val drivers = File(port.pack, "drivers")
        val hookDir = if (generation) port.host.parentFile!! else drivers
        val components = listOf(port.host) + hooks.map { File(hookDir, it) }
        if (components.any { !it.isFile || !it.canonicalFile.toPath().startsWith(port.pack.canonicalFile.toPath()) })
            throw IOException("Missing runtime components. Update the runtime and retry.")
        // Clear only managed launch copies. User-selected drivers live outside this cache.
        destination.listFiles()?.filter { it.name.endsWith(".so") || it.name.endsWith(".part") }?.forEach { it.delete() }
        for (source in components) copy(source, File(destination, source.name))
        drivers.listFiles()?.filter { it.isFile && it.name !in hooks && it.name != port.host.name }?.forEach { copy(it, File(destination, it.name)) }
    }

    private fun copy(source: File, target: File) {
        val pending = File(target.parentFile, target.name + ".part")
        source.inputStream().use { input -> pending.outputStream().use { input.copyTo(it) } }
        if (!pending.renameTo(target)) throw IOException("Cannot replace ${target.name}")
    }
}
