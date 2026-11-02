package com.alo.android

import kotlinx.coroutines.CancellationException

/**
 * A settings save outlives the screen that asked for it. When the user switches account while it is in
 * flight, its answer (saved values, a rollback, an error message, even a 401) belongs to the old account
 * and must not touch the one that is active now.
 */
internal object AloAccountScopedSave {
    suspend fun <T> run(
        accountId: Int,
        activeAccountId: () -> Int?,
        previous: T,
        save: suspend () -> T,
        onSaved: (T) -> Unit,
        onRolledBack: (previous: T, message: String) -> Unit,
        onSessionExpired: () -> Unit,
        failureMessage: String
    ) {
        fun stillActive() = activeAccountId() == accountId
        try {
            val saved = save()
            if (stillActive()) onSaved(saved)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AloApiException) {
            if (!stillActive()) return
            if (error.status == 401) onSessionExpired() else onRolledBack(previous, error.message)
        } catch (_error: Exception) {
            if (stillActive()) onRolledBack(previous, failureMessage)
        }
    }
}
