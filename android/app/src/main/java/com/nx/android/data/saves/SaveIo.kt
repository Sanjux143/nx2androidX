package com.nx.android.data.saves

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nx.android.domain.saves.SaveSource
import com.nx.android.domain.saves.SaveTarget
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The file manager's places, in the app's terms.
 *
 * The player says where a package goes and where it comes from with the system file manager; what he picks
 * is a document, and the app reads and writes it through the content resolver.
 */
interface SaveIo {
    /** where an exported package goes: the document the player picked */
    fun target(uri: Uri): SaveTarget

    /** where an imported package comes from: the document the player picked, with the name it shows */
    fun source(uri: Uri): SaveSource
}

@Singleton
class AndroidSaveIo @Inject constructor(
    @ApplicationContext private val context: Context,
) : SaveIo {
    override fun target(uri: Uri): SaveTarget = object : SaveTarget {
        override fun open(): OutputStream? = context.contentResolver.openOutputStream(uri)
    }

    override fun source(uri: Uri): SaveSource = object : SaveSource {
        override val name: String = displayName(uri)
        override fun open(): InputStream? = context.contentResolver.openInputStream(uri)
    }

    /** the name the file manager shows for a document (the title a package carries is in it) */
    private fun displayName(uri: Uri): String {
        val shown = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && c.moveToFirst()) c.getString(column) else null
            }
        }.getOrNull()
        return shown?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "save.zip"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SaveIoModule {
    @Binds
    @Singleton
    abstract fun bindSaveIo(impl: AndroidSaveIo): SaveIo
}
