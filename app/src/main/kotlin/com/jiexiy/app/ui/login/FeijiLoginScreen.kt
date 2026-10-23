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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jiexiy.app.data.network.FeijiApi
import com.jiexiy.app.data.parse.FeijiAccountStore
import com.jiexiy.app.ui.SnackbarController
import com.jiexiy.app.ui.components.jexiyWavyLoading
import com.jiexiy.app.ui.rememberGlobalSnackbarHostState
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 小飞机网盘登录页：网页版登录。
 *
 * 登录态读取与 wenxi-main 一致：优先扫 localStorage 的 `disk-pc-vuex` / `vuex` / `disk-vuex`
 * 里的 `common.appToken` 与 `common.uuid`，其次读 Cookie。
 *
 * ★ 自动检测不再「看到 appToken 就保存」：
 *   1. 页面加载后先记下**进入登录页时已有的凭证**作为基线，基线不变不会自动保存
 *      （退出登录后 WebView 里残留的旧 Cookie 不会再被立刻登回去）；
 *   2. 只有凭证变化后，才调用 account/map + info/map 做真实网络校验，校验通过才落库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeijiLoginScreen(
    newAccount: Boolean = false,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    var isSavingManual by remember { mutableStateOf(false) }
    // 进入登录页时页面里已经存在的凭证：它不算「本次登录」，不触发自动保存
    var baseline by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = DESKTOP_UA
            settings.suppressRequestedWithHeader()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    isLoading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
                    if (baseline == null) {
                        scope.launch {
                            val existing = readWebCredentials(view ?: return@launch)
                            if (existing != null && baseline == null) baseline = existing.encode()
                        }
                    }
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(LOGIN_URL)
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    suspend fun saveCredential(credential: WebCredential): Boolean {
        val uuid = credential.uuid.ifBlank { FeijiApi.getUuid() }
        val info = FeijiApi.account(credential.token, uuid)
        FeijiAccountStore.save(context, credential.token, uuid, info, newAccount)
        return true
    }

    // 自动登录检测：只接受「相对基线发生变化」的凭证，并且必须通过真实接口校验
    rememberWebLoginAutoDetect(
        sampleCredential = {
            val current = readWebCredentials(webView) ?: return@rememberWebLoginAutoDetect null
            val encoded = current.encode()
            if (baseline == null) {
                baseline = encoded
                null
            } else if (encoded == baseline) {
                null
            } else {
                encoded
            }
        },
        isPlausible = { decodeCredential(it) != null },
        validateAndSave = { encoded ->
            val credential = decodeCredential(encoded) ?: return@rememberWebLoginAutoDetect false
            saveCredential(credential)
        },
        isPaused = { isSaving || isSavingManual || showCookieDialog },
        onInFlightChange = { isSaving = it },
        onAutoSaved = onSaved
    )

    BackHandler(enabled = !isSaving && !isSavingManual) { onBack() }

    val snackbarHostState = rememberGlobalSnackbarHostState()

    fun manualSave(credential: WebCredential?) {
        if (credential == null) {
            SnackbarController.show("未检测到 appToken，请先完成登录或手动粘贴凭证")
            return
        }
        scope.launch {
            isSaving = true
            runCatching { saveCredential(credential) }
                .onSuccess {
                    SnackbarController.show("登录成功")
                    onSaved()
                }
                .onFailure {
                    SnackbarController.show(it.message ?: "小飞机登录校验失败，请重新登录")
                }
            isSaving = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (newAccount) "添加小飞机账号" else "小飞机网盘登录",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { showDebug = true })
                        }
                    )
                },
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
                                val credential = readWebCredentials(webView)
                                isSaving = false
                                if (credential != null) {
                                    manualSave(credential)
                                } else {
                                    val keys = collectCookies(webView.url).split(';')
                                        .map { it.substringBefore('=').trim() }
                                        .filter { it.isNotEmpty() }
                                        .take(12)
                                        .joinToString(",")
                                    SnackbarController.show(
                                        "未检测到有效 appToken（页面 ${webView.url ?: "?"}；Cookie 键：${keys.ifBlank { "空" }}）"
                                    )
                                }
                            }
                        },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("保存")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
            if (isLoading) {
                jexiyWavyLoading(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (showDebug) {
        FeijiDebugDialog(webView = webView, onDismiss = { showDebug = false })
    }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingManual) showCookieDialog = false },
            title = { Text("手动输入小飞机凭证") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "可以粘贴 appToken、含 appToken 的 Cookie，或网页登录态 JSON（含 appToken / uuid）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("appToken / Cookie") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        singleLine = false,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = cookieInput.isNotBlank() && !isSavingManual,
                    onClick = {
                        val credential = parseCredential(cookieInput)
                        if (credential == null) {
                            SnackbarController.show("没有识别到 appToken")
                            return@TextButton
                        }
                        isSavingManual = true
                        scope.launch {
                            runCatching { saveCredential(credential) }
                                .onSuccess {
                                    showCookieDialog = false
                                    SnackbarController.show("登录成功")
                                    onSaved()
                                }
                                .onFailure {
                                    SnackbarController.show(it.message ?: "小飞机登录校验失败")
                                }
                            isSavingManual = false
                        }
                    }
                ) {
                    if (isSavingManual) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("保���")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCookieDialog = false }, enabled = !isSavingManual) {
                    Text("取消")
                }
            }
        )
    }
}

