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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jiexiy.app.data.account.AccountApi
import com.jiexiy.app.data.account.AccountStore
import com.jiexiy.app.data.account.AccountSyncManager
import com.jiexiy.app.data.backup.AuthBackupManager
import com.jiexiy.app.ui.SnackbarController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 账号页（设置 → 账号）：登录 / 注册两个 Tab，以及登录后的账号信息与退出入口。
 *
 * 与 [LinkOpenScreen] 等叠加页一样由设置页那一行做容器变换长出来，本页不加自己的入场动画。
 *
 * 登录态完全由后端 Cookie 决定（[AccountStore] 只负责持久化），所以进页面时先 `check` 一次：
 * Cookie 已失效（用户手动退出）就清掉本地，网络失败则保留 —— 不能把「没网」当成「已退出」。
 *
 * 验证码是服务端一次性的：提交过 / 发过邮箱码之后都必须换一张，
 * 否则下一次提交一定报「图形验证码错误」。收到该文案时自动刷新（见 [AccountApi.isCaptchaRefresh]）。
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
    var captchaText by rememberSaveable { mutableStateOf("") }
    var mailCode by rememberSaveable { mutableStateOf("") }
    var captchaImage by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var captchaLoading by remember { mutableStateOf(false) }
    var mailSending by remember { mutableStateOf(false) }
    var mailCooldown by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // 拉一张新的图形验证码。失败不致命（不覆盖旧图），只在 error 里给出原因
    val loadCaptcha: () -> Unit = {
        if (!captchaLoading) {
            scope.launch {
                captchaLoading = true
                when (val result = AccountApi.fetchCaptcha(context)) {
                    is AccountApi.Result.Success -> {
                        captchaImage = BitmapFactory.decodeByteArray(result.data, 0, result.data.size)
                    }
                    is AccountApi.Result.Failure -> error = "验证码获取失败：${result.message}"
                }
                captchaLoading = false
            }
        }
    }

    // 验证码 60 秒发送冷却倒计时（服务端成功失败都上锁，前端按钮同步禁用）
    LaunchedEffect(mailCooldown) {
        while (mailCooldown > 0) {
            delay(1_000L)
            mailCooldown--
        }
    }

    // 进页面立即拉一张图
    LaunchedEffect(Unit) { loadCaptcha() }

    // 切 Tab 时清掉错误提示
    val switchTab: (Int) -> Unit = { index ->
        tab = index
        error = null
    }

    val sendMailCode: () -> Unit = {
        if (!(mailSending || mailCooldown > 0 || email.isBlank() || captchaText.isBlank())) {
            scope.launch {
                mailSending = true
                error = null
                when (val result = AccountApi.sendMailCode(context, captchaText.trim(), email.trim())) {
                    is AccountApi.Result.Success -> {
                        mailCooldown = MailCodeCooldownSeconds
                        mailCode = ""
                        // 图形验证码已被这一次提交消费，立刻换一张
                        loadCaptcha()
                        SnackbarController.show("邮箱验证码已发送，10 分钟内有效")
                    }
                    is AccountApi.Result.Failure -> {
                        error = result.message
                        // 图形验证码错了：自动换一张，用户只需重填验证码文本
                        if (AccountApi.isCaptchaRefresh(result.message)) loadCaptcha()
                    }
                }
                mailSending = false
            }
        }
    }

    // 同步不需要用户手动触发：登录 / 注册成功后自动跑一次（拉云端独有 + 推本地独有），
    // 已登录时进页面也自动跟云端对一次。notify=false 时静默，避免每次进页都弹提示。
    val runSync: (Boolean) -> Unit = { notify ->
        if (store.session.value != null) {
            scope.launch {
                when (val result = syncManager.sync(context)) {
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
        when (val result = AccountApi.check(context)) {
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
                // 注册前端限制：仅 QQ 邮箱（数字@qq.com）+ 密码须同时含字母和数字
                if (tab == 1) {
                    if (!Regex("""^\d+@qq\.com$""", RegexOption.IGNORE_CASE).matches(email.trim())) {
                        error = "仅支持 QQ 邮箱（数字@qq.com）"
                        busy = false
                        return@launch
                    }
                    if (!(password.any { it.isLetter() } && password.any { it.isDigit() })) {
                        error = "密码必须同时包含字母和数字"
                        busy = false
                        return@launch
                    }
                }
                val result = if (tab == 0) {
                    AccountApi.login(context, email.trim(), password, captchaText.trim())
                } else {
                    AccountApi.register(
                        context,
                        email.trim(),
                        password,
                        password2,
                        mailCode.trim(),
                        captchaText.trim()
                    )
                }
                busy = false
                when (result) {
                    is AccountApi.Result.Success -> {
                        store.save(result.data)
                        password = ""
                        password2 = ""
                        mailCode = ""
                        SnackbarController.show(if (tab == 0) "登录成功" else "注册成功")
                        // 登录：云端已有的账号拉下来；注册：本地账号推上去。双向合并一次到位
                        runSync(true)
                    }
                    is AccountApi.Result.Failure -> {
                        error = result.message
                        // 图形验证码错误：服务端要求刷新图片重试
                        if (AccountApi.isCaptchaRefresh(result.message)) loadCaptcha()
                    }
                }
            }
        }
    }

    val logout: () -> Unit = {
        if (!busy) {
            scope.launch {
                busy = true
                // 服务端只是清 Cookie，本地无论如何都要清，失败也不该把用户卡在「已登录」
                AccountApi.logout(context)
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
                    onClick = { runSync(true) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) { Text("双向同步") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = logout,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) { Text("退出登录") }
            } else {
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { switchTab(0) },
                        text = { Text("登录") }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { switchTab(1) },
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
                        supportingText = if (tab == 1) {
                            { Text("仅支持 QQ 邮箱，如 12345@qq.com") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        supportingText = if (tab == 1) {
                            { Text("至少 6 位，须含字母和数字") }
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = mailCode,
                                onValueChange = { mailCode = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("邮箱验证码") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = sendMailCode,
                                modifier = Modifier.height(56.dp),
                                enabled = !mailSending && mailCooldown == 0 &&
                                    email.isNotBlank() && captchaText.isNotBlank()
                            ) {
                                when {
                                    mailSending -> CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    mailCooldown > 0 -> Text("${mailCooldown}s 后重发")
                                    else -> Text("发送验证码")
                                }
                            }
                        }
                    }
                    // 图形验证码：左边输入框 + 右边可点击的图片（点击即刷新）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = captchaText,
                            onValueChange = { captchaText = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("图形验证码") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(CaptchaImageWidth, CaptchaImageHeight)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { loadCaptcha() }
                        ) {
                            val bitmap = captchaImage
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "图形验证码，点击刷新",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (captchaLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "点击\n刷新",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
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
                            captchaText.isNotBlank() &&
                            (tab == 0 || (password2.isNotEmpty() && mailCode.isNotBlank()))
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

private const val MailCodeCooldownSeconds = 60

private val CaptchaImageWidth: Dp = 108.dp
private val CaptchaImageHeight: Dp = 38.dp
