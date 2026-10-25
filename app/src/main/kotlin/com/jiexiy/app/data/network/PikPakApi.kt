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

package com.jiexiy.app.data.network

import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.QuotaInfo
import com.jiexiy.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.Charsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** PikPak 账号信息（账号密码登录后取回）。 */
data class PikPakAccountInfo(
    val userId: String,
    val nickname: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long
)

/** PikPak 登录凭证丢失（401 unauthenticated / refresh_token 失效）。 */
internal class PikPakLoginExpiredException : Exception("PikPak 登录已过期，请重新登录")

/** PikPak 分享状态（决定 UI 提示文案）。 */
enum class PikPakShareStatus {
    OK,
    PASS_CODE_EMPTY,
    PASS_CODE_ERROR,
    DELETED,
    EXPIRED,
    NOT_FOUND,
    UNKNOWN
}

/**
 * PikPak（mypikpak.com）API 封装。
 *
 * 认证走安卓 App 客户端凭据（[PikPakConstants.CLIENT_ID] / [PikPakConstants.CLIENT_SECRET]），
 * 所有请求必须带 `User-Agent: ANDROID-com.pikcloud.pikpak/1.21.0` 与 `X-Device-Id`；
 * 需要登录态时再加 `Authorization: Bearer <access_token>`。
 *
 * 上传走阿里云 OSS V1 签名（`OSS <AK>:<signature>`）；hash 是「每块 SHA1 → 拼接 20 字节 → 再 SHA1」，
 * **不是**整文件直接 SHA1。
 */
class PikPakApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {

    private val client get() = clientProvider()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val xmlMediaType = "application/xml; charset=utf-8".toMediaType()
    private val octetMediaType = "application/octet-stream".toMediaType()

    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody()

    // ==================== 认证 ====================

