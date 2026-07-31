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
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mtsignin.data.local.AccountEntity
import com.example.mtsignin.data.model.SignInResult
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val signInState by viewModel.signInState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<AccountEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MT论坛签到助手") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer
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
            // 签到状态卡片
            SignInStatusCard(
                signInState = signInState,
                onSignInAll = { viewModel.signInAll() },
                onRefresh = { viewModel.refresh() }
            )

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
                            "暂无账号",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "点击右下角+添加账号",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(accounts, key = { it.id }) { account ->
                        AccountListItem(
                            account = account,
                            onSignIn = { viewModel.signInOne(account) },
                            onToggleEnabled = { viewModel.toggleAccountEnabled(account) },
                            onDelete = { showDeleteConfirm = account }
                        )
                    }
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

    // 删除确认对话框
    showDeleteConfirm?.let { account ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除账号 ${account.username} 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAccount(account)
                        showDeleteConfirm = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun SignInStatusCard(
    signInState: SignInUiState,
    onSignInAll: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                signInState.isSigningIn -> MaterialTheme.colorScheme.secondaryContainer
                signInState.error != null -> MaterialTheme.colorScheme.errorContainer
                signInState.successCount > 0 -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "签到状态",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (signInState.isSigningIn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "正在签到...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (signInState.error != null) {
                        Text(
                            signInState.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (signInState.successCount > 0) {
                        Text(
                            "成功: ${signInState.successCount}, 失败: ${signInState.failCount}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "点击下方按钮开始签到",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    Button(
                        onClick = onSignInAll,
                        enabled = !signInState.isSigningIn
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("全部签到")
                    }
                }
            }

            // 签到进度
            if (signInState.isSigningIn && signInState.totalCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = signInState.currentProgress.toFloat() / signInState.totalCount,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

data class SignInUiState(
    val isSigningIn: Boolean = false,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val error: String? = null,
    val currentProgress: Int = 0,
    val totalCount: Int = 0
)