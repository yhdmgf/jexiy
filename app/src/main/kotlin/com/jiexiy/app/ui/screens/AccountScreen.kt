/*
 * jexiy (jexiy) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 jexiy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.jiexiy.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jiexiy.app.data.account.AccountApi
import com.jiexiy.app.data.account.AccountStore
import com.jiexiy.app.data.account.AccountSyncManager
import com.jiexiy.app.data.backup.AuthBackupManager
import com.jiexiy.app.ui.SnackbarController
import kotlinx.coroutines.launch

/**
 * 账号页（设置 → 账号）：登录 / 注册两个 Tab，以及登录后的账号信息与退出入口。
 *
 * 与 [LinkOpenScreen] 等叠加页一样由设置页那一行做容器变换长出来，本页不加自己的入场动画。
 *
 * 登录态完全由后端 Cookie 决定（[AccountStore] 只负责持久化），所以进页面时先 `check` 一次：
 * Cookie 已失效（用户手动退出）就清掉本地，网络失败则保留 —— 不能把「没网」当成「已退出」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    store: AccountStore,
    backupManager: AuthBackupManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session by store.session.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val syncManager = remember(backupManager) { AccountSyncManager(backupManager) }

    var tab by rememberSaveable { mutableStateOf(0) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var password2 by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 同步不需要用户手动触发：登录 / 注册成功后自动跑一次（拉云端独有 + 推本地独有），
    // 已登录时进页面也自动跟云端对一次。notify=false 时静默，避免每次进页都弹提示。
    val runSync: (Boolean) -> Unit = { notify ->
        val cookie = store.session.value?.cookie
        if (cookie != null) {
            scope.launch {
                when (val result = syncManager.sync(cookie, context)) {
                    is AccountApi.Result.Success -> if (notify) {
                        SnackbarController.show("已同步 ${result.data.applied} 个平台")
                    }
                    is AccountApi.Result.Failure -> if (notify) {
                        SnackbarController.show("同步失败：${result.message}")
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val current = store.session.value ?: return@LaunchedEffect
        when (val result = AccountApi.check(current.cookie)) {
            is AccountApi.Result.Success -> {
                val checked = result.data
                if (checked == null) {
                    store.clear()
                } else {
                    store.save(checked)
                    runSync(false)
                }
            }
            // 网络失败：保留本地登录态，由页面上的操作给出真实错误
            is AccountApi.Result.Failure -> Unit
        }
    }

    val submit: () -> Unit = {
        if (!busy) {
            scope.launch {
                busy = true
                error = null
                val cookie = store.session.value?.cookie
                val result = if (tab == 0) {
                    AccountApi.login(email.trim(), password, cookie)
                } else {
                    AccountApi.register(email.trim(), password, password2, cookie)
                }
                busy = false
                when (result) {
                    is AccountApi.Result.Success -> {
                        store.save(result.data)
                        password = ""
                        password2 = ""
                        SnackbarController.show(if (tab == 0) "登录成功" else "注册成功")
                        // 登录：云端已有的账号拉下来；注册：本地账号推上去。双向合并一次到位
                        runSync(true)
                    }
                    is AccountApi.Result.Failure -> error = result.message
                }
            }
        }
    }

    val logout: () -> Unit = {
        if (!busy) {
            scope.launch {
                busy = true
                // 服务端只是清 Cookie，本地无论如何都要清，失败也不该把用户卡在「已登录」
                AccountApi.logout(store.session.value?.cookie)
                store.clear()
                busy = false
                SnackbarController.show("已退出登录")
            }
        }
    }

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("账号", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            val current = session
            if (current != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "已登录",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = current.email,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = logout,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) { Text("退出登录") }
            } else {
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = {
                            tab = 0
                            error = null
                        },
                        text = { Text("登录") }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = {
                            tab = 1
                            error = null
                        },
                        text = { Text("注册") }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("邮箱") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        supportingText = if (tab == 1) {
                            { Text("8-64 位，需同时包含字母和数字") }
                        } else {
                            null
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                    if (tab == 1) {
                        OutlinedTextField(
                            value = password2,
                            onValueChange = { password2 = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("确认密码") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true
                        )
                    }
                    error?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = submit,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy &&
                            email.isNotBlank() &&
                            password.isNotEmpty() &&
                            (tab == 0 || password2.isNotEmpty())
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(if (tab == 0) "登录" else "注册")
                        }
                    }
                }
            }
        }
    }
}
