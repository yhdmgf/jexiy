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
import android.webkit.CookieManager
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
import androidx.compose.material.icons.outlined.Delete
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
import com.jiexiy.app.data.network.LanzouConstants
import com.jiexiy.app.ui.SnackbarController
import com.jiexiy.app.ui.components.jexiyWavyLoading
import com.jiexiy.app.ui.rememberGlobalSnackbarHostState
import com.jiexiy.app.ui.viewmodel.LanzouAccountViewModel
import com.jiexiy.app.util.DiagnosticLog
import kotlinx.coroutines.launch

/**
 * 蓝奏云网页登录页：在应用内打开 pc.woozooo.com 的官网登录页，用户在浏览器里手动登录。
 *
 * 登录完成后网页把会话写进 Cookie（ylogin + phpdisk_info），本页轮询读取、过一次真实接口
 * （mydisk.php 取 uid/vei）校验后落库，用户不需要额外操作；读取失败时还能手动粘贴兜底。
 *
 * 为什么值得单独做一条网页登录：原生账号密码接口（accounts.php + acw_sc__v2）只在纯密码场景走得通，
 * 遇到滑块 / 短信验证码 / 扫码这些网页侧独有形态就必须放弃；网页登录让浏览器跑完整个流程，
 * 再回读它下发的会话 Cookie，能绕开这条拦路。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanzouWebLoginScreen(
    viewModel: LanzouAccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    // 手动粘贴 Cookie 弹窗状态
    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    var isSavingManual by remember { mutableStateOf(false) }

    // 登录教程弹窗：进入页面即展示一次
    var showTutorial by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showTutorial = true }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NARROW_COLUMNS
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.suppressRequestedWithHeader()
            webViewClient = object : DiagnosticWebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    DiagnosticLog.webview("page_started", url)
                    isLoading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    DiagnosticLog.webview("page_finished", url)
                    isLoading = false
                    // 会话 Cookie（phpdisk_info）要访问过「我的文件」页才齐：登录完成后若还差它，
                    // 主动跳一次 mydisk.php 让服务端补全，而不是等用户手动点进去。
                    // 跳完落在 mydisk.php，本页判定条件不再命中，不会形成循环。
                    if (url.orEmpty().contains("account.php")) {
                        val cookie = LanzouConstants.extractWebCookies { CookieManager.getInstance().getCookie(it) }
                        if (cookie.contains("ylogin") && !LanzouConstants.isValidWebCookie(cookie)) {
                            view?.loadUrl(LanzouConstants.MYDISK_URL)
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // 登录过程可能跳 accounts.woozooo.com / up.woozooo.com，统一放行白名单域；
                    // 出了这个域（含 http 明文页、自定义 scheme）直接拦掉
                    val url = request?.url?.toString().orEmpty()
                    return !LanzouConstants.isTrustedWebUrl(url)
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(LanzouConstants.WEB_LOGIN_URL)
        }
    }

    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    BackHandler(enabled = !isSaving && !isSavingManual) { onBack() }

    // 自动登录检测：CookieManager 里出现完整的登录 Cookie 就自动校验并落库
    rememberWebLoginAutoDetect(
        sampleCredential = {
            LanzouConstants.extractWebCookies { CookieManager.getInstance().getCookie(it) }
        },
        isPlausible = { LanzouConstants.isValidWebCookie(it) },
        validateAndSave = { viewModel.saveCookie(it) },
        isPaused = { isSaving || isSavingManual || showCookieDialog },
        onInFlightChange = { isSaving = it },
        onAutoSaved = onSaved
    )

    val snackbarHostState = rememberGlobalSnackbarHostState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("蓝奏云网页登录", style = MaterialTheme.typography.titleLarge) },
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
                    // 清除 WebView 里的 Cookie（已保存到应用的账号不受影响）
                    IconButton(
                        onClick = {
                            if (!isSaving && !isSavingManual) {
                                CookieManager.getInstance().removeAllCookies {
                                    CookieManager.getInstance().flush()
                                    webView.post {
                                        webView.reload()
                                        SnackbarController.show("已清除 Cookie，请重新登录")
                                    }
                                }
                            }
                        },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "清除 Cookie",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { if (!isSaving && !isSavingManual) showCookieDialog = true },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        Icon(
                            Icons.Outlined.ContentPaste,
                            contentDescription = "手动输入 Cookie",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                try {
                                    val cookie =
                                        LanzouConstants.extractWebCookies { CookieManager.getInstance().getCookie(it) }
                                    val saved = cookie.isNotBlank() && viewModel.saveCookie(cookie)
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
                        text = "1. 在下方网页中输入账号密码登录（滑块 / 短信 / 扫码都能在网页里完成）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. 登录成功后会自动跳到「我的文件」，系统识别到会话即自动保存",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. 若没自动保存，可点右上角「保存」手动提取一次",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. 网页卡住或还停留在别人账号时，点删除图标清除 Cookie 后重新登录",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "5. 仍不行就点「粘贴」图标，把浏览器里的登录 Cookie 贴进来",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "6. 蓝奏会话没有续期机制，Cookie 失效后需重新登录",
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

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingManual) showCookieDialog = false },
            title = { Text("手动输入 Cookie") },
            text = {
                Column {
                    Text(
                        text = "在电脑浏览器登录蓝奏云后，从开发者工具的 Cookie 面板复制整串 " +
                            "（必须包含 ylogin 与 phpdisk_info）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴 Cookie…") },
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
                                val cookie = cookieInput.trim()
                                val saved = LanzouConstants.isValidWebCookie(cookie) &&
                                    viewModel.saveCookie(cookie)
                                if (saved) {
                                    SnackbarController.show("登录成功")
                                    showCookieDialog = false
                                    onSaved()
                                } else {
                                    SnackbarController.show("Cookie 无效或缺少 ylogin / phpdisk_info")
                                }
                            } finally {
                                isSavingManual = false
                            }
                        }
                    },
                    enabled = cookieInput.isNotBlank() && !isSavingManual
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
                    onClick = { if (!isSavingManual) showCookieDialog = false },
                    enabled = !isSavingManual
                ) { Text("取消") }
            }
        )
    }
}
