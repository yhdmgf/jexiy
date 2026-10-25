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

package com.jiexiy.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PikPak 登录凭证（mypikpak.com 安卓 App 客户端）：
 * - `accessToken` / `refreshToken` 落库前在 [SecureAccountDaos] 里字段级加密；
 * - `deviceId` 是 UUID 去横线的 32 位十六进制，所有请求都要带；
 * - `account` 是邮箱 / 手机号，展示用；`userId` 是服务端 `sub`，仅作内部标识；
 * - `expiresAtMs` 是 access_token 过期毫秒时间戳（0 = 未知）。
 */
@Entity(tableName = "pikpak_account")
data class PikPakAccountEntity(
    @PrimaryKey
    override val id: String = "pikpak",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val account: String = "",
    val userId: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = 0L,
    /** 是否该平台的主账号（多账号）：同平台只应有一条为 true，见 [MultiAccountEntity] */
    override val isPrimary: Boolean = false,
    /** 用户备注，便于区分同一平台的多个账号（空串 = 界面回退显示昵称） */
    override val remark: String = ""
) : MultiAccountEntity