/** 调试弹窗：列出实际读到的 Cookie 键、document.cookie 键和 localStorage 键 */
@Composable
private fun FeijiDebugDialog(webView: WebView, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf("读取中…") }
    LaunchedEffect(Unit) {
        val url = webView.url.orEmpty()
        val cookie = collectCookies(url)
        val cookieKeys = cookie.split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
        val raw = evalJs(
            webView,
            "(function(){try{return JSON.stringify({c:document.cookie||'',ls:Object.keys(localStorage)})}catch(e){return ''}})()"
        )
        val inner = runCatching {
            JSONTokener(raw ?: "").nextValue() as String
        }.getOrNull().orEmpty()
        val obj = runCatching { JSONObject(inner) }.getOrNull()
        val docKeys = obj?.optString("c").orEmpty().split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
        val lsKeys = obj?.optJSONArray("ls")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()
        val credential = readWebCredentials(webView)
        info = buildString {
            appendLine("URL: $url")
            appendLine("CookieManager 键: ${cookieKeys.joinToString(",").ifBlank { "空" }}")
            appendLine("document.cookie 键: ${docKeys.joinToString(",").ifBlank { "空" }}")
            appendLine("localStorage 键: ${lsKeys.joinToString(",").ifBlank { "空" }}")
            appendLine("读取到 appToken: ${credential != null}")
            append("读取到 uuid: ${credential?.uuid.orEmpty().ifBlank { "空" }}")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小飞机调试信息") },
        text = {
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("feiji_debug", info))
                SnackbarController.show("调试信息已复制")
            }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 网页登录凭证：appToken + 设备 uuid（uuid 可能为空，保存时再补） */
private data class WebCredential(val token: String, val uuid: String) {
    fun encode(): String = "$token|$uuid"
}

private fun decodeCredential(value: String): WebCredential? {
    val token = value.substringBefore('|').trim()
    if (token.isBlank()) return null
    return WebCredential(token, value.substringAfter('|', "").trim())
}

/** 异步执行 WebView JS，返回原始结果（字符串是 JSON 编码过的） */
private suspend fun evalJs(webView: WebView, script: String): String? =
    suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { value -> cont.resume(value) }
    }

/**
 * 与 wenxi-main 的 readStorageScript 一致：
 * 依次读 localStorage 的 disk-pc-vuex / vuex / disk-vuex，取 common.appToken / common.uuid，
 * 再回退到 document.cookie 里的 appToken。
 */
