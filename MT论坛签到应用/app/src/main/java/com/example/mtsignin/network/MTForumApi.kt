package com.example.mtsignin.network

import com.example.mtsignin.data.local.AccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
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
 */
class MTForumApi {
    
    companion object {
        const val BASE_URL = "https://bbs.binmt.cc/"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
    
    private val cookieJar = JavaNetCookieJar(java.net.CookieManager())
    
    private val client = OkHttpClient.Builder()
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
            
            val loginhash = extractValue(loginPageHtml, "loginhash=(.*?)\">")
                ?: return SignInResult.Error("获取loginhash失败")
            val formhash = extractValue(loginPageHtml, "formhash\" value=\"(.*?)\"")
                ?: return SignInResult.Error("获取formhash失败")
            
            // 2. 执行登录
            val loginResult = login(loginhash, formhash, username, password)
            
            if (!loginResult.contains("欢迎您回来")) {
                val errorMsg = if (loginResult.contains("密码错误") || loginResult.contains("用户名错误")) {
                    "用户名或密码错误"
                } else {
                    "登录失败，请检查网络"
                }
                return SignInResult.Error(errorMsg)
            }
            
            val nickname = extractValue(loginResult, "欢迎您回来，(.*?)，现在") ?: username
            
            // 3. 获取签到页面
            val signPageHtml = getSignPage()
            val signFormhash = extractValue(signPageHtml, "formhash\" value=\"(.*?)\"")
                ?: return SignInResult.Error("获取签到formhash失败")
            
            // 检查是否已签到
            if (signPageHtml.contains("今日已签")) {
                val ranking = extractValue(signPageHtml, "您的签到排名：(.*?)</div>") ?: "未知"
                return SignInResult.Success(
                    username = nickname,
                    status = "今日已签",
                    ranking = ranking,
                    reward = "0"
                )
            }
            
            // 4. 执行签到
            val signInResult = doSignIn(signFormhash)
            val status = extractValue(signInResult, "<root><(.*?)</root>") ?: "签到完成"
            
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
    
    private fun getLoginPage(): String {
        val request = Request.Builder()
            .url("${BASE_URL}member.php?mod=logging&action=login&infloat=yes&handlekey=login&inajax=1&ajaxtarget=fwin_content_login")
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
        
        val request = Request.Builder()
            .url("${BASE_URL}member.php?mod=logging&action=login&loginsubmit=yes&handlekey=login&loginhash=$loginhash&inajax=1")
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
    
    private fun extractValue(html: String, pattern: String): String? {
        val p = Pattern.compile(pattern)
        val m = p.matcher(html)
        return if (m.find()) m.group(1) else null
    }
}

/**
 * 签到结果
 */
sealed class SignInResult {
    data class Success(
        val username: String,
        val status: String,
        val ranking: String,
        val reward: String
    ) : SignInResult()
    
    data class Error(val message: String) : SignInResult()
}