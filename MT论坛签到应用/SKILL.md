---
name: mt-forum-signin
version: "1.0.0"
description: MT论坛自动签到应用 - 支持多账号管理、自动签到、签到状态查看、定时提醒等功能。
---

# MT论坛签到应用

## 项目结构

```
app/
├── app/src/main/java/com/example/mtsignin/
│   ├── App.kt
│   ├── MainActivity.kt
│   ├── data/
│   │   ├── local/
│   │   │   ├── AppDatabase.kt
│   │   │   ├── AccountDao.kt
│   │   │   └── AccountEntity.kt
│   │   ├── repository/
│   │   │   └── SignRepository.kt
│   │   └── model/
│   │       ├── Account.kt
│   │       └── SignInResult.kt
│   ├── ui/
│   │   ├── MainScreen.kt
│   │   ├── AddAccountDialog.kt
│   │   └── AccountListItem.kt
│   ├── network/
│   │   └── MTForumApi.kt
│   └── service/
│       └── SignInWorker.kt
├── build.gradle.kts
└── AndroidManifest.xml
```

---

## 一、签到机制分析

### 论坛系统
- **类型**: Discuz! 论坛
- **签到插件**: k_misign

### 登录流程
1. 访问登录页面获取 `loginhash` 和 `formhash`
2. POST 提交用户名密码
3. 获取 Cookie: `saltkey`, `auth`

### 签到流程
1. 访问签到页面获取 `formhash`
2. GET 请求签到接口
3. 解析签到结果

### 关键接口

| 接口 | 方法 | 说明 |
|-----|------|------|
| `/member.php?mod=logging&action=login` | GET | 登录页面 |
| `/member.php?mod=logging&action=login&loginsubmit=yes` | POST | 提交登录 |
| `/k_misign-sign.html` | GET | 签到页面 |
| `/plugin.php?id=k_misign:sign&operation=qiandao` | GET | 执行签到 |

---

## 二、网络请求实现

### MTForumApi.kt

```kotlin
package com.example.mtsignin.network

import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object MTForumApi {
    private const val BASE_URL = "https://bbs.binmt.cc/"
    
    private val cookieJar = JavaNetCookieJar(java.net.CookieManager())
    
    private val okHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    val instance: MTForumService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .build()
            .create(MTForumService::class.java)
    }
}

interface MTForumService {
    @GET("member.php?mod=logging&action=login&infloat=yes&handlekey=login&inajax=1")
    suspend fun getLoginPage(): ResponseBody
    
    @FormUrlEncoded
    @POST("member.php?mod=logging&action=login&loginsubmit=yes")
    suspend fun login(
        @Field("formhash") formhash: String,
        @Field("referer") referer: String = "https://bbs.binmt.cc/forum.php",
        @Field("loginfield") loginfield: String = "username",
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("questionid") questionid: String = "0",
        @Field("answer") answer: String = ""
    ): ResponseBody
    
    @GET("k_misign-sign.html")
    suspend fun getSignPage(): ResponseBody
    
    @GET("plugin.php")
    suspend fun signIn(
        @Query("id") id: String = "k_misign:sign",
        @Query("operation") operation: String = "qiandao",
        @Query("format") format: String = "text",
        @Query("formhash") formhash: String
    ): ResponseBody
    
    @GET("member.php?mod=logging&action=logout")
    suspend fun logout(@Query("formhash") formhash: String): ResponseBody
}
```

### SignInRepository.kt

```kotlin
package com.example.mtsignin.data.repository

import com.example.mtsignin.data.model.Account
import com.example.mtsignin.data.model.SignInResult
import com.example.mtsignin.network.MTForumApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.util.regex.Pattern

class SignRepository {
    
    private val api = MTForumApi.instance
    
    suspend fun signIn(account: Account): SignInResult = withContext(Dispatchers.IO) {
        try {
            // 1. 获取登录页面，提取 loginhash 和 formhash
            val loginPageHtml = api.getLoginPage().string()
            val loginhash = extractValue(loginPageHtml, "loginhash=(.*?)\">") ?: return SignInResult.Error("获取loginhash失败")
            val formhash = extractValue(loginPageHtml, "formhash\" value=\"(.*?)\"") ?: return SignInResult.Error("获取formhash失败")
            
            // 2. 执行登录
            val loginResult = api.login(
                formhash = formhash,
                username = account.username,
                password = account.password
            ).string()
            
            if (!loginResult.contains("欢迎您回来")) {
                return SignInResult.Error("登录失败，请检查账号密码")
            }
            
            val nickname = extractValue(loginResult, "欢迎您回来，(.*?)，现在") ?: account.username
            
            // 3. 获取签到页面
            val signPageHtml = api.getSignPage().string()
            val signFormhash = extractValue(signPageHtml, "formhash\" value=\"(.*?)\"") ?: return SignInResult.Error("获取签到formhash失败")
            
            // 4. 执行签到
            val signInResult = api.signIn(formhash = signFormhash).string()
            val status = extractValue(signInResult, "<root><(.*?)</root>") ?: "未知状态"
            
            // 5. 获取签到信息
            val ranking = extractValue(signPageHtml, "您的签到排名：(.*?)</div>") ?: "未知"
            val reward = extractValue(signPageHtml, "id=\"lxreward\" value=\"(.*?)\">") ?: "0"
            
            // 6. 退出登录
            api.logout(signFormhash)
            
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
    
    private fun extractValue(html: String, pattern: String): String? {
        val p = Pattern.compile(pattern)
        val m = p.matcher(html)
        return if (m.find()) m.group(1) else null
    }
}
```

---

## 三、数据模型