    /** 生成新的设备标识（UUID 去横线，32 位小写十六进制）。 */
    fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * 账号密码登录：先取 captcha_token，再用 password grant 换 access_token。
     *
     * 服务端响应字段命名不统一（`access_token`/`accessToken`、`expire_time`/`expires_at`/`expires_in` 都见过），
     * 全部兼容读取，避免某次接口升级就把登录打崩。
     */
    suspend fun login(
        account: String,
        password: String,
        deviceId: String
    ): PikPakAccountInfo = withContext(Dispatchers.IO) {
        val name = account.trim()
        if (name.isEmpty() || password.isEmpty()) throw IllegalStateException("请输入 PikPak 账号和密码")
        if (deviceId.isEmpty()) throw IllegalStateException("PikPak 缺少设备标识")

        // Step 1: captcha init
        val captchaBody = JSONObject()
            .put("client_id", PikPakConstants.CLIENT_ID)
            .put("device_id", deviceId)
            .put("action", "POST:${PikPakConstants.SIGNIN}")
            .put(
                "meta",
                JSONObject().put("username", name)
            )
        val captchaResp = execJson(
            Request.Builder().url(PikPakConstants.CAPTCHA_INIT)
                .headers(authlessHeaders(deviceId))
                .post(captchaBody.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "captcha init"
        )
        val captchaToken = firstNonBlank(
            captchaResp.optString("captcha_token"),
            captchaResp.optString("captchaToken"),
            captchaResp.optJSONObject("data")?.optString("captcha_token").orEmpty()
        )

        // Step 2: signin
        val signinBody = JSONObject()
            .put("client_id", PikPakConstants.CLIENT_ID)
            .put("client_secret", PikPakConstants.CLIENT_SECRET)
            .put("grant_type", "password")
            .put("username", name)
            .put("password", password)
            .put("captcha_token", captchaToken)
        val signinResp = execJson(
            Request.Builder().url(PikPakConstants.SIGNIN)
                .headers(authlessHeaders(deviceId))
                .post(signinBody.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "signin",
            requireSuccess = false
        )
        val accessToken = firstNonBlank(
            signinResp.optString("access_token"),
            signinResp.optString("accessToken"),
            signinResp.optString("access_token") // 兜底
        )
        val refreshToken = firstNonBlank(
            signinResp.optString("refresh_token"),
            signinResp.optString("refreshToken")
        )
        if (accessToken.isEmpty()) {
            // 服务端错误体：优先透出 error_description / error
            val detail = firstNonBlank(
                signinResp.optString("error_description"),
                signinResp.optString("error"),
                signinResp.optString("message")
            )
            throw IllegalStateException(
                if (detail.isEmpty()) "PikPak 未返回登录凭据，请检查账号密码"
                else "PikPak 登录失败：$detail"
            )
        }
        val expiresAtMs = resolveExpiresAtMs(
            expireTime = signinResp.optString("expire_time"),
            expiresAt = signinResp.optString("expires_at"),
            expiresIn = signinResp.optString("expires_in")
        )
        PikPakAccountInfo(
            userId = "",
            nickname = "",
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAtMs
        )
    }

    /** 用 refresh_token 换新 access_token。refresh 本身失效时抛 [PikPakLoginExpiredException]。 */
    suspend fun refreshToken(
        refreshToken: String,
        deviceId: String
    ): PikPakAccountInfo = withContext(Dispatchers.IO) {
        if (refreshToken.isEmpty()) throw PikPakLoginExpiredException()
        val body = JSONObject()
            .put("client_id", PikPakConstants.CLIENT_ID)
            .put("client_secret", PikPakConstants.CLIENT_SECRET)
            .put("grant_type", "refresh_token")
            .put("refresh_token", refreshToken)
        val resp = execJson(
            Request.Builder().url(PikPakConstants.REFRESH_TOKEN)
                .headers(authlessHeaders(deviceId))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "refresh",
            requireSuccess = false
        )
        val accessToken = firstNonBlank(
            resp.optString("access_token"),
            resp.optString("accessToken")
        )
        val newRefresh = firstNonBlank(
            resp.optString("refresh_token"),
            resp.optString("refreshToken"),
            refreshToken // 未换发时沿用旧 refresh
        )
        if (accessToken.isEmpty()) {
            val error = resp.optString("error")
            if (error == "unauthenticated" || error == "invalid_grant" || error == "invalid_refresh_token") {
                throw PikPakLoginExpiredException()
            }
            val detail = firstNonBlank(
                resp.optString("error_description"),
                resp.optString("error"),
                resp.optString("message")
            )
            throw IllegalStateException(
                if (detail.isEmpty()) "PikPak 刷新令牌失败" else "PikPak 刷新令牌失败：$detail"
            )
        }
        PikPakAccountInfo(
            userId = "",
            nickname = "",
            accessToken = accessToken,
            refreshToken = newRefresh,
            expiresAtMs = resolveExpiresAtMs(
                expireTime = resp.optString("expire_time"),
                expiresAt = resp.optString("expires_at"),
                expiresIn = resp.optString("expires_in")
            )
        )
    }

    /** 用户信息（/user/me）：`sub` / `name`。 */
    suspend fun fetchMe(accessToken: String, deviceId: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val json = execJson(
                Request.Builder().url(PikPakConstants.USER_ME)
                    .headers(authHeaders(deviceId, accessToken))
                    .get()
                    .build(),
                phase = "user/me"
            )
            val sub = firstNonBlank(json.optString("sub"), json.optString("user_id"))
            val name = firstNonBlank(json.optString("name"), json.optString("nickname"))
            sub to name
        }

    /** 云端配额（/about）：`quota.usage` / `quota.limit`，单位字节。 */
    suspend fun getQuota(accessToken: String, deviceId: String): QuotaInfo =
        withContext(Dispatchers.IO) {
            val json = execJson(
                Request.Builder().url(PikPakConstants.ABOUT)
                    .headers(authHeaders(deviceId, accessToken))
                    .get()
                    .build(),
                phase = "about"
            )
            val quota = json.optJSONObject("quota") ?: JSONObject()
            QuotaInfo(
                used = quota.optLong("usage", 0L),
                total = quota.optLong("limit", 0L)
            )
        }

    // ==================== 文件管理 ====================

    /** 列出父目录下的所有文件与子目录（自动翻页）。`parentId` 为空表示根目录。 */
    suspend fun listFiles(
        accessToken: String,
        deviceId: String,
        parentId: String,
        withAudit: Boolean = false
    ): List<ShareFile> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ShareFile>()
        var pageToken = ""
        var safety = 0
        while (safety++ < 100) {
            val url = PikPakConstants.FILES.toHttpUrl().newBuilder()
                .apply {
                    if (parentId.isNotEmpty()) addQueryParameter("parent_id", parentId)
                    addQueryParameter("limit", "500")
                    if (pageToken.isNotEmpty()) addQueryParameter("page_token", pageToken)
                    addQueryParameter("thumbnail_size", "SIZE_SMALL")
                    addQueryParameter("with_audit", withAudit.toString())
                    addQueryParameter("filters", "{\"trashed\":{\"eq\":false}}")
                }
                .build()
            val json = execJson(
                Request.Builder().url(url.toString())
                    .headers(authHeaders(deviceId, accessToken))
                    .get()
                    .build(),
                phase = "list"
            )
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val share = parseShareFile(item)
                if (share != null) all.add(share)
            }
            pageToken = json.optString("next_page_token").orEmpty()
            if (pageToken.isEmpty()) break
        }
        all
    }

    /** 文件详情（用于取直链、轮询上传结果）。返回解析好的 JSON，调用方按需读取。 */
    suspend fun fileDetail(
        accessToken: String,
        deviceId: String,
        fileId: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = PikPakConstants.FILES.toHttpUrl().newBuilder()
            .addPathSegment(fileId)
            .addQueryParameter("thumbnail_size", "SIZE_SMALL")
            .build()
        execJson(
            Request.Builder().url(url.toString())
                .headers(authHeaders(deviceId, accessToken))
                .get()
                .build(),
            phase = "file detail"
        )
    }

    /** 取个人文件下载直链。取 `links["application/octet-stream"].url`，为空才回退 `web_content_link`。 */
    suspend fun getDownloadLink(
        accessToken: String,
        deviceId: String,
        fileId: String,
        fileName: String
    ): DownloadLink = withContext(Dispatchers.IO) {
        val json = fileDetail(accessToken, deviceId, fileId)
        val size = json.optLong("size", 0L)
        val links = json.optJSONObject("links") ?: JSONObject()
        val octet = links.optJSONObject("application/octet-stream")
        val directUrl = firstNonBlank(
            octet?.optString("url").orEmpty(),
            links.optString("url"),
            json.optString("web_content_link")
        )
        if (directUrl.isEmpty()) throw IllegalStateException("PikPak 未返回下载直链")
        DownloadLink(
            fid = fileId,
            filename = fileName.ifEmpty { json.optString("name") },
            downloadUrl = directUrl,
            size = size
        )
    }

    /** 新建文件夹，返回新目录 id。 */
    suspend fun createFolder(
        accessToken: String,
        deviceId: String,
        parentId: String,
        name: String
    ): String = withContext(Dispatchers.IO) {
        if (name.isEmpty()) throw IllegalStateException("文件夹名为空")
        val body = JSONObject()
            .put("kind", "drive#folder")
            .put("name", name)
            .apply { if (parentId.isNotEmpty()) put("parent_id", parentId) }
        val json = execJson(
            Request.Builder().url(PikPakConstants.FILES)
                .headers(authHeaders(deviceId, accessToken))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "create folder"
        )
        val fileId = json.optJSONObject("file")?.optString("id").orEmpty()
        if (fileId.isEmpty()) throw IllegalStateException("PikPak 未返回新文件夹信息")
        fileId
    }

    /** 重命名（PATCH /drive/v1/files/{id}）。 */
    suspend fun rename(
        accessToken: String,
        deviceId: String,
        fileId: String,
        newName: String
    ): Unit = withContext(Dispatchers.IO) {
        if (newName.isEmpty()) throw IllegalStateException("新名称为空")
        val url = PikPakConstants.FILES.toHttpUrl().newBuilder()
            .addPathSegment(fileId)
            .build()
        val body = JSONObject().put("name", newName)
        execJson(
            Request.Builder().url(url.toString())
                .headers(authHeaders(deviceId, accessToken))
                .method("PATCH", body.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "rename"
        )
    }

    /** 批量移动（最多 100 条）。 */
    suspend fun batchMove(
        accessToken: String,
        deviceId: String,
        ids: List<String>,
        targetParentId: String
    ): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val body = JSONObject()
            .put("ids", JSONArray(ids.take(100)))
            .put("to", JSONObject().put("parent_id", targetParentId.ifEmpty { "root" }))
        execJson(
            Request.Builder().url(PikPakConstants.FILES_BATCH_MOVE)
                .headers(authHeaders(deviceId, accessToken))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "batch move"
        )
    }

    /** 批量删除（进回收站，最多 100 条）。 */
    suspend fun batchTrash(
        accessToken: String,
        deviceId: String,
        ids: List<String>
    ): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val body = JSONObject().put("ids", JSONArray(ids.take(100)))
        execJson(
            Request.Builder().url(PikPakConstants.FILES_BATCH_TRASH)
                .headers(authHeaders(deviceId, accessToken))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "batch trash"
        )
    }

    /** 创建分享（默认 7 天、公开链接、不需要提取码）。 */
    suspend fun createShare(
        accessToken: String,
        deviceId: String,
        fileIds: List<String>,
        passcode: String = "",
        expirationDays: Int = 7
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("file_ids", JSONArray(fileIds))
            .put("expiration_days", expirationDays)
            .put("share_to", "publiclink")
            .apply {
                if (passcode.isNotBlank()) {
                    put("pass_code", passcode)
                    put("pass_code_option", "REQUIRED")
                } else {
                    put("pass_code_option", "NOT_REQUIRED")
                }
            }
        val json = execJson(
            Request.Builder().url(PikPakConstants.SHARE)
                .headers(authHeaders(deviceId, accessToken))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "create share"
        )
        val url = firstNonBlank(json.optString("share_url"), json.optString("url"))
        if (url.isEmpty()) throw IllegalStateException("PikPak 未返回分享链接")
        url to json.optString("pass_code")
    }

    // ==================== 分享解析 ====================

    /** 分享会话：shareId + passCodeToken（后续 detail / restore 请求都要带）。 */
    data class ShareSession(
        val shareId: String,
        val passCodeToken: String,
        val title: String,
        val status: PikPakShareStatus
    )

    /** 首次打开分享（获取 passCodeToken 并读取根目录）。 */
    suspend fun openShare(
        deviceId: String,
        shareId: String,
        passCode: String = ""
    ): Pair<ShareSession, List<ShareFile>> = withContext(Dispatchers.IO) {
        val url = PikPakConstants.SHARE.toHttpUrl().newBuilder()
            .apply {
                addQueryParameter("share_id", shareId)
                addQueryParameter("limit", "100")
                addQueryParameter("thumbnail_size", "SIZE_SMALL")
                if (passCode.isNotEmpty()) addQueryParameter("pass_code", passCode)
            }
            .build()
        val json = execJson(
            Request.Builder().url(url.toString())
                .headers(authlessHeaders(deviceId))
                .get()
                .build(),
            phase = "share open",
            requireSuccess = false
        )
        val status = parseShareStatus(json.optString("share_status"))
        val session = ShareSession(
            shareId = shareId,
            passCodeToken = firstNonBlank(
                json.optString("pass_code_token"),
                json.optString("passCodeToken")
            ),
            title = firstNonBlank(json.optString("title"), "PikPak 分享"),
            status = status
        )
        val files = json.optJSONArray("files")?.let { list ->
            (0 until list.length()).mapNotNull { parseShareFile(list.optJSONObject(it)) }
        }.orEmpty()
        session to files
    }

    /** 分享子目录 / 翻页列表（需 passCodeToken）。 */
    suspend fun listShare(
        deviceId: String,
        shareId: String,
        passCodeToken: String,
        parentId: String = "",
        pageToken: String = ""
    ): Pair<List<ShareFile>, String> = withContext(Dispatchers.IO) {
        val url = PikPakConstants.SHARE_DETAIL.toHttpUrl().newBuilder()
            .apply {
                addQueryParameter("share_id", shareId)
                addQueryParameter("pass_code_token", passCodeToken)
                addQueryParameter("limit", "100")
                addQueryParameter("thumbnail_size", "SIZE_SMALL")
                if (parentId.isNotEmpty()) addQueryParameter("parent_id", parentId)
                if (pageToken.isNotEmpty()) addQueryParameter("page_token", pageToken)
            }
            .build()
        val json = execJson(
            Request.Builder().url(url.toString())
                .headers(authlessHeaders(deviceId))
                .get()
                .build(),
            phase = "share list"
        )
        val files = json.optJSONArray("files")?.let { list ->
            (0 until list.length()).mapNotNull { parseShareFile(list.optJSONObject(it)) }
        }.orEmpty()
        files to json.optString("next_page_token").orEmpty()
    }

    // ==================== 分享下载（需转存） ====================

    /**
     * 分享文件不能直接下载：先转存到目标目录再走个人文件取链。
     *
     * @param parentId 转存目标目录 id（空 = 我的根目录）
     * @return 转存后的新文件 id
     */
    suspend fun restoreShareFile(
        accessToken: String,
        deviceId: String,
        shareId: String,
        passCodeToken: String,
        fileId: String,
        ancestorIds: List<String>,
        parentId: String = ""
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("share_id", shareId)
            .put("pass_code_token", passCodeToken)
            .put("file_ids", JSONArray(listOf(fileId)))
            .put("ancestor_ids", JSONArray(ancestorIds))
            .put("parent_id", parentId)
            .put("specify_parent_id", true)
        val json = execJson(
            Request.Builder().url(PikPakConstants.SHARE_RESTORE)
                .headers(authHeaders(deviceId, accessToken))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "share restore"
        )
        val taskId = firstNonBlank(
            json.optString("restore_task_id"),
            json.optString("task_id")
        )
        if (taskId.isEmpty()) throw IllegalStateException("PikPak 未返回转存任务")
        // 轮询任务状态
        repeat(PikPakConstants.UPLOAD_POLL_MAX) {
            delay(1000)
            val taskUrl = PikPakConstants.TASKS.toHttpUrl().newBuilder()
                .addPathSegment(taskId)
                .build()
            val taskJson = execJson(
                Request.Builder().url(taskUrl.toString())
                    .headers(authHeaders(deviceId, accessToken))
                    .get()
                    .build(),
                phase = "task status"
            )
            val phase = taskJson.optString("phase")
            when (phase) {
                "PHASE_TYPE_COMPLETE" -> {
                    // 从任务里读新 file id（PikPak 通常返回 results[].file.id）
                    val file = taskJson.optJSONArray("results")
                        ?.optJSONObject(0)
                        ?.optJSONObject("file")
                    val newId = file?.optString("id").orEmpty()
                    if (newId.isEmpty()) throw IllegalStateException("PikPak 转存完成但未返回新文件 id")
                    return@withContext newId
                }
                "PHASE_TYPE_ERROR" -> {
                    val message = taskJson.optString("message")
                    throw IllegalStateException("PikPak 转存失败${if (message.isEmpty()) "" else "：$message"}")
                }
                "PHASE_TYPE_CANCELED" -> {
                    throw IllegalStateException("PikPak 转存任务被取消")
                }
                else -> Unit
            }
        }
        throw IllegalStateException("PikPak 转存仍在处理，请稍后手动刷新目录确认")
    }

    // ==================== 上传 ====================

    /** 上传结果。 */
    data class UploadResult(
        val fileId: String,
        val size: Long
    )

    /**
     * 上传文件到 PikPak（两步：创建上传槽 → 阿里云 OSS V1 分片直传）。
     *
     * Hash 算法：每块先算 SHA1 得到 20 字节，把所有块的 20 字节连起来再 SHA1 一次 → 整文件 hash
     * （大写十六进制 40 字符）。**不是**直接算整文件 SHA1。
     *
     * @param open 打开文件的 [start, end) 区间输入流（分片会多次调用）
     * @param onProgress 已传输字节数（含算 hash 阶段）
     */
    suspend fun uploadFile(
        accessToken: String,
        deviceId: String,
        parentId: String,
        name: String,
        size: Long,
        open: (Long, Long) -> InputStream,
        onProgress: (Long) -> Unit = {}
    ): UploadResult = withContext(Dispatchers.IO) {
        if (name.isEmpty()) throw IllegalStateException("文件名为空")
        if (size <= 0L) throw IllegalStateException("文件大小异常")
        if (accessToken.isEmpty()) throw PikPakLoginExpiredException()

        val partSize = pickPartSize(size)
        val hash = computePikPakHash(open, size, partSize) { read -> onProgress(read) }

        // Step 1: 创建上传槽
        val createBody = JSONObject()
            .put("kind", "drive#file")
            .put("name", name)
            .put("size", size.toString())
            .put("hash", hash)
            .put("upload_type", "UPLOAD_TYPE_RESUMABLE")
            .apply { if (parentId.isNotEmpty()) put("parent_id", parentId) }
            .put("body", JSONObject().put("duration", "").put("width", "").put("height", ""))
            .put("objProvider", JSONObject().put("provider", "UPLOAD_TYPE_UNKNOWN"))
        val createResp = execJson(
            Request.Builder().url(PikPakConstants.FILES)
                .headers(authHeaders(deviceId, accessToken))
                .post(createBody.toString().toRequestBody(jsonMediaType))
                .build(),
            phase = "upload create"
        )
        val newFile = createResp.optJSONObject("file") ?: throw IllegalStateException("PikPak 未返回上传文件句柄")
        val newFileId = newFile.optString("id")
        if (newFileId.isEmpty()) throw IllegalStateException("PikPak 未返回新文件 id")
        val resumable = createResp.optJSONObject("resumable")?.optJSONObject("params")
            ?: throw IllegalStateException("PikPak 未返回 OSS 上传参数")
        val endpoint = resumable.optString("endpoint")
        val bucket = resumable.optString("bucket")
        val key = resumable.optString("key")
        val accessKeyId = resumable.optString("access_key_id")
        val accessKeySecret = resumable.optString("access_key_secret")
        val securityToken = resumable.optString("security_token")
        if (endpoint.isEmpty() || bucket.isEmpty() || key.isEmpty() || accessKeyId.isEmpty() || accessKeySecret.isEmpty()) {
            throw IllegalStateException("PikPak OSS 上传参数不完整")
        }

        // Step 2: OSS 分片上传（阿里云 OSS V1 签名）
        ossMultipartUpload(
            endpoint = endpoint,
            bucket = bucket,
            key = key,
            accessKeyId = accessKeyId,
            accessKeySecret = accessKeySecret,
            securityToken = securityToken,
            size = size,
            partSize = partSize,
            open = open,
            onProgress = onProgress
        )

        // Step 3: 轮询 file detail 直到 phase == PHASE_TYPE_COMPLETE 且 size 匹配
        var lastDetail = JSONObject()
        repeat(PikPakConstants.UPLOAD_POLL_MAX) {
            lastDetail = fileDetail(accessToken, deviceId, newFileId)
            val phase = lastDetail.optString("phase")
            when (phase) {
                "PHASE_TYPE_COMPLETE" -> {
                    if (lastDetail.optLong("size", 0L) == size || lastDetail.optLong("size", -1L) <= 0) {
                        return@withContext UploadResult(newFileId, size)
                    }
                }
                "PHASE_TYPE_ERROR" -> {
                    val msg = lastDetail.optString("message")
                    throw IllegalStateException(
                        if (msg.isEmpty()) "PikPak 上传失败" else "PikPak 上传失败：$msg"
                    )
                }
                else -> Unit
            }
            delay(1000)
        }
        // 轮询上限到了但 phase 仍未完成：给一个可诊断的错误
        throw IllegalStateException(
            "PikPak 上传处理超时（phase=${lastDetail.optString("phase")}, size=${lastDetail.optLong("size", 0L)}/${size}）"
        )
    }

    /**
     * PikPak hash 算法：`SHA1(concat(SHA1(chunk_i)))`。
     * 分片大小直接沿用服务端指定的 partSize（避免重切块导致 hash 不匹配）。
     */
    private fun computePikPakHash(
        open: (Long, Long) -> InputStream,
        size: Long,
        partSize: Long,
        onProgress: (Long) -> Unit
    ): String {
        val outer = MessageDigest.getInstance("SHA-1")
        var offset = 0L
        var read = 0L
        while (offset < size) {
            val stop = minOf(size, offset + partSize)
            val chunk = MessageDigest.getInstance("SHA-1")
            open(offset, stop).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    chunk.update(buffer, 0, n)
                    read += n
                    onProgress(read)
                }
            }
            outer.update(chunk.digest())
            offset = stop
        }
        return outer.digest().joinToString("") { "%02X".format(it) }
    }

    /** 按文件大小选单片档位（照抄 Android 客户端策略）。 */
    private fun pickPartSize(size: Long): Long = when {
        size < 0x8000000L -> PikPakConstants.PART_SIZE_256K
        size < 0x10000000L -> PikPakConstants.PART_SIZE_512K
        size < 0x20000000L -> PikPakConstants.PART_SIZE_1M
        else -> PikPakConstants.PART_SIZE_2M
    }

    /**
     * 阿里云 OSS V1 签名分片上传：`POST ?uploads=` 建 → `PUT ?partNumber=&uploadId=` 传每片 →
     * `POST ?uploadId=` 提交 `CompleteMultipartUpload` XML；任一步失败则 `DELETE ?uploadId=` 撤销。
     *
     * stringToSign 布局（逐字照抄 Aliyun OSS V1 规范）：
     * ```
     * METHOD\n
     * \n                          ← 空 content-md5
     * content-type\n
     * Date\n
     * [x-oss-security-token:token\n]
     * CanonicalizedResource
     * ```
     */
    private fun ossMultipartUpload(
        endpoint: String,
        bucket: String,
        key: String,
        accessKeyId: String,
        accessKeySecret: String,
        securityToken: String,
        size: Long,
        partSize: Long,
        open: (Long, Long) -> InputStream,
        onProgress: (Long) -> Unit
    ) {
        val rawEndpoint = if (endpoint.contains("://")) endpoint else "https://$endpoint"
        val afterScheme = rawEndpoint.substringAfter("://")
        val slashIndex = afterScheme.indexOf('/')
        val host = if (slashIndex < 0) afterScheme else afterScheme.substring(0, slashIndex)
        val path = if (slashIndex < 0) "" else afterScheme.substring(slashIndex + 1)
            .substringBefore('?').substringBefore('#')
        if (host.isEmpty() || bucket.isEmpty() || key.isEmpty()) {
            throw IllegalStateException("OSS 上传参数缺失（endpoint / bucket / key）")
        }
        val finalHost = if (host.startsWith("$bucket.")) host else "$bucket.$host"
        val baseUri = "https://$finalHost/${path.trimEnd('/')}/$key"

        fun signedRequest(
            method: String,
            params: List<Pair<String, String>>,
            contentType: String,
            body: RequestBody? = null,
            start: Long? = null,
            end: Long? = null
        ): ResponseWrapper {
            val date = rfc1123()
            val sorted = params.sortedBy { it.first }
            val canonicalQuery = sorted.joinToString("&") { (k, v) ->
                if (v.isEmpty()) k else "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
            val resource = "/$bucket/$key" + if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery"
            val builder = StringBuilder()
                .append(method).append("\n")
                .append("\n") // 空 content-md5
                .append(contentType).append("\n")
                .append(date).append("\n")
                .apply { if (securityToken.isNotEmpty()) append("x-oss-security-token:$securityToken\n") }
                .append(resource)
            val signature = java.util.Base64.getEncoder().encodeToString(hmacSha1(accessKeySecret, builder.toString()))
            val headers = linkedMapOf(
                "Authorization" to "OSS $accessKeyId:$signature",
                "Date" to date,
                "Content-Type" to contentType
            )
            if (securityToken.isNotEmpty()) headers["x-oss-security-token"] = securityToken

            val address = if (params.isEmpty()) baseUri else baseUri + "?" + params.joinToString("&") { (k, v) -> "$k=$v" }
            if (start != null && end != null) {
                val resp = UploadIo.rangePut(address, start, end, open, contentType, headers, false, onProgress)
                return ResponseWrapper(resp.code, resp.isSuccessful, resp.header("ETag").orEmpty(), "")
            }
            val requestBuilder = Request.Builder().url(address).method(method, body ?: EMPTY_BODY)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            val resp = UploadIo.execute(requestBuilder.build(), false)
            return resp.use { ResponseWrapper(it.code, it.isSuccessful, it.header("ETag").orEmpty(), it.body?.string().orEmpty()) }
        }

        // 空文件：直接单 PUT，跳过分片
        if (size <= 0L) {
            val resp = signedRequest("PUT", emptyList(), "application/octet-stream", start = 0, end = 0)
            if (!resp.isSuccessful) throw IllegalStateException("OSS 空文件上传失败（HTTP ${resp.code}）")
            return
        }

        // Initiate
        val initiated = signedRequest("POST", listOf("uploads" to ""), "application/xml")
        if (!initiated.isSuccessful) {
            throw IllegalStateException("OSS 创建分片任务失败（HTTP ${initiated.code}｜${initiated.body.take(200)}）")
        }
        val uploadId = Regex("<UploadId>([^<]+)</UploadId>").find(initiated.body)?.groupValues?.getOrNull(1).orEmpty()
        if (uploadId.isEmpty()) throw IllegalStateException("OSS 未返回分片任务标识")

        val etags = ArrayList<String>()
        var offset = 0L
        while (offset < size) {
            val stop = minOf(size, offset + partSize)
            val partNumber = etags.size + 1
            val resp = signedRequest(
                "PUT",
                listOf("partNumber" to partNumber.toString(), "uploadId" to uploadId),
                "application/octet-stream",
                start = offset,
                end = stop
            )
            if (resp.etag.isEmpty()) {
                throw IllegalStateException("OSS 第 $partNumber 片未确认（HTTP ${resp.code}）")
            }
            etags.add(resp.etag)
            offset = stop
        }

        val xml = buildString {
            append("<CompleteMultipartUpload>")
            etags.forEachIndexed { i, etag ->
                append("<Part><PartNumber>").append(i + 1)
                    .append("</PartNumber><ETag>").append(etag.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }
        val done = signedRequest(
            "POST",
            listOf("uploadId" to uploadId),
            "application/xml",
            body = xml.toByteArray().toRequestBody(xmlMediaType)
        )
        if (!done.isSuccessful || done.body.contains("<Error>")) {
            throw IllegalStateException("OSS 未完成分片合并（HTTP ${done.code}｜${done.body.take(200)}）")
        }
    }

    private fun hmacSha1(secret: String, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF-8), "HmacSHA1"))
        return mac.doFinal(message.toByteArray(Charsets.UTF-8))
    }

    private fun rfc1123(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private data class ResponseWrapper(
        val code: Int,
        val isSuccessful: Boolean,
        val etag: String,
        val body: String
    )

    // ==================== 内部工具 ====================

    /** 需要登录的请求头。 */
    private fun authHeaders(deviceId: String, accessToken: String): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
            .add("User-Agent", PikPakConstants.USER_AGENT)
            .add("X-Device-Id", deviceId)
            .add("X-Client-Id", PikPakConstants.CLIENT_ID)
            .add("Authorization", "Bearer $accessToken")
            .add("Accept", "application/json, text/plain, */*")
            .add("Content-Type", "application/json")
        return builder.build()
    }

    /** 匿名（未登录）请求头。 */
    private fun authlessHeaders(deviceId: String): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
            .add("User-Agent", PikPakConstants.USER_AGENT)
            .add("X-Device-Id", deviceId)
            .add("X-Client-Id", PikPakConstants.CLIENT_ID)
            .add("Accept", "application/json, text/plain, */*")
            .add("Content-Type", "application/json")
        return builder.build()
    }

    /**
     * 执行请求并返回 JSON。
     *
     * @param phase 出错时携带的定位信息（"share open" / "refresh" 等）
     * @param requireSuccess 若 true，遇到 401/403 抛 [PikPakLoginExpiredException]；false 时原样返回 JSON 供调用方判断
     */
    private fun execJson(
        request: Request,
        phase: String,
        requireSuccess: Boolean = true
    ): JSONObject {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val ct = resp.header("Content-Type").orEmpty()
            if (!resp.isSuccessful) {
                if (requireSuccess && (resp.code == 401 || resp.code == 403)) {
                    throw PikPakLoginExpiredException()
                }
                // 401 未拦截分支（登录 / 刷新场景）：把服务端错误原样返回给调用方
                return runCatching { JSONObject(body) }.getOrNull()
                    ?: JSONObject().put("error", body.take(200)).put("httpCode", resp.code)
            }
            return runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException(
                    "PikPak $phase 响应无法解析（HTTP ${resp.code}｜$ct｜${body.take(160)}）"
                )
            }
        }
    }

    /** 解析文件条目为 [ShareFile]（`kind == "drive#folder"` 视为目录）。 */
    private fun parseShareFile(item: JSONObject?): ShareFile? {
        item ?: return null
        val id = item.optString("id")
        if (id.isEmpty()) return null
        val isDir = item.optString("kind") == "drive#folder"
        val name = item.optString("name")
        if (name.isEmpty()) return null
        return ShareFile(
            fid = id,
            fname = name,
            fsize = if (isDir) 0L else item.optLong("size", 0L),
            isdir = isDir,
            pdirFid = item.optString("parent_id"),
            fidToken = "",
            modifyTime = firstNonBlank(item.optString("modified_time"), item.optString("created_time"))
        )
    }

    /** 分享状态码解析。 */
    private fun parseShareStatus(status: String): PikPakShareStatus = when (status) {
        "PASS_CODE_EMPTY" -> PikPakShareStatus.PASS_CODE_EMPTY
        "PASS_CODE_ERROR" -> PikPakShareStatus.PASS_CODE_ERROR
        "DELETED" -> PikPakShareStatus.DELETED
        "EXPIRED" -> PikPakShareStatus.EXPIRED
        "NOT_FOUND" -> PikPakShareStatus.NOT_FOUND
        else -> PikPakShareStatus.UNKNOWN
    }

    /** PikPak 服务端会返回多种过期时间字段（三种命名都兼容），统一折算为毫秒。 */
    private fun resolveExpiresAtMs(
        expireTime: String,
        expiresAt: String,
        expiresIn: String
    ): Long {
        // 1) expire_time 常见为 epoch 秒
        expireTime.toLongOrNull()?.let { if (it > 0L) return it * 1000L }
        // 2) expires_at 可能是 ISO 8601（"2026-10-25T10:00:00Z"）
        if (expiresAt.isNotEmpty()) {
            val parsed = runCatching {
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    time = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        .parse(expiresAt)
                }.timeInMillis
            }.getOrNull()
            if (parsed != null) return parsed
        }
        // 3) expires_in 是相对秒数
        expiresIn.toLongOrNull()?.let { if (it > 0L) return System.currentTimeMillis() + it * 1000L }
        return 0L
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}
