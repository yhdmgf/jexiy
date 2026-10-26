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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 阿里云盘账号凭证。
 *
 * @param accessToken OAuth 访问令牌
 * @param refreshToken 刷新令牌（网页登录唯一能拿到的长寿命凭证）
 * @param deviceId 设备标识（自己生成，32 位十六进制）
 * @param devicePrivateKey 设备私钥（64 位十六进制 secp256k1 私钥，自己生成并持久化）
 * @param userId 用户 id（首次调用 [AliyunApi.userInfo] 后填入）
 * @param driveId 个人空间 drive_id（首次调用 [AliyunApi.userInfo] 后填入）
 * @param nickname 昵称（可选）
 * @param signature 当前设备会话签名（[AliyunApi.ensureDeviceSession] 写入）
 * @param deviceSessionId 当前设备会话 id
 * @param signatureExpiresAt 设备会话过期时间（epoch 毫秒）
 */
data class AliyunCredential(
    val id: String = "aliyun",
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String = AliyunConstants.newDeviceId(),
    val devicePrivateKey: String = AliyunSignature.privateKey(),
    val userId: String = "",
    val driveId: String = "",
    val nickname: String = "",
    val signature: String = "",
    val deviceSessionId: String = "",
    val signatureExpiresAt: Long = 0L
)

/** 阿里云盘用户信息（`user.aliyundrive.com/v2/user/get` 返回值）。 */
data class AliyunUserInfo(
    val userId: String,
    val nickname: String,
    val driveId: String,
    val resourceDriveId: String,
    val backupDriveId: String
)

/** 阿里云盘分享会话。 */
data class AliyunShareSession(val shareId: String, val stoken: String, val title: String)

/**
 * 阿里云盘 API 封装。对照 wenxi `lib/data/providers/aliyun.dart`。
 *
 * 请求分三类，签名要求不同：
 *  - 用户信息（`USER` 域名）：只带 `Authorization`，不需要设备签名；
 *  - 账号/容量/相册（`api/v2/...`）：带 `Authorization` + `X-Signature`；
 *  - 分享接口（`/v2/share_link/` 前缀、`/adrive/v2/file/list_by_share`）：
 *    只带 `X-Share-Token`，不带 `Authorization`（匿名可读）。
 *
 * 设备会话 25 分钟过期，所有需签名的请求前先走 [ensureDeviceSession]。
 */
class AliyunApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()
    private val jsonMediaType = "application/json;charset=UTF-8".toMediaType()

    // ---------- 认证与账号 ----------

    /**
     * 用刷新令牌换取新的 access_token。
     * 对应 `POST https://auth.aliyundrive.com/v2/account/token`。
     */
    /** 令牌刷新结果。 */
    data class RefreshResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long, // 秒
        val userId: String = "",
        val nickname: String = "",
        val driveId: String = ""
    )

    suspend fun refreshToken(refreshToken: String, deviceId: String): RefreshResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .put("grant_type", "refresh_token")
        // 令牌失效时服务端返 400/401，统一归为「需重新登录」
        val data = try {
            post(AliyunConstants.AUTH + AliyunConstants.PATH_TOKEN_REFRESH, body, deviceId)
        } catch (error: IllegalStateException) {
            throw IllegalStateException("阿里云盘登录凭据已失效，请重新网页登录")
        }
        if (data.optString("code") == "InvalidParameter.RefreshToken") {
            throw IllegalStateException("阿里云盘登录凭据已失效，请重新网页登录")
        }
        val access = data.optString("access_token")
        if (access.isBlank()) throw IllegalStateException("阿里云盘未返回访问令牌")
        RefreshResult(
            accessToken = access,
            refreshToken = data.optString("refresh_token", refreshToken),
            expiresIn = data.optLong("expire_in", 3600L),
            userId = data.optString("user_id"),
            nickname = data.optString("nick_name"),
            driveId = data.optString("default_drive_id")
        )
    }

    /**
     * 读取用户信息（用户 id、昵称、三个空间 drive_id）。此接口不需要设备签名。
     */
    suspend fun userInfo(accessToken: String, deviceId: String): AliyunUserInfo = withContext(Dispatchers.IO) {
        val data = post(AliyunConstants.USER + AliyunConstants.PATH_USER_INFO, JSONObject(), deviceId, accessToken = accessToken)
        val userId = data.optString("user_id")
        if (userId.isBlank()) throw IllegalStateException("阿里云盘账号信息缺失")
        val nickname = firstNonBlank(data.optString("nick_name"), data.optString("name"))
        AliyunUserInfo(
            userId = userId,
            nickname = nickname,
            driveId = data.optString("default_drive_id"),
            resourceDriveId = data.optString("resource_drive_id"),
            backupDriveId = data.optString("backup_drive_id")
        )
    }

    /**
     * 读取账号容量。对应 `POST /v2/databox/get_personal_info`，需设备签名。
     */
    suspend fun quota(credential: AliyunCredential): QuotaInfo = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val data = post(AliyunConstants.PATH_PERSONAL_INFO, JSONObject(),
            c.deviceId, c.accessToken)
        val space = data.optJSONObject("personal_space_info")
            ?: throw IllegalStateException("阿里云盘未返回有效容量信息")
        QuotaInfo(space.optLong("used_size"), space.optLong("total_size"))
    }

    /**
     * 读取昵称与容量（供账号页展示）。
     */
    suspend fun accountName(credential: AliyunCredential): String = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val data = post(AliyunConstants.PATH_PERSONAL_INFO, JSONObject(),
            c.deviceId, c.accessToken)
        firstNonBlank(
            data.optString("nick_name"),
            data.optString("name"),
            credential.nickname.ifBlank { "阿里云盘用户" }
        )
    }

    // ---------- 设备会话 ----------

    /**
     * 确保设备会话有效，返回补齐了签名与新令牌的凭证。
     *
     * 设备会话由 `devicePrivateKey` 签名生成，25 分钟过期；过期或服务端拒绝时重建。
     */
    suspend fun ensureDeviceSession(credential: AliyunCredential): AliyunCredential = withContext(Dispatchers.IO) {
        var c = credential
        if (c.userId.isBlank()) {
            c = c.copy(accessToken = refreshToken(c.refreshToken, c.deviceId).accessToken)
            val info = userInfo(c.accessToken, c.deviceId)
            c = c.copy(
                userId = info.userId,
                driveId = c.driveId.ifBlank { info.driveId },
                nickname = c.nickname.ifBlank { info.nickname }
            )
        }
        val now = System.currentTimeMillis()
        if (c.signature.isNotBlank() && now < c.signatureExpiresAt - 60_000L) return@withContext c

        val signed = AliyunSignature.sign(c.devicePrivateKey, c.deviceId, c.userId)
        val body = JSONObject()
            .put("deviceName", AliyunConstants.DEVICE_NAME)
            .put("modelName", AliyunConstants.DEVICE_MODEL)
            .put("pubKey", signed.publicKey)
        val data = postSigned(
            AliyunConstants.PATH_DEVICE_SESSION,
            body,
            c.copy(signature = signed.signature)
        )
        if (!data.optBoolean("result")) {
            throw IllegalStateException("阿里设备会话创建失败，请在官网检查已登录设备后重新登录")
        }
        c.copy(
            signature = signed.signature,
            deviceSessionId = AliyunConstants.newDeviceId(),
            signatureExpiresAt = now + AliyunConstants.DEVICE_SESSION_TTL_SEC * 1000L
        )
    }

    // ---------- 个人盘 ----------

    /**
     * 列出个人空间目录。对应 `POST /adrive/v3/file/list`，需设备签名。
     * 自动翻页（每页 [AliyunConstants.LIST_LIMIT] 条，最多 1000 页）。
     */
    suspend fun listPersonal(credential: AliyunCredential, parentFileId: String): List<ShareFile> =
        withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失，请重新登录") }
        listPages(parentFileId) { marker ->
            val body = JSONObject()
                .put("drive_id", driveId)
                .put("parent_file_id", parentFileId)
                .put("limit", AliyunConstants.LIST_LIMIT)
                .put("marker", marker)
                .put("all", false)
                .put("fields", "*")
                .put("url_expire_sec", AliyunConstants.URL_EXPIRE_SEC)
                .put("order_by", "name")
                .put("order_direction", "ASC")
            postSigned(AliyunConstants.PATH_LIST, body, c)
        }
    }

    /**
     * 取原文件下载直链。对应 `POST /v2/file/get_download_url`。
     * 部分文件（老文件、被标记的预览型文件）取不到时会回退到 `/v2/file/get`。
     */
    suspend fun getDownloadUrl(credential: AliyunCredential, fileId: String, fileName: String, size: Long): DownloadLink =
        withContext(Dispatchers.IO) {
            val c = ensureDeviceSession(credential)
            val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失，请重新登录") }
            var data = try {
                postSignedDownload(AliyunConstants.PATH_DOWNLOAD_URL, JSONObject()
                    .put("drive_id", driveId)
                    .put("file_id", fileId)
                    .put("expire_sec", AliyunConstants.URL_EXPIRE_SEC), c)
            } catch (error: IllegalStateException) {
                null
            }
            var url = data?.let { pickUrl(it, allowFallback = true) } ?: ""
            if (url.isBlank()) {
                data = postSignedDownload(AliyunConstants.PATH_FILE_GET, JSONObject()
                    .put("drive_id", driveId)
                    .put("file_id", fileId)
                    .put("fields", "*")
                    .put("url_expire_sec", AliyunConstants.URL_EXPIRE_SEC), c)
                url = pickUrl(data, allowFallback = false)
            }
            if (url.isBlank()) throw IllegalStateException("阿里未返回原文件下载地址，请确认文件下载权限")
            DownloadLink(
                fid = fileId,
                filename = fileName,
                downloadUrl = url,
                size = size
            )
        }

    /**
     * 新建目录。对应 `POST /adrive/v2/file/createWithFolders`。
     */
    suspend fun mkdir(credential: AliyunCredential, parentFileId: String, name: String): ShareFile = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失，请重新登录") }
        val data = postSigned(AliyunConstants.PATH_MKDIR, JSONObject()
            .put("drive_id", driveId)
            .put("name", name)
            .put("parent_file_id", parentFileId), c)
        val id = data.optString("file_id")
        if (id.isBlank()) throw IllegalStateException("阿里云盘未返回新目录标识")
        ShareFile(id, name, 0L, true, parentFileId, "")
    }

    /** 重命名文件。对应 `POST /file/update`。 */
    suspend fun rename(credential: AliyunCredential, fileId: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失，请重新登录") }
        postSigned(AliyunConstants.PATH_RENAME, JSONObject()
            .put("drive_id", driveId)
            .put("file_id", fileId)
            .put("name", newName), c)
        Unit
    }

    /** 移动文件。对应 `POST /file/move`。 */
    suspend fun move(credential: AliyunCredential, fileIds: List<String>, toParentFileId: String): Unit = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失，请重新登录") }
        postSigned(AliyunConstants.PATH_MOVE, JSONObject()
            .put("drive_id", driveId)
            .put("ids", JSONArray(fileIds))
            .put("to_parent_file_id", toParentFileId), c)
        Unit
    }

    /** 删除文件（进回收站）。对应 `POST /adrive/v4/batch` 的 `file/delete` + 轮询任务状态。 */
    suspend fun delete(credential: AliyunCredential, fileIds: List<String>): Unit = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失，请重新登录") }
        val body = JSONObject()
            .put("request_id", UUID.randomUUID().toString())
            .put("resource_type", "file")
            .put("batch_id", UUID.randomUUID().toString())
            .put("action", JSONObject()
                .put("name", "file/delete")
                .put("request", JSONObject()
                    .put("drive_id", driveId)
                    .put("ids", JSONArray(fileIds))
                    .put("type", "file")))
        val data = postSigned(AliyunConstants.PATH_BATCH, body, c)
        if (!data.optBoolean("success")) {
            throw IllegalStateException("阿里云盘删除失败")
        }
        Unit
    }

    /** 异步任务轮询。对应 `POST /v2/async_task/get`。wenxi `_waitTask`。 */
    private fun waitAsyncTask(c: AliyunCredential, asyncTaskId: String): Unit {
        var attempts = 0
        while (attempts < AliyunConstants.TASK_POLL_MAX) {
            val data = postSigned(
                AliyunConstants.PATH_ASYNC_TASK_GET,
                JSONObject().put("async_task_id", asyncTaskId),
                c
            )
            val state = data.optString("state")
            when {
                state == "succeed" || state == "success" -> return
                state == "failed" || state == "fail" || state == "cancelled" ->
                    throw IllegalStateException("阿里云盘异步任务失败：${data.optString("reason", state)}")
                else -> {
                    Thread.sleep(AliyunConstants.TASK_POLL_DELAY_MS)
                    attempts++
                }
            }
        }
        throw IllegalStateException("阿里云盘异步任务超时")
    }

    /** 分享文件转存到自己网盘。对应 wenxi `_save` + `_batch('/file/copy')`。 */
    suspend fun saveShareFile(
        credential: AliyunCredential,
        shareId: String,
        shareToken: String,
        fileId: String,
        toDriveId: String,
        toParentFileId: String
    ): String = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val actionRequest = JSONObject()
            .put("share_id", shareId)
            .put("file_id", fileId)
            .put("to_drive_id", toDriveId)
            .put("to_parent_file_id", toParentFileId)
            .put("auto_rename", true)
        val data = postSignedBatch(c, UUID.randomUUID().toString(), "file/copy", actionRequest, shareToken)
        checkBusiness(data, "阿里云盘转存失败")
        val body = data.optJSONObject("response") ?: data
        val resultBody = body.optJSONObject("body") ?: body
        val resultFileId = resultBody.optString("file_id")
        val asyncTaskId = resultBody.optString("async_task_id")
        if (asyncTaskId.isNotBlank()) {
            waitAsyncTask(c, asyncTaskId)
        }
        if (resultFileId.isBlank()) {
            throw IllegalStateException("阿里云盘转存未返回文件 ID")
        }
        resultFileId
    }

    /** 删除到回收站。对应 wenxi `delete` + `_trash`。 */
    suspend fun deleteTrash(credential: AliyunCredential, fileId: String): Unit = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失") }
        val data = postSigned(
            AliyunConstants.PATH_FILE_TRASH,
            JSONObject().put("drive_id", driveId).put("file_id", fileId),
            c
        )
        val asyncTaskId = data.optString("async_task_id")
        if (asyncTaskId.isNotBlank()) {
            waitAsyncTask(c, asyncTaskId)
        }
        Unit
    }

    /** 创建分享链接。对应 wenxi `createShare`。 */
    suspend fun createShare(
        credential: AliyunCredential,
        fileIdList: List<String>,
        title: String,
        passcode: String = "",
        expiryDays: Int = 0
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失") }
        val expiration = if (expiryDays > 0) {
            val expireMs = System.currentTimeMillis() / 1000 + expiryDays * 86400L
            // ISO 8601 格式：2026-10-25T00:00:00Z
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.format(java.util.Date(expireMs * 1000))
        } else ""
        val data = postSigned(
            AliyunConstants.PATH_SHARE_CREATE,
            JSONObject()
                .put("drive_id", driveId)
                .put("file_id_list", JSONArray(fileIdList))
                .put("share_name", title)
                .put("share_pwd", passcode)
                .put("expiration", expiration),
            c
        )
        checkBusiness(data, "阿里云盘创建分享失败")
        val shareUrl = data.optString("share_url")
        val sharePwd = data.optString("share_pwd", passcode)
        if (shareUrl.isBlank()) throw IllegalStateException("阿里云盘未返回分享链接")
        Pair(shareUrl, sharePwd)
    }

    // ---------- 分享 ----------

    /** 读取分享标题。对应 `POST /adrive/v3/share_link/get_share_by_anonymous`，匿名可读。 */
    suspend fun shareInfo(shareId: String): String = withContext(Dispatchers.IO) {
        val data = postAnonymous(AliyunConstants.PATH_SHARE_INFO, JSONObject().put("share_id", shareId))
        firstNonBlank(data.optString("share_name"), "阿里云盘分享")
    }

    /** 取分享令牌。对应 `POST /v2/share_link/get_share_token`，匿名可读。 */
    suspend fun shareToken(shareId: String, passcode: String): AliyunShareSession = withContext(Dispatchers.IO) {
        val info = postAnonymous(AliyunConstants.PATH_SHARE_INFO, JSONObject().put("share_id", shareId))
        val tokenData = postAnonymous(
            AliyunConstants.PATH_SHARE_TOKEN,
            JSONObject().put("share_id", shareId).put("share_pwd", passcode)
        )
        val token = tokenData.optString("share_token")
        if (token.isBlank()) throw IllegalStateException("阿里未返回分享凭据，请检查提取码")
        AliyunShareSession(shareId, token, firstNonBlank(info.optString("share_name"), "阿里云盘分享"))
    }

    /** 列出分享目录。对应 `POST /adrive/v2/file/list_by_share`，用分享令牌鉴权。 */
    suspend fun listByShare(session: AliyunShareSession, parentFileId: String): List<ShareFile> =
        withContext(Dispatchers.IO) {
            listPages(parentFileId) { marker ->
                postAnonymousToken(AliyunConstants.PATH_LIST_BY_SHARE, JSONObject()
                    .put("share_id", session.shareId)
                    .put("parent_file_id", parentFileId)
                    .put("limit", AliyunConstants.LIST_LIMIT)
                    .put("marker", marker)
                    .put("all", false)
                    .put("fields", "*")
                    .put("url_expire_sec", AliyunConstants.URL_EXPIRE_SEC)
                    .put("order_by", "name")
                    .put("order_direction", "ASC"), session.stoken)
            }
        }

    /** 取分享内文件的下载直链。对应 `POST /adrive/v2/file/get_share_download_url`，匿名（用分享令牌鉴权）。 */
    suspend fun shareDownloadUrl(session: AliyunShareSession, fileId: String): String = withContext(Dispatchers.IO) {
        val data = postAnonymousToken(
            AliyunConstants.PATH_SHARE_DOWNLOAD_URL,
            JSONObject().put("share_id", session.shareId).put("file_id", fileId),
            session.stoken
        )
        checkBusiness(data, "阿里云盘取直链失败")
        pickUrl(data, allowFallback = true).ifBlank { throw IllegalStateException("阿里云盘未返回直链") }
    }

    // ---------- 上传 ----------

    /**
     * 上传单个文件（三步：申请上传 → 取预签名地址 → 直传 OSS → 提交上传）。
     * 对应 wenxi `aliyun.dart` 的 `upload()`，走 `/adrive/v4/batch` 的 `upload_file` 动作族。
     *
     * @param input 文件字节流（调用方负责关闭）
     */
    suspend fun upload(
        credential: AliyunCredential,
        parentFileId: String,
        name: String,
        size: Long,
        input: () -> java.io.InputStream
    ): ShareFile = withContext(Dispatchers.IO) {
        val c = ensureDeviceSession(credential)
        val driveId = c.driveId.ifBlank { throw IllegalStateException("阿里云盘空间标识缺失，请重新登录") }
        val requestId = UUID.randomUUID().toString()

        // 创建上传任务（/adrive/v2/file/createWithFolders）
        val partInfoList = JSONArray()
        partInfoList.put(JSONObject().put("part_number", 1))
        val createBody = JSONObject()
            .put("drive_id", driveId)
            .put("parent_file_id", parentFileId)
            .put("name", name)
            .put("type", "file")
            .put("size", size)
            .put("check_name_mode", "refuse")
            .put("content_hash_name", "none")
            .put("proof_version", "v1")
            .put("part_info_list", partInfoList)
        val createResp = postSignedBatch(c, requestId, "upload_file", JSONObject()
            .put("drive_id", driveId)
            .put("parent_file_id", parentFileId)
            .put("name", name)
            .put("size", size)
            .put("conflict", "auto_rename")
            .put("task_id", requestId)
            .put("part_number", 1))
        if (!createResp.optBoolean("success")) {
            throw IllegalStateException("阿里云盘上传申请失败")
        }
        val fileId = createResp.optString("file_id")
        val uploadId = createResp.optString("upload_id")
        if (fileId.isBlank() || uploadId.isBlank()) {
            throw IllegalStateException("阿里云盘未返回上传标识")
        }

        // 获取预签名 URL
        val urlData = postSignedBatch(c, UUID.randomUUID().toString(), "get_upload_url", JSONObject()
            .put("drive_id", driveId)
            .put("file_id", fileId)
            .put("upload_id", uploadId)
            .put("part_number", 1))
        val presignUrl = urlData.optString("presigned_url")
        if (presignUrl.isBlank()) throw IllegalStateException("阿里云盘未返回上传地址")

        // 直传 OSS（流式上传，避免 OOM）
        val buffer = ByteArray(8192)
        val inputStream = input()
        val requestBuilder = Request.Builder()
            .url(presignUrl)
            .method("PUT", object : RequestBody() {
                override fun contentType() = okhttp3.MediaType.parse("application/octet-stream")
                override fun contentLength() = size
                override fun writeTo(sink: okio.BufferedSink) {
                    var read = inputStream.read(buffer)
                    while (read != -1) {
                        sink.write(buffer, 0, read)
                        sink.flush()
                        read = inputStream.read(buffer)
                    }
                }
            })
            .build()
        client.newCall(requestBuilder).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("阿里云盘直传失败（HTTP ${it.code}）")
        }

        // 完成上传
        postSignedBatch(c, UUID.randomUUID().toString(), "upload_file", JSONObject()
            .put("drive_id", driveId)
            .put("file_id", fileId)
            .put("upload_id", uploadId)
            .put("part_number", 1)
            .put("task_id", requestId)
            .put("finish", true))

        ShareFile(fileId, name, size, false, parentFileId, "")
    }

    // ---------- 底层请求 ----------

    /** 翻页遍历：调用方提供「按 marker 取一页」的函数，返回完整文件列表。 */
    private suspend fun listPages(parentFileId: String, page: (String) -> JSONObject): List<ShareFile> {
        val result = mutableListOf<ShareFile>()
        val seen = HashSet<String>()
        val seenMarkers = HashSet<String>()
        var marker = ""
        for (pageNo in 0 until 1000) {
            val data = page(marker)
            checkBusiness(data, "阿里云盘列表返回异常")
            val items = data.optJSONArray("items")
                ?: throw IllegalStateException("阿里云盘未返回有效文件列表，请重试")
            var added = 0
            for (i in 0 until items.length()) {
                val file = parseFile(items.optJSONObject(i), parentFileId)
                if (file != null && seen.add(file.fid)) {
                    result.add(file)
                    added++
                }
            }
            marker = data.optString("next_marker")
            if (marker.isBlank()) return result
            if (added == 0 || !seenMarkers.add(marker)) {
                throw IllegalStateException("阿里云盘分页重复，请刷新列表重试")
            }
        }
        throw IllegalStateException("阿里目录项目过多，请分目录打开")
    }

    /** 解析文件项。folder 与 file 统一映射成 jexiy 的 [ShareFile]。 */
    private fun parseFile(item: JSONObject?, parentFileId: String): ShareFile? {
        if (item == null) return null
        val id = item.optString("file_id")
        val name = item.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        return ShareFile(
            fid = id,
            fname = name,
            fsize = item.optLong("size"),
            isdir = item.optString("type") == "folder",
            pdirFid = item.optString("parent_file_id").ifBlank { parentFileId },
            fidToken = item.optString("share_id"),
            modifyTime = item.optString("updated_at").ifBlank { item.optString("created_at") }
        )
    }

    /** 从下载接口响应里挑一个可用的直链。`allowFallback=false` 时只接受 `download_url`。 */
    private fun pickUrl(data: JSONObject, allowFallback: Boolean): String {
        val keys = if (allowFallback) listOf("download_url", "cdn_url", "url") else listOf("download_url")
        for (key in keys) {
            val raw = data.optString(key)
            if (raw.isBlank()) continue
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) continue
            if (raw.any { it.code < 0x20 || it.code == 0x7f }) continue
            return raw
        }
        return ""
    }

    // ---------- HTTP 封装 ----------

    /**
     * 统一 POST（无设备签名）。用户信息、匿名分享接口都走这里。
     * @param accessToken 非空时携带 Authorization
     * @param canary 默认网页版 canary；下载直链用 [AliyunConstants.CANARY_DOWNLOAD]
     */
    private fun post(
        path: String,
        body: JSONObject,
        deviceId: String,
        accessToken: String = "",
        canary: String = AliyunConstants.CANARY_WEB
    ): JSONObject {
        val builder = Request.Builder()
            .url(path)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", AliyunConstants.UA)
            .header("Referer", AliyunConstants.WEB + "/")
            .header("Origin", AliyunConstants.WEB)
            .header("X-Canary", canary)
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Device-Id", deviceId)
        if (accessToken.isNotBlank()) builder.header("Authorization", "Bearer $accessToken")
        return doPost(builder, path)
    }

    /** 带设备签名的个人盘请求。 */
    private fun postSigned(path: String, body: JSONObject, c: AliyunCredential, shareToken: String = ""): JSONObject {
        val builder = Request.Builder()
            .url(AliyunConstants.API + path)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", AliyunConstants.UA)
            .header("Referer", AliyunConstants.WEB + "/")
            .header("Origin", AliyunConstants.WEB)
            .header("X-Canary", AliyunConstants.CANARY_WEB)
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("Authorization", "Bearer ${c.accessToken}")
            .header("X-Device-Id", c.deviceId)
            .header("X-Signature", c.signature)
        if (shareToken.isNotBlank()) builder.header("X-Share-Token", shareToken)
        return doPost(builder, path)
    }

    /** 下载专用请求（Android canary）。 */
    private fun postSignedDownload(path: String, body: JSONObject, c: AliyunCredential): JSONObject {
        val builder = Request.Builder()
            .url(AliyunConstants.API + path)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", AliyunConstants.UA)
            .header("Referer", AliyunConstants.WEB + "/")
            .header("Origin", AliyunConstants.WEB)
            .header("X-Canary", AliyunConstants.CANARY_DOWNLOAD)
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("Authorization", "Bearer ${c.accessToken}")
            .header("X-Device-Id", c.deviceId)
            .header("X-Signature", c.signature)
        return doPost(builder, path)
    }

    /** 匿名分享请求（不带 Authorization，只带分享令牌）。 */
    private fun postAnonymous(path: String, body: JSONObject): JSONObject =
        postAnonymousToken(path, body, "")

    private fun postAnonymousToken(path: String, body: JSONObject, stoken: String): JSONObject {
        val builder = Request.Builder()
            .url(AliyunConstants.API + path)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", AliyunConstants.UA)
            .header("Referer", AliyunConstants.WEB + "/")
            .header("Origin", AliyunConstants.WEB)
            .header("X-Canary", AliyunConstants.CANARY_WEB)
            .header("X-Request-Id", UUID.randomUUID().toString())
        if (stoken.isNotBlank()) builder.header("X-Share-Token", stoken)
        return doPost(builder, path)
    }

    /** 批量动作请求（上传族 / 转存族）。 */
    private fun postSignedBatch(
        c: AliyunCredential,
        requestId: String,
        actionName: String,
        actionRequest: JSONObject,
        shareToken: String = ""
    ): JSONObject {
        val body = JSONObject()
            .put("request_id", requestId)
            .put("resource_type", "file")
            .put("batch_id", UUID.randomUUID().toString())
            .put("action", JSONObject().put("name", actionName).put("request", actionRequest))
        return postSigned(AliyunConstants.PATH_BATCH, body, c, shareToken)
    }

    private fun doPost(builder: Request.Builder, path: String): JSONObject {
        val response = client.newCall(builder.build()).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IllegalStateException("阿里云盘接口返回异常（HTTP ${it.code}）")
            }
            // 响应体只能读一次，这里直接解析已读到的文本
            return parseJson(text)
        }
    }

    private fun parseJson(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrElse { JSONObject() }

    /**
     * 业务错误检查。阿里云盘的失败响应带 `code`（非 "0"）+ `message`。
     */
    private fun checkBusiness(json: JSONObject, fallback: String) {
        if (json.optBoolean("success", true).not()) {
            throw IllegalStateException(firstNonBlank(json.optString("message"), json.optString("msg"), fallback))
        }
        val code = json.optString("code")
        if (code.isBlank() || code == "0") return
        throw IllegalStateException(
            firstNonBlank(json.optString("message"), json.optString("msg"), "$fallback（code=$code）")
        )
    }
}

/** 提取码 / 密码校验用：全部为空时返回默认值。 */
internal fun firstNonBlank(default: String, vararg values: String): String {
    for (v in values) if (v.isNotBlank()) return v
    return default
}