### AccountEntity.kt

```kotlin
package com.example.mtsignin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val password: String,  // 加密存储
    val nickname: String? = null,
    val lastSignInTime: Long? = null,
    val lastSignInStatus: String? = null,
    val isEnabled: Boolean = true
)
```

### SignInResult.kt

```kotlin
package com.example.mtsignin.data.model

sealed class SignInResult {
    data class Success(
        val username: String,
        val status: String,
        val ranking: String,
        val reward: String
    ) : SignInResult()
    
    data class Error(val message: String) : SignInResult()
}
```

---

## 四、UI界面

### MainScreen.kt

```kotlin
package com.example.mtsignin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mtsignin.data.local.AccountEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val isSigningIn by viewModel.isSigningIn.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MT论坛签到") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加账号")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 一键签到所有
            if (accounts.isNotEmpty()) {
                Button(
                    onClick = { viewModel.signInAll() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = !isSigningIn
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("签到中...")
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("一键签到全部")
                    }
                }
            }
            
            // 账号列表
            if (accounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "点击下方按钮添加账号",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(accounts) { account ->
                        AccountListItem(
                            account = account,
                            onSignIn = { viewModel.signInOne(account) },
                            onDelete = { viewModel.deleteAccount(account) },
                            onToggle = { viewModel.toggleAccount(account) }
                        )
                    }
                }
            }
        }
        
        // 添加账号对话框
        if (showAddDialog) {
            AddAccountDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { username, password ->
                    viewModel.addAccount(username, password)
                    showAddDialog = false
                }
            )
        }
    }
}
```

### AccountListItem.kt

```kotlin
@Composable
fun AccountListItem(
    account: AccountEntity,
    onSignIn: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { 
                Text(account.nickname ?: account.username) 
            },
            supportingContent = {
                Column {
                    if (account.lastSignInTime != null) {
                        Text(
                            "上次签到: ${formatTime(account.lastSignInTime)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (account.lastSignInStatus != null) {
                        Text(
                            "状态: ${account.lastSignInStatus}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (account.lastSignInStatus.contains("已签") || account.lastSignInStatus.contains("成功"))
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            trailingContent = {
                Row {
                    // 开关
                    Switch(
                        checked = account.isEnabled,
                        onCheckedChange = { onToggle() }
                    )
                    // 签到按钮
                    IconButton(onClick = onSignIn) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "签到",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 删除按钮
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }
}
```

---

## 五、后台定时签到

### SignInWorker.kt

```kotlin
package com.example.mtsignin.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.example.mtsignin.data.repository.SignRepository
import com.example.mtsignin.data.local.AccountDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@HiltWorker
class SignInWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val signRepository: SignRepository,
    private val accountDao: AccountDao
) : CoroutineWorker(context, workerParams) {
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val accounts = accountDao.getAllEnabled()
            
            accounts.forEach { account ->
                val result = signRepository.signIn(account)
                
                // 更新签到状态
                accountDao.update(
                    account.copy(
                        lastSignInTime = System.currentTimeMillis(),
                        lastSignInStatus = if (result is SignInResult.Success) {
                            "${result.status} - 排名:${result.ranking}"
                        } else {
                            (result as SignInResult.Error).message
                        }
                    )
                )
            }
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
    
    companion object {
        fun schedule(context: Context) {
            // 每天早上8点签到
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            
            val initialDelay = if (calendar.timeInMillis > System.currentTimeMillis()) {
                calendar.timeInMillis - System.currentTimeMillis()
            } else {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                calendar.timeInMillis - System.currentTimeMillis()
            }
            
            val signInRequest = PeriodicWorkRequestBuilder<SignInWorker>(
                1, java.util.concurrent.TimeUnit.DAYS
            )
                .setInitialDelay(initialDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "mt_forum_signin",
                ExistingPeriodicWorkPolicy.KEEP,
                signInRequest
            )
        }
    }
}
```

---

## 六、密码加密存储

### CryptoUtils.kt

```kotlin
package com.example.mtsignin.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

object CryptoUtils {
    private const val KEY_ALIAS = "mt_signin_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
                .build()
            
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            ).apply {
                init(spec)
                generateKey()
            }
        }
        
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
    
    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(data.toByteArray())
        val iv = cipher.iv
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }
    
    fun decrypt(data: String): String {
        val decoded = Base64.decode(data, Base64.NO_WRAP)
        val iv = decoded.copyOfRange(0, 12)
        val encrypted = decoded.copyOfRange(12, decoded.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        
        return String(cipher.doFinal(encrypted))
    }
}
```

---

## 七、构建配置

### build.gradle.kts (App)

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.mtsignin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mtsignin"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-android-compiler:2.48")

    // Room
    implementation("androidx.room:room-runtime:2.6.0")
    implementation("androidx.room:room-ktx:2.6.0")
    kapt("androidx.room:room-compiler:2.6.0")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    kapt("androidx.hilt:hilt-compiler:1.1.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
```

---

## 八、功能特性

| 功能 | 说明 |
|-----|------|
| 多账号管理 | 添加、删除、启用/禁用账号 |
| 一键签到 | 所有账号批量签到 |
| 定时签到 | 每天自动签到 |
| 状态显示 | 签到排名、奖励、历史记录 |
| 密码加密 | 使用Android KeyStore加密存储 |
| 签到提醒 | 通知栏提醒签到结果 |

---

## 九、使用说明

1. 点击右下角 "+" 添加账号
2. 输入MT论坛用户名和密码
3. 点击账号右侧签到按钮单独签到
4. 或点击顶部"一键签到全部"批量签到
5. 可开启账号右侧开关，每天自动签到