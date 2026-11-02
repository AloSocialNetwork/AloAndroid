package com.alo.android

import android.app.Activity
import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential

/**
 * Thin adapter around Android Credential Manager. It deliberately contains no
 * fallback credential or locally generated key: when the device/provider cannot
 * create or retrieve a passkey, the caller receives the real platform failure.
 */
internal class AloPasskeyClient(context: Context) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun create(activity: Activity, optionsJson: String): String {
        val response = credentialManager.createCredential(
            context = activity,
            request = CreatePublicKeyCredentialRequest(requestJson = optionsJson)
        )
        return (response as? CreatePublicKeyCredentialResponse)
            ?.registrationResponseJson
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Устройство не вернуло ответ ключа доступа.")
    }

    suspend fun authenticate(activity: Activity, optionsJson: String): String {
        val response = credentialManager.getCredential(
            context = activity,
            request = GetCredentialRequest(
                listOf(GetPublicKeyCredentialOption(requestJson = optionsJson))
            )
        )
        return (response.credential as? PublicKeyCredential)
            ?.authenticationResponseJson
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("На устройстве не найден подходящий ключ доступа.")
    }
}
