package com.example.mtsignin.network

import com.example.mtsignin.data.model.RankingResult
import com.example.mtsignin.data.model.SignInResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * MT论坛签到API
 *
 * 签到流程:
 * 1. 访问登录页获取 loginhash 和 formhash
 * 2. POST 提交用户名密码登录
 * 3. 访问签到页获取 formhash
 * 4. GET 请求签到接口
 * 5. 解析结果并退出登录
 *
 * 说明:
 * - Discuz 的 loginhash 由服务端生成并写入 cookie，同时出现在登录表单 action 中（`loginhash=xxx&inajax=1`）
 * - loginhash 缺失时 Discuz 并不强制校验，登录仍可成功，因此提取失败不应阻断流程
 * - 已签到判断同时覆盖签到页面文案与签到接口返回两种来源
 * - 排名（ranking）来自签到页"您的签到排名"文案，可在不执行签到的情况下单独查询（[fetchRanking]）
 */
class MTForumApi(client: OkHttpClient) {

    companion object {
        const val BASE_URL = "https://bbs.binmt.cc/"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 已签到判定关键词（签到页面文案） */
        private val ALREADY_SIGNED_KEYWORDS = listOf(
            "今日已签",
            "今日已经签到",
            "您今天已经签到过了",
            "您已经签到过了",
            "已经签到过"
        )

        /** 登录成功标志（Discuz AJAX 模式返回 succeed，非 AJAX 模式返回 欢迎您回来） */
        private val LOGIN_SUCCESS_KEYWORDS = listOf(
            "succeed",
            "欢迎您回来",
            "login_reult_1"
        )
    }

    private val cookieManager = java.net.CookieManager()
    private val cookieJar = JavaNetCookieJar(cookieManager)

    private val client = client.newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 执行签到
     */
    suspend fun signIn(username: String, password: String): SignInResult = withContext(Dispatchers.IO) {
        try {
            // 1. 获取登录页面
            val loginPageHtml = getLoginPage()

            val loginhash = extractLoginhash(loginPageHtml)
            val formhash = extractValue(loginPageHtml, "formhash\" value=\"(.*?)\"")
                ?: return@withContext SignInResult.Error("获取formhash失败")

            // 2. 执行登录
            val loginResult = login(loginhash, formhash, username, password)

            if (!isLoginSuccess(loginResult)) {
                val errorMsg = when {
                    loginResult.contains("密码错误") || loginResult.contains("用户名错误") || loginResult.contains("不存在") ->
                        "用户名或密码错误"
                    loginResult.contains("验证码") || loginResult.contains("seccode") ->
                        "登录需要验证码"
                    else -> "登录失败，请检查网络"
                }
                return@withContext SignInResult.Error(errorMsg)
            }

            val nickname = extractValue(loginResult, "欢迎您回来，(.*?)，现在") ?: username

            // 3. 获取签到页面
            val signPageHtml = getSignPage()
            val signFormhash = extractValue(signPageHtml, "formhash\" value=\"(.*?)\"")
                ?: return@withContext SignInResult.Error("获取签到formhash失败")

            // 3.1 检查是否已签到（签到页面文案）
            if (isAlreadySigned(signPageHtml)) {
                val ranking = extractValue(signPageHtml, "您的签到排名：(.*?)</div>") ?: "未知"
                return@withContext SignInResult.Success(
                    username = nickname,
                    status = "今日已签",
                    ranking = ranking,
                    reward = "0"
                )
            }

            // 4. 执行签到
            val signInResponse = doSignIn(signFormhash)
            val status = parseSignInResponse(signInResponse)

            // 4.1 签到接口返回已签到（并发重复提交或凌晨边界情况）
            if (isAlreadySigned(status)) {
                return@withContext SignInResult.Success(
                    username = nickname,
                    status = "今日已签",
                    ranking = "未知",
                    reward = "0"
                )
            }

            // 5. 重新获取签到页面获取排名和奖励
            val resultPageHtml = getSignPage()
            val ranking = extractValue(resultPageHtml, "您的签到排名：(.*?)</div>") ?: "未知"
            val reward = extractValue(resultPageHtml, "id=\"lxreward\" value=\"(.*?)\">") ?: "0"

            // 6. 退出登录
            logout(signFormhash)

            SignInResult.Success(
                username = nickname,
                status = status,
                ranking = ranking,
                reward = reward
            )
        } catch (e: Exception) {
            SignInResult.Error(e.message ?: "网络错误")
        }
    }

