package com.nx.android.data.saves

import android.content.Context
import com.nx.android.data.SaveBackup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the save flow needs from the device: the app's own folder (the live save lives there) and the
 * cache (a package is unpacked into it before it is installed).
 */
interface SavePlatform {
    val filesDir: File
    val cacheDir: File
}

@Singleton
class AndroidSavePlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : SavePlatform {
    override val filesDir: File get() = context.filesDir
    override val cacheDir: File get() = context.cacheDir
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SavePlatformModule {
    @Binds
    @Singleton
    abstract fun bindSavePlatform(impl: AndroidSavePlatform): SavePlatform
}
