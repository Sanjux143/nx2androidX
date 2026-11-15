package com.nx.android.data.saves

import com.nx.android.core.di.IoDispatcher
import com.nx.android.data.SaveBackup
import com.nx.android.domain.saves.SaveOperationException
import com.nx.android.domain.saves.SaveRepository
import com.nx.android.domain.saves.SaveSource
import com.nx.android.domain.saves.SaveTarget
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class SaveRepositoryImpl @Inject constructor(
    private val platform: SavePlatform,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val saveSwap: SaveSwap,
) : SaveRepository {
    private val locks = ConcurrentHashMap<String, Mutex>()

    override fun titleIdInPackage(name: String): String? = SaveBackup.titleIdIn(name)

    override fun suggestedPackageName(titleId: String, gameName: String): String =
        SaveBackup.packageName(gameName, titleId, stamp())

    /**
     * The live save as a package, written where the player says: the file manager picked the place, the
     * package goes straight there - no folder of packages in between, nothing to share afterwards.
     */
    override suspend fun export(titleId: String, gameName: String, target: SaveTarget): Result<Unit> = runResult {
        withTitleLock(titleId) {
            checkTitle(titleId)
            val live = SaveBackup.liveDir(platform.filesDir, titleId)
            val device = SaveBackup.deviceDir(platform.filesDir, titleId)
            val tree = SaveBackup.packageName(gameName, titleId, stamp()).removeSuffix(".${SaveBackup.EXT}")
            val out = target.open() ?: throw IOException("Could not open the place the file manager gave")
            withContext(io) {
                if (!SaveBackup.export(live, device, tree, out)) {
                    throw SaveOperationException(SaveOperationException.Reason.NO_SAVES)
                }
            }
        }
    }

    /**
     * A package replaces the live save. The package is unpacked into the cache first and only then
     * installed, so a broken package never touches the save.
     */
    override suspend fun import(titleId: String, source: SaveSource): Result<Unit> = runResult {
        withTitleLock(titleId) {
            checkTitle(titleId)
            checkPackageIdentity(titleId, source.name)
            val tmp = File(platform.cacheDir, "save-import-${titleId.lowercase()}")
            withContext(io) {
                tmp.deleteRecursively()
                try {
                    val stream = source.open() ?: throw IOException("Could not read the file the file manager gave")
                    val unpacked = stream.use { SaveBackup.unpack(it, tmp) }
                    withContext(NonCancellable) {
                        saveSwap.install(unpacked.tree, unpacked.device,
                            SaveBackup.liveDir(platform.filesDir, titleId),
                            SaveBackup.deviceDir(platform.filesDir, titleId))
                    }
                } finally {
                    withContext(NonCancellable) { tmp.deleteRecursively() }
                }
            }
        }
    }

    private suspend fun <T> withTitleLock(titleId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(titleId) { Mutex() }.withLock { block() }

    private fun checkTitle(titleId: String) {
        if (!TITLE_ID.matches(titleId)) throw SaveOperationException(SaveOperationException.Reason.INVALID_TITLE_ID)
    }

    private fun checkPackageIdentity(titleId: String, name: String) {
        val packageTitleId = SaveBackup.titleIdIn(name)
        if (packageTitleId != null && !packageTitleId.equals(titleId, ignoreCase = true)) {
            throw SaveOperationException(SaveOperationException.Reason.TITLE_MISMATCH, packageTitleId)
        }
    }

    private suspend fun <T> runResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        val TITLE_ID = Regex("[0-9A-Fa-f]{16}")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SaveDataModule {
    @Binds
    @Singleton
    abstract fun bindSaveRepository(impl: SaveRepositoryImpl): SaveRepository
}
