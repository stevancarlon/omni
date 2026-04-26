package com.omni.assistant.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.omni.assistant.R

data class GoogleAccount(
    val idToken: String,
    val email: String,
    val displayName: String,
)

class GoogleSignInClient(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(): GoogleAccount {
        val webClientId = context.getString(R.string.google_web_client_id)
        if (webClientId.isBlank()) {
            throw IllegalStateException("Google sign-in is not configured yet. Add google_web_client_id.")
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val credential = try {
            credentialManager.getCredential(context, request).credential
        } catch (error: GetCredentialException) {
            throw IllegalStateException(error.message ?: "Google sign-in was cancelled or unavailable", error)
        }

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            return GoogleAccount(
                idToken = googleCredential.idToken,
                email = googleCredential.id,
                displayName = googleCredential.displayName.orEmpty(),
            )
        }

        throw IllegalStateException("Google sign-in returned an unsupported credential")
    }
}
