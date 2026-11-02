package com.alo.android

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AloAccountScopedSaveTest {
    private class Recorder(var activeAccountId: Int?) {
        val saved = mutableListOf<String>()
        val rolledBack = mutableListOf<Pair<String, String>>()
        var sessionExpired = 0

        fun run(accountId: Int, save: suspend () -> String) = runBlocking {
            AloAccountScopedSave.run(
                accountId = accountId,
                activeAccountId = { activeAccountId },
                previous = "old",
                save = save,
                onSaved = { saved += it },
                onRolledBack = { previous, message -> rolledBack += previous to message },
                onSessionExpired = { sessionExpired++ },
                failureMessage = "failed"
            )
        }
    }

    private fun apiError(status: Int) = AloApiException(status, null, "server said $status", JSONObject())

    @Test
    fun appliesSavedValuesWhileTheSameAccountIsActive() {
        val recorder = Recorder(activeAccountId = 7)
        recorder.run(7) { "saved" }
        assertEquals(listOf("saved"), recorder.saved)
    }

    @Test
    fun dropsSavedValuesWhenAnotherAccountIsActiveByNow() {
        val recorder = Recorder(activeAccountId = 7)
        recorder.run(7) {
            recorder.activeAccountId = 9
            "saved"
        }
        assertTrue(recorder.saved.isEmpty())
    }

    @Test
    fun dropsSavedValuesWhileNoAccountIsLoaded() {
        val recorder = Recorder(activeAccountId = 7)
        recorder.run(7) {
            recorder.activeAccountId = null
            "saved"
        }
        assertTrue(recorder.saved.isEmpty())
    }

    @Test
    fun rollsBackWithTheServerMessageForTheSameAccount() {
        val recorder = Recorder(activeAccountId = 7)
        recorder.run(7) { throw apiError(400) }
        assertEquals(listOf("old" to "server said 400"), recorder.rolledBack)
        assertEquals(0, recorder.sessionExpired)
    }

    @Test
    fun rollsBackWithTheGenericMessageOnNetworkFailure() {
        val recorder = Recorder(activeAccountId = 7)
        recorder.run(7) { throw IOException("offline") }
        assertEquals(listOf("old" to "failed"), recorder.rolledBack)
    }

    @Test
    fun expiresTheSessionOnA401ForTheSameAccount() {
        val recorder = Recorder(activeAccountId = 7)
        recorder.run(7) { throw apiError(401) }
        assertEquals(1, recorder.sessionExpired)
        assertTrue(recorder.rolledBack.isEmpty())
    }

    @Test
    fun aLate401FromTheOldAccountDoesNotEndTheNewSession() {
        val recorder = Recorder(activeAccountId = 7)
        recorder.run(7) {
            recorder.activeAccountId = 9
            throw apiError(401)
        }
        assertEquals(0, recorder.sessionExpired)
        assertTrue(recorder.rolledBack.isEmpty())
    }

    @Test
    fun aLateFailureFromTheOldAccountDoesNotRollBackOrShowAnError() {
        val recorder = Recorder(activeAccountId = 7)
        recorder.run(7) {
            recorder.activeAccountId = 9
            throw apiError(500)
        }
        assertTrue(recorder.rolledBack.isEmpty())
        recorder.run(7) {
            recorder.activeAccountId = 9
            throw IOException("offline")
        }
        assertTrue(recorder.rolledBack.isEmpty())
    }
}
