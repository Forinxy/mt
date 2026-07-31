package com.example.mtsignin.data.model

sealed class SignInResult {
    data class Success(
        val username: String,
        val status: String,
        val ranking: String,
        val reward: String
    ) : SignInResult()

    data class Error(
        val message: String
    ) : SignInResult()
}