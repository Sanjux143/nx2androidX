package com.nx.android.core.ui

import com.nx.android.R
import com.nx.android.data.AppLocale
import com.nx.android.data.drivers.Drivers
import com.nx.android.data.SaveBackup
import com.nx.android.domain.saves.SaveOperationException

/**
 * The app's own words for a failure the data layer reported.
 *
 * The data layer says what went wrong in a code - it stays free of Android, so its tests run on the JVM -
 * and this is where the code becomes a sentence the player reads, in the language the app shows.
 */
fun failureText(locale: AppLocale, e: Throwable): String = when (e) {
    is SaveOperationException -> when (e.reason) {
        SaveOperationException.Reason.TITLE_MISMATCH -> locale.str(R.string.lib_other_games_save, e.message.orEmpty())
        SaveOperationException.Reason.NO_SAVES -> locale.str(R.string.lib_no_saves)
        else -> locale.str(R.string.common_error_unknown_reason)
    }
    is Drivers.Refused -> when (e.refusal) {
        Drivers.Refusal.NO_DRIVER -> locale.str(R.string.driver_refused_no_driver)
        Drivers.Refusal.NO_LIBRARY -> locale.str(R.string.driver_refused_no_library)
        Drivers.Refusal.NOT_A_DRIVER -> locale.str(R.string.driver_refused_not_a_driver)
    }
    is SaveBackup.Refused -> when (e.refusal) {
        SaveBackup.Refusal.TOO_MANY_ENTRIES -> locale.str(R.string.save_pkg_too_many)
        SaveBackup.Refusal.BAD_PATH -> locale.str(R.string.save_pkg_bad_path, e.entry ?: "")
        SaveBackup.Refusal.PATH_ESCAPES -> locale.str(R.string.save_pkg_escapes, e.entry ?: "")
        SaveBackup.Refusal.TOO_BIG -> locale.str(R.string.save_pkg_too_big)
        SaveBackup.Refusal.NOT_A_PACKAGE -> locale.str(R.string.save_pkg_not_a_package)
    }
    else -> e.message ?: locale.str(R.string.common_error_unknown_reason)
}
