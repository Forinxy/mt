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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignRepository @Inject constructor(
    private val client: OkHttpClient,
    private val accountDao: AccountDao
) {
    /** 并发签到时的最大并发数。并发过高易触发论坛 WAF 限流，取较小值保证稳定 */
    private val signInSemaphore = Semaphore(3)

    /** 单个账号签到的超时保护，避免个别账号卡住导致整体一直转圈 */
    private val accountTimeoutMillis = 120_000L

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
                        try {
                            // 单账号超时保护：超时按失败处理，保证批量签到能正常收尾
                            withTimeoutOrNull(accountTimeoutMillis) {
                                signInOneWith(account, newApi())
                            } ?: SignInResult.Error("签到超时")
                        } catch (e: Exception) {
                            SignInResult.Error(e.message ?: "签到异常")
                        }
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
        // 本地去重：当天已成功签到过则直接返回本地记录结果，不再发起网络请求，
        // 避免同一账号一天内重复请求触发论坛风控
        if (isSignedToday(account)) {
            return SignInResult.Success(
                username = account.nickname ?: account.username,
                status = account.lastSignInStatus ?: "今日已签",
                ranking = account.lastSignInRanking ?: "未知",
                reward = account.lastSignInReward ?: "0"
            )
        }

        val password = try {
            CryptoUtils.decrypt(account.passwordEncrypted)
        } catch (e: Exception) {
            return SignInResult.Error("密码解密失败")
        }

        val result = api.signIn(account.username, password)

        // 更新签到状态
        when (result) {
            is SignInResult.Success -> {
                val updated = account.copy(
                    nickname = result.username,
                    lastSignInTime = System.currentTimeMillis(),
                    lastSignInStatus = result.status,
                    lastSignInRanking = result.ranking,
                    lastSignInReward = result.reward,
                    lastToken = api.lastToken ?: account.lastToken
                )
                accountDao.update(updated)

                // 签到成功后若排名未获取到（"未知"），自动补查一次排名，保证签到后即可看到排名。
                // 传入更新后的实体，避免补查时用旧实体覆盖刚写入的签到时间/状态
                if (result.ranking == "未知") {
                    refreshRankingWith(updated, api)
                }
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
     * 刷新单个账号的今日签到排名（仅查询，不执行签到）
     */
    suspend fun refreshRanking(account: AccountEntity): RankingResult {
        return refreshRankingWith(account, newApi())
    }

    /**
     * 刷新单个账号的今日签到排名（使用指定的 API 实例）
     */
    private suspend fun refreshRankingWith(account: AccountEntity, api: MTForumApi): RankingResult {
        // 本地去重：当天已成功查询过排名则直接返回本地记录结果，不再发起网络请求，
        // 避免同一账号一天内重复查询触发论坛风控
        if (isRankingQueriedToday(account)) {
            val ranking = account.lastSignInRanking ?: ""
            return RankingResult.Success(
                username = account.nickname ?: account.username,
                ranking = ranking,
                isSignedToday = ranking.isNotEmpty()
            )
        }

        val password = try {
            CryptoUtils.decrypt(account.passwordEncrypted)
        } catch (e: Exception) {
            return RankingResult.Error("密码解密失败")
        }

        val result = api.fetchRanking(account.username, password)

        when (result) {
            is RankingResult.Success -> {
                // 基于数据库最新记录更新，避免覆盖签到/其他操作刚写入的状态与时间
                val latest = accountDao.getById(account.id) ?: account
                accountDao.update(
                    latest.copy(
                        nickname = result.username,
                        lastSignInRanking = if (result.isSignedToday) result.ranking else null,
                        lastToken = api.lastToken ?: latest.lastToken,
                        lastRankingQueryDate = todayDateString()
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

    /**
     * 判断该账号当天是否已成功签到（依据本地记录，不触发网络请求）。
     * 仅当最近一次签到发生在今天、且状态为成功类文案（已签/成功/完成）时视为已签到
     */
    private fun isSignedToday(account: AccountEntity): Boolean {
        val time = account.lastSignInTime ?: return false
        if (!isSameDay(time, System.currentTimeMillis())) return false
        val status = account.lastSignInStatus ?: return false
        return status.contains("已签") || status.contains("成功") || status.contains("完成")
    }

    /**
     * 判断该账号当天是否已查询过排名（依据本地记录，不触发网络请求）
     */
    private fun isRankingQueriedToday(account: AccountEntity): Boolean {
        val date = account.lastRankingQueryDate ?: return false
        return date == todayDateString()
    }

    private fun isSameDay(t1: Long, t2: Long): Boolean =
        dateString(t1) == dateString(t2)

    private fun dateString(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

    private fun todayDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}
