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

package com.jiexiy.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jiexiy.app.data.db.AliyunAccountEntity
import com.jiexiy.app.data.network.AliyunWebCredential
import com.jiexiy.app.data.repository.AliyunAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 阿里云盘账号 ViewModel：管理登录状态与凭据。
 *
 * 阿里云盘只支持网页登录（refresh_token 续期），没有账号密码直登接口，
 * 所以这里只暴露 `saveWebCredential` 而非 `login`。
 */
class AliyunAccountViewModel(
    private val repository: AliyunAccountRepository
) : ViewModel() {

    val aliyunAccount: StateFlow<AliyunAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val isLoggedIn: StateFlow<Boolean> = repository.observeAccount()
        .map { it != null && it.refreshToken.isNotBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    private var addNewAccount = false

    fun beginAddAccount() {
        addNewAccount = true
    }

    fun cancelAddAccount() {
        addNewAccount = false
    }

    /** 保存网页登录凭据；返回 (是否成功, 提示文案)。 */
    suspend fun saveWebCredential(raw: String): Pair<Boolean, String> {
        val tokens = AliyunWebCredential.parse(raw)
        if (tokens == null) {
            return false to "未检测到有效登录凭据，请先在网页中完成登录"
        }
        if (tokens.refreshToken.isBlank()) {
            return false to "网页未返回刷新令牌，无法登录"
        }
        val result = repository.saveWebCredential(tokens, newAccount = addNewAccount)
        if (result.isSuccess) addNewAccount = false
        return result.fold(
            onSuccess = { true to "登录成功" },
            onFailure = { false to (it.message ?: "阿里云盘登录失败，请检查网络连接") }
        )
    }

    class Factory(
        private val repository: AliyunAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AliyunAccountViewModel::class.java))
            return AliyunAccountViewModel(repository) as T
        }
    }
}