private suspend fun readWebCredentials(webView: WebView): WebCredential? {
    val raw = evalJs(webView, READ_CREDENTIAL_JS)
    val inner = runCatching {
        JSONTokener(raw ?: "").nextValue() as String
    }.getOrNull().orEmpty()
    if (inner.isNotBlank()) {
        val obj = runCatching { JSONObject(inner) }.getOrNull()
        val token = obj?.optString("appToken").orEmpty()
        val uuid = obj?.optString("uuid").orEmpty()
        if (token.isNotBlank()) return WebCredential(token, uuid)
    }
    // localStorage 没读到（部分页面跨域）时，再扫 CookieManager
    val cookie = collectCookies(webView.url)
    val token = extractAppToken(cookie)
    if (token.isNotBlank()) {
        return WebCredential(token, extractUuid(cookie))
    }
    return null
}

/** 从 Cookie / JSON / 纯 token 里提取非空 appToken */
private fun extractAppToken(credential: String): String {
    val c = credential.trim()
    if (c.isEmpty()) return ""
    Regex("""appToken=([^;]+)""", RegexOption.IGNORE_CASE).find(c)?.let { return it.groupValues[1].trim() }
    if (c.startsWith("{")) {
        runCatching {
            val o = JSONObject(c)
            val common = o.optJSONObject("common")
            val t = common?.optString("appToken").orEmpty().ifBlank {
                o.optString("appToken").ifBlank {
                    o.optString("token").ifBlank { o.optString("accessToken") }
                }
            }
            if (t.isNotBlank()) return t
        }
    }
    Regex(""""appToken"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(c)?.let { return it.groupValues[1] }
    if (!c.contains("=") && !c.startsWith("{")) return c
    return ""
}

private fun extractUuid(credential: String): String =
    Regex("""uuid["']?\s*[:=]\s*["']?([A-Za-z0-9_-]{8,128})""", RegexOption.IGNORE_CASE)
        .find(credential)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()

private fun parseCredential(input: String): WebCredential? {
    val token = extractAppToken(input)
    if (token.isBlank()) return null
    return WebCredential(token, extractUuid(input))
}

/** 汇总小飞机各域名的 Cookie（登录态通常分散在主站/分享站） */
private fun collectCookies(currentUrl: String? = null): String {
    val manager = CookieManager.getInstance()
    manager.flush()
    val domains = buildList {
        if (!currentUrl.isNullOrBlank()) add(currentUrl)
        add("https://www.feijipan.com")
        add("https://feijipan.com")
        add("https://share.feijipan.com")
        add("https://api.feijipan.com")
    }
    val seen = LinkedHashMap<String, String>()
    for (domain in domains) {
        val raw = manager.getCookie(domain).orEmpty()
        for (part in raw.split(';')) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val key = item.substringBefore('=')
            if (key.isNotEmpty()) seen[key] = item
        }
    }
    return seen.values.joinToString("; ")
}

private const val LOGIN_URL = "https://www.feijipan.com/console/files/0"

/** 与 wenxi-main 一致：桌面 UA */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

private const val READ_CREDENTIAL_JS = """(() => {
  let common = {};
  for (const key of ['disk-pc-vuex', 'vuex', 'disk-vuex']) {
    try {
      const stored = JSON.parse(localStorage.getItem(key) || '{}').common || {};
      if (stored.appToken) { common = stored; break; }
      if (stored.uuid) common = stored;
    } catch (_) {}
  }
  try {
    const raw = document.cookie.split(';').map(s => s.trim()).find(s => s.startsWith('appToken='));
    const appToken = common.appToken || (raw && decodeURIComponent(raw.slice(9))) || '';
    return appToken || common.uuid
      ? JSON.stringify({appToken, uuid: common.uuid || ''})
      : '';
  } catch (_) {
    return common.appToken || common.uuid
      ? JSON.stringify({appToken: common.appToken || '', uuid: common.uuid || ''})
      : '';
  }
})()"""
