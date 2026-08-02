package com.example.mtsignin.data.repository

import com.example.mtsignin.data.local.AccountDao
import com.example.mtsignin.data.local.AccountEntity
import com.example.mtsignin.data.model.RankingResult
import com.example.mtsignin.data.model.SignInResult
import com.example.mtsignin.network.MTForumApi
import com.example.mtsignin.util.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignRepository @Inject constructor(
    private val client: OkHttpClient,
    private val accountDao: AccountDao
) {
    /** 并发签到时的最大并发数，避免同时发起过多请求对论坛造成压力 */
    private val signInSemaphore = Semaphore(5)

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
        return signInOneWith(account, newApi())
    }

    /**
     * 签到所有启用的账号。
     *
     * 多个账号并行执行，大幅提升批量签到速度；每个账号使用独立的 API 实例
     * 与 Cookie 会话，互不干扰。
     *
     * @param onProgress 每完成一个账号回调一次，参数为 (已完成数, 总数)
     */
    suspend fun signInAll(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Pair<AccountEntity, SignInResult>> {
        val accounts = accountDao.getAllEnabled()
        if (accounts.isEmpty()) return emptyList()

        val doneCount = AtomicInteger(0)

        return coroutineScope {
            accounts.map { account ->
                async(Dispatchers.IO) {
                    val result = signInSemaphore.withPermit {
                        signInOneWith(account, newApi())
                    }
                    val done = doneCount.incrementAndGet()
                    onProgress(done, accounts.size)
                    Pair(account, result)
                }
            }.awaitAll()
        }
    }

    /**
     * 创建独立 API 实例：每个账号使用独立的 Cookie 会话，保证并行签到互不干扰
     */
    private fun newApi(): MTForumApi = MTForumApi(client)

    /**
     * 签到单个账号（使用指定的 API 实例）
     */
    private suspend fun signInOneWith(account: AccountEntity, api: MTForumApi): SignInResult {
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
                        lastSignInReward = result.reward,
                        lastToken = api.lastToken ?: account.lastToken
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

        // 签到成功后若排名未获取到（"未知"），自动补查一次排名，保证签到后即可看到排名
        if (result is SignInResult.Success && result.ranking == "未知") {
            refreshRankingWith(account, api)
        }

        return result
    }

    /**
     * 刷新单个账号的今日签到排名（仅查询，不执行签到）
     */
    suspend fun refreshRanking(account: AccountEntity): RankingResult {
        return refreshRankingWith(account, newApi())
    }

    /**
     * 刷新单个账号的今日签到排名（使用指定的 API 实例）
     */
    private suspend fun refreshRankingWith(account: AccountEntity, api: MTForumApi): RankingResult {
        val password = try {
            CryptoUtils.decrypt(account.passwordEncrypted)
        } catch (e: Exception) {
            return RankingResult.Error("密码解密失败")
        }

        val result = api.fetchRanking(account.username, password)

        when (result) {
            is RankingResult.Success -> {
                accountDao.update(
                    account.copy(
                        nickname = result.username,
                        lastSignInRanking = if (result.isSignedToday) result.ranking else null,
                        lastToken = api.lastToken ?: account.lastToken
                    )
                )
            }
            is RankingResult.Error -> {
                // 刷新失败不覆盖原有签到状态，仅保留错误信息供 UI 提示
            }
        }

        return result
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