    /**
     * 仅查询今日签到排名，不执行签到。
     *
     * 流程：登录 -> 获取签到页 -> 解析"您的签到排名" -> 退出登录。
     * 今日已签到则返回排名；今日未签到则返回 isSignedToday=false。
     */
    suspend fun fetchRanking(username: String, password: String): RankingResult = withContext(Dispatchers.IO) {
        try {
            // 1. 获取登录页面
            val loginPageHtml = getLoginPage()

            val loginhash = extractLoginhash(loginPageHtml)
            val formhash = extractValue(loginPageHtml, "formhash\" value=\"(.*?)\"")
                ?: return@withContext RankingResult.Error("获取formhash失败")

            // 2. 执行登录
            val loginResult = login(loginhash, formhash, username, password)

            if (!isLoginSuccess(loginResult)) {
                val errorMsg = when {
                    loginResult.contains("密码错误") || loginResult.contains("用户名错误") || loginResult.contains("不存在") ->
                        "用户名或密码错误"
                    loginResult.contains("验证码") || loginResult.contains("seccode") ->
                        "登录需要验证码"
                    else -> "登录失败，请检查网络"
                }
                return@withContext RankingResult.Error(errorMsg)
            }

            val nickname = extractValue(loginResult, "欢迎您回来，(.*?)，现在") ?: username

            // 3. 获取签到页面
            val signPageHtml = getSignPage()
            val signFormhash = extractValue(signPageHtml, "formhash\" value=\"(.*?)\"")
                ?: return@withContext RankingResult.Error("获取签到formhash失败")

            // 4. 解析排名
            val ranking = extractValue(signPageHtml, "您的签到排名：(.*?)</div>")?.trim()

            // 5. 退出登录
            logout(signFormhash)

            if (ranking.isNullOrEmpty()) {
                RankingResult.Success(username = nickname, ranking = "", isSignedToday = false)
            } else {
                RankingResult.Success(username = nickname, ranking = ranking, isSignedToday = true)
            }
        } catch (e: Exception) {
            RankingResult.Error(e.message ?: "网络错误")
        }
    }

    private fun getLoginPage(): String {
        val request = Request.Builder()
            .url("${BASE_URL}member.php?mod=logging&action=login")
            .get()
            .build()

        return client.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    private fun login(loginhash: String, formhash: String, username: String, password: String): String {
        val formBody = FormBody.Builder()
            .add("formhash", formhash)
            .add("referer", "${BASE_URL}forum.php")
            .add("loginfield", "username")
            .add("username", username)
            .add("password", password)
            .add("questionid", "0")
            .add("answer", "")
            .build()

        val loginhashParam = if (loginhash.isNotEmpty()) "&loginhash=$loginhash" else ""
        val request = Request.Builder()
            .url("${BASE_URL}member.php?mod=logging&action=login&loginsubmit=yes&handlekey=login$loginhashParam&inajax=1")
            .post(formBody)
            .build()

        return client.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    private fun getSignPage(): String {
        val request = Request.Builder()
            .url("${BASE_URL}k_misign-sign.html")
            .get()
            .build()

        return client.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    private fun doSignIn(formhash: String): String {
        val request = Request.Builder()
            .url("${BASE_URL}plugin.php?id=k_misign:sign&operation=qiandao&format=text&formhash=$formhash")
            .get()
            .build()

        return client.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    private fun logout(formhash: String): String {
        val request = Request.Builder()
            .url("${BASE_URL}member.php?mod=logging&action=logout&formhash=$formhash")
            .get()
            .build()

        return client.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    /**
     * 提取 loginhash。
     *
     * Discuz 的 loginhash 出现在：
     * 1. 登录表单 action URL 中：`loginhash=xxx&inajax=1`
     * 2. cookie 中（服务端 dsetcookie('loginhash', ...)）
     *
     * 两级提取都失败时返回空字符串（Discuz 不强制校验 loginhash，登录仍可成功）。
     */
    private fun extractLoginhash(html: String): String {
        extractValue(html, "loginhash=([a-zA-Z0-9]+)")?.let { return it }
        getCookie("loginhash")?.let { return it }
        return ""
    }

    private fun isAlreadySigned(html: String): Boolean {
        return ALREADY_SIGNED_KEYWORDS.any { html.contains(it) }
    }

    private fun isLoginSuccess(result: String): Boolean {
        return LOGIN_SUCCESS_KEYWORDS.any { result.contains(it) }
    }

    /**
     * 解析签到接口返回。
     *
     * k_misign 插件 format=text 返回格式为：
     * - `<root><![CDATA[签到成功]]></root>`
     * - `<root>签到成功</root>`
     * 两种都兼容；解析失败时原样返回。
     */
    private fun parseSignInResponse(raw: String): String {
        extractValue(raw, "<root><!\\[CDATA\\[(.*?)\\]\\]></root>")?.let { return it }
        extractValue(raw, "<root>(.*?)</root>")?.let { return it }
        return raw.trim().ifEmpty { "签到完成" }
    }

    private fun getCookie(name: String): String? {
        return try {
            cookieManager.cookieStore.get(URI(BASE_URL)).firstOrNull { it.name == name }?.value
        } catch (e: Exception) {
            null
        }
    }

    private fun extractValue(html: String, pattern: String): String? {
        val p = Pattern.compile(pattern)
        val m = p.matcher(html)
        return if (m.find()) m.group(1) else null
    }
}
