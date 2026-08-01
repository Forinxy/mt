package com.example.mtsignin.data.repository

import com.example.mtsignin.data.local.AccountDao
import com.example.mtsignin.data.local.AccountEntity
import com.example.mtsignin.data.model.SignInResult
import com.example.mtsignin.network.MTForumApi
import com.example.mtsignin.util.CryptoUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignRepository @Inject constructor(
    private val api: MTForumApi,
    private val accountDao: AccountDao
) {
    val accounts: Flow<List<AccountEntity>> = accountDao.getAll()

    fun getAllAccounts(): Flow<List<AccountEntity>> = accountDao.getAll()
    
    /**
     * 添加账号
     */
    suspend fun addAccount(username: String, password: String): Long {
        val encryptedPassword = CryptoUtils.encrypt(password)
        val account = AccountEntity(
            username = username,
            passwordEncrypted = encryptedPassword
        )
        return accountDao.insert(account)
    }
    
    /**
     * 签到单个账号
     */
    suspend fun signInOne(account: AccountEntity): SignInResult {
        val password = try {
            CryptoUtils.decrypt(account.passwordEncrypted)
        } catch (e: Exception) {
            return SignInResult.Error("密码解密失败")
        }
        
        val result = api.signIn(account.username, password)
        
        // 更新签到状态
        when (result) {
            is SignInResult.Success -> {
                accountDao.update(
                    account.copy(
                        nickname = result.username,
                        lastSignInTime = System.currentTimeMillis(),
                        lastSignInStatus = result.status,
                        lastSignInRanking = result.ranking,
                        lastSignInReward = result.reward
                    )
                )
            }
            is SignInResult.Error -> {
                accountDao.update(
                    account.copy(
                        lastSignInTime = System.currentTimeMillis(),
                        lastSignInStatus = result.message
                    )
                )
            }
        }
        
        return result
    }
    
    /**
     * 签到所有启用的账号
     */
    suspend fun signInAll(): List<Pair<AccountEntity, SignInResult>> {
        val accounts = accountDao.getAllEnabled()
        val results = mutableListOf<Pair<AccountEntity, SignInResult>>()
        
        for (account in accounts) {
            val result = signInOne(account)
            results.add(Pair(account, result))
            
            // 每次签到间隔1秒，避免请求过快
            if (accounts.indexOf(account) < accounts.size - 1) {
                Thread.sleep(1000)
            }
        }
        
        return results
    }
    
    /**
     * 删除账号
     */
    suspend fun deleteAccount(account: AccountEntity) {
        accountDao.delete(account)
    }
    
    /**
     * 切换账号启用状态
     */
    suspend fun toggleAccount(account: AccountEntity) {
        accountDao.update(account.copy(isEnabled = !account.isEnabled))
    }
    
    /**
     * 更新账号
     */
    suspend fun updateAccount(account: AccountEntity) {
        accountDao.update(account)
    }
}