package com.example.mtsignin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mtsignin.data.local.AccountEntity
import com.example.mtsignin.data.model.SignInResult
import com.example.mtsignin.data.repository.SignRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: SignRepository
) : ViewModel() {

    val accounts: StateFlow<List<AccountEntity>> = repository.getAllAccounts()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _signInState = MutableStateFlow(SignInUiState())
    val signInState: StateFlow<SignInUiState> = _signInState

    fun addAccount(username: String, password: String) {
        viewModelScope.launch {
            repository.addAccount(username, password)
        }
    }

    fun deleteAccount(account: AccountEntity) {
        viewModelScope.launch {
            repository.deleteAccount(account)
        }
    }

    fun toggleAccountEnabled(account: AccountEntity) {
        viewModelScope.launch {
            repository.updateAccount(
                account.copy(isEnabled = !account.isEnabled)
            )
        }
    }

    fun signInOne(account: AccountEntity) {
        viewModelScope.launch {
            _signInState.update { it.copy(isSigningIn = true) }

            val result = repository.signInOne(account)

            _signInState.update {
                it.copy(
                    isSigningIn = false,
                    successCount = if (result is SignInResult.Success) it.successCount + 1 else it.successCount,
                    failCount = if (result is SignInResult.Error) it.failCount + 1 else it.failCount,
                    error = if (result is SignInResult.Error) result.message else null
                )
            }
        }
    }

    fun signInAll() {
        viewModelScope.launch {
            _signInState.update {
                it.copy(
                    isSigningIn = true,
                    successCount = 0,
                    failCount = 0,
                    error = null,
                    currentProgress = 0,
                    totalCount = accounts.value.count { acc -> acc.isEnabled }
                )
            }

            val results = repository.signInAll()

            var successCount = 0
            var failCount = 0

            results.forEachIndexed { index, pair ->
                val result = pair.second
                if (result is SignInResult.Success) {
                    successCount++
                } else {
                    failCount++
                }

                _signInState.update {
                    it.copy(
                        currentProgress = index + 1,
                        successCount = successCount,
                        failCount = failCount
                    )
                }
            }

            _signInState.update {
                it.copy(isSigningIn = false)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _signInState.update {
                SignInUiState(
                    isSigningIn = false,
                    successCount = 0,
                    failCount = 0,
                    error = null
                )
            }
        }
    }
}