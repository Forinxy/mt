package com.example.mtsignin.data.model

sealed class RankingResult {
    data class Success(
        val username: String,
        val ranking: String,
        val isSignedToday: Boolean = true
    ) : RankingResult()

    data class Error(
        val message: String
    ) : RankingResult()
}
