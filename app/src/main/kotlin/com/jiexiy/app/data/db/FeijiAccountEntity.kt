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
 * 小飞机网盘登录凭证：
 * - appToken：所有 api.feijipan.com 请求的 URL query 鉴权参数；
 * - uuid：设备标识（网页登录时从 localStorage 的 common.uuid 读出，所有请求携带）；
 * - userId：下载接口 downloadId 需要，登录后由 account/map 补齐。
 */
@Entity(tableName = "feiji_account")
data class FeijiAccountEntity(
    @PrimaryKey
    override val id: String = "feiji",
    val appToken: String = "",
    val uuid: String = "",
    val account: String = "",
    val userId: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = false,
    override val remark: String = ""
) : MultiAccountEntity
