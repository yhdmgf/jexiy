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
import com.jiexiy.app.data.network.AliyunWebCredential
import com.jiexiy.app.data.network.XunleiWebCredential
import com.jiexiy.app.ui.SnackbarController
import com.jiexiy.app.ui.components.jexiyWavyLoading
import com.jiexiy.app.ui.rememberGlobalSnackbarHostState
import com.jiexiy.app.ui.viewmodel.AliyunAccountViewModel
import com.jiexiy.app.util.DiagnosticLog
import kotlinx.coroutines.launch

/**
 * 阿里云盘网页登录页：在应用内打开 www.alipan.com 的登录页，由网页自己完成登录
 * （账号密码 / 短信验证码 / 滑块验证 / 微信扫码都由它处理）。
 *
 * 登录完成后网页把令牌写进 localStorage 的 `token` 键（JSON，含 access_token / refresh_token），
 * 本页轮询读取、过真实接口校验后落库，用户不需要额外操作；读取失败时还能手动粘贴兜底。
 *
 * 阿里云盘只认 refresh_token 换 access_token（access_token 约 15 分钟过期），
 * 因此持久化必须以 refresh_token 为准。参考 wenxi `domain/aliyun.dart`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliyunWebLoginScreen(
    viewModel: AliyunAccountViewModel,
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
            // 桌面 UA：令牌只在桌面版页面写入
            settings.userAgentString = AliyunWebCredential.DESKTOP_UA
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
                    // 登录流程可能经 auth.aliyundrive.com 与第三方验证；统一放行白名单域，
                    // 出了这个域（含 http 明文页、自定义 scheme）直接拦掉
                    val url = request?.url?.toString().orEmpty()
                    return !AliyunWebCredential.isTrustedUrl(url)
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(AliyunWebCredential.LOGIN_URL)
        }
    }

    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    BackHandler(enabled = !isSaving && !isSavingManual) { onBack() }

    // 自动登录检测：网页把令牌写进 localStorage 就自动校验并落库，无需手动点「保存」
    rememberWebLoginAutoDetect(
        sampleCredential = { webView.evaluateJsEncoded(AliyunWebCredential.READ_SCRIPT) },
        isPlausible = { AliyunWebCredential.parse(it) != null },
        validateAndSave = { viewModel.saveWebCredential(it).first },
        isPaused = { isSaving || isSavingManual || showPasteDialog },
        onInFlightChange = { isSaving = it },
        onAutoSaved = onSaved
    )

    val snackbarHostState = rememberGlobalSnackbarHostState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("阿里云盘网页登录", style = MaterialTheme.typography.titleLarge) },
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
                                    val raw = webView.evaluateJsEncoded(AliyunWebCredential.READ_SCRIPT)
                                    val (ok, msg) = viewModel.saveWebCredential(raw)
                                    if (ok) {
                                        SnackbarController.show("登录成功")
                                        onSaved()
                                    } else {
                                        SnackbarController.show("未检测到登录态，请先在网页中完成登录")
                                    }
                                } finally {
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
                        text = "1. 在下方网页中登录阿里云盘账号（账号密码 / 短信验证码 / 微信扫码都行）",
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
                        text = "6. 登录后的 refresh_token 会自动续期；失效后需重新登录",
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
                        text = "在电脑浏览器登录 www.alipan.com 后，从开发者工具复制 localStorage 中 " +
                            "${AliyunWebCredential.STORAGE_KEY} 的值（整段 JSON 即可）",
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
                                val (ok, msg) = viewModel.saveWebCredential(pasteInput.trim())
                                if (ok) {
                                    SnackbarController.show("登录成功")
                                    showPasteDialog = false
                                    onSaved()
                                } else {
                                    SnackbarController.show(msg)
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
