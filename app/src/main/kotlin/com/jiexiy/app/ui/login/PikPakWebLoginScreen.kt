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

package com.jiexiy.app.ui.login

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jiexiy.app.data.network.PikPakWebCredential
import com.jiexiy.app.data.network.XunleiWebCredential
import com.jiexiy.app.ui.SnackbarController
import com.jiexiy.app.ui.components.jexiyWavyLoading
import com.jiexiy.app.ui.rememberGlobalSnackbarHostState
import com.jiexiy.app.ui.viewmodel.PikPakAccountViewModel
import com.jiexiy.app.util.DiagnosticLog
import kotlinx.coroutines.launch

/**
 * PikPak 网页登录页：在应用内打开 mypikpak.com 的官网登录页，由网页自己完成登录
 * （账号密码 / 短信验证码 / 滑块验证 / 微信 / 手机号 都由它处理）。
 *
 * 登录完成后网页把令牌写进当前域 localStorage（`credentials_YUMx5nI8ZU8Ap8pm`），本页轮询读取、
 * 过一次真实接口校验后落库，用户不需要额外操作；读取失败时还能手动粘贴兜底。
 *
 * 网页版 client_id `YUMx5nI8ZU8Ap8pm` 与移动端 `YNxT9w7GMdWvEOKa` 是两套独立体系。参考实现
 * wenxi-main/lib/domain/pikpak_web_login.dart。
 *
 * 为什么值得单独做一条网页登录：PikPak 的账号密码 / 短信接口在命中风控时会返回一个**必须由网页完成的
 * 人机验证**，纯 API 客户端解不了；网页登录让浏览器跑完整个验证流程，再回读它写好的令牌，能绕开这条拦路。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PikPakWebLoginScreen(
    viewModel: PikPakAccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    // 手动粘贴凭据弹窗状态
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteInput by remember { mutableStateOf("") }
    var isSavingManual by remember { mutableStateOf(false) }

    // 登录教程弹窗：进入页面即展示一次
    var showTutorial by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showTutorial = true }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true   // 令牌存在 localStorage，必须开启
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 桌面 UA：令牌只在桌面版页面写入，移动版页面是另一套
            // 同步 client hints（Sec-CH-UA 系），否则服务端按移动端判定，页面会退化成移动版落地页
            settings.userAgentString = PikPakWebCredential.DESKTOP_UA
            XunleiWebCredential.applyDesktopClientHints(settings)
            settings.suppressRequestedWithHeader()
            webViewClient = object : DiagnosticWebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    DiagnosticLog.webview("page_started", url)
                    isLoading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    DiagnosticLog.webview("page_finished", url)
                    isLoading = false
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // 登录流程可能经 user.mypikpak.com 与第三方滑块验证；统一放行白名单域，
                    // 出了这个域（含 http 明文页、自定义 scheme）直接拦掉
                    val url = request?.url?.toString().orEmpty()
                    return !PikPakWebCredential.isTrustedUrl(url)
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(PikPakWebCredential.LOGIN_URL)
        }
    }

    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    BackHandler(enabled = !isSaving && !isSavingManual) { onBack() }

    // 自动登录检测：网页把令牌写进 localStorage 就自动校验并落库，无需手动点「保存」
    rememberWebLoginAutoDetect(
        sampleCredential = { webView.evaluateJsEncoded(PikPakWebCredential.READ_SCRIPT) },
        isPlausible = { PikPakWebCredential.parse(it) != null },
        validateAndSave = { viewModel.saveWebCredential(it) },
        isPaused = { isSaving || isSavingManual || showPasteDialog },
        onInFlightChange = { isSaving = it },
        onAutoSaved = onSaved
    )

    val snackbarHostState = rememberGlobalSnackbarHostState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("PikPak 网页登录", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { if (!isSaving && !isSavingManual) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (!isSaving && !isSavingManual) webView.reload() },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { if (!isSaving && !isSavingManual) showPasteDialog = true },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        Icon(
                            Icons.Outlined.ContentPaste,
                            contentDescription = "手动粘贴登录凭据",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                try {
                                    // 从网页 localStorage 提取令牌并校验落库
                                    val raw = webView.evaluateJsEncoded(PikPakWebCredential.READ_SCRIPT)
                                    val saved = raw.isNotBlank() && viewModel.saveWebCredential(raw)
                                    if (saved) {
                                        SnackbarController.show("登录成功")
                                        onSaved()
                                    } else {
                                        SnackbarController.show("未检测到登录态，请先在网页中完成登录")
                                    }
                                } finally {
                                    // 必须 finally：抛异常时「保存」按钮要能恢复可点，否则这个页面就废了
                                    isSaving = false
                                }
                            }
                        },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (isLoading) {
                jexiyWavyLoading(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (showTutorial) {
        AlertDialog(
            onDismissRequest = { showTutorial = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("网页登录说明") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "1. 在下方网页中登录 PikPak 账号（账号密码 / 短信验证码 / 微信扫码都行）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. 若出现滑块验证或图形验证，在网页里完成即可",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. 登录完成后会自动识别并登录，通常无需手动操作",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. 若没自动登录，可点右上角「保存」手动提取一次",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "5. 仍不行就点「粘贴」图标，把网页登录后的令牌 JSON 贴进来",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "6. 网页登录的令牌可自动续期；失效后需重新登录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTutorial = false }) { Text("知道了") }
            }
        )
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingManual) showPasteDialog = false },
            title = { Text("手动粘贴登录凭据") },
            text = {
                Column {
                    Text(
                        text = "在电脑浏览器登录 mypikpak.com 后，从开发者工具复制 localStorage 中 " +
                            "${PikPakWebCredential.STORAGE_KEY} 的值（整段 JSON 即可）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pasteInput,
                        onValueChange = { pasteInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴令牌 JSON…") },
                        minLines = 4,
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isSavingManual = true
                            try {
                                val saved = viewModel.saveWebCredential(pasteInput.trim())
                                if (saved) {
                                    SnackbarController.show("登录成功")
                                    showPasteDialog = false
                                    onSaved()
                                } else {
                                    SnackbarController.show("凭据无效或已过期，请确认复制的是完整内容")
                                }
                            } finally {
                                isSavingManual = false
                            }
                        }
                    },
                    enabled = pasteInput.isNotBlank() && !isSavingManual
                ) {
                    if (isSavingManual) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("保存")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!isSavingManual) showPasteDialog = false },
                    enabled = !isSavingManual
                ) { Text("取消") }
            }
        )
    }
}
