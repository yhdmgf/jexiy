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
import com.jiexiy.app.data.db.PikPakAccountEntity
import com.jiexiy.app.data.repository.PikPakAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * PikPak 账号 ViewModel（对齐 [ILanzouAccountViewModel] 的多账号「添加账号」模式）。
 */
class PikPakAccountViewModel(
    private val repository: PikPakAccountRepository
) : ViewModel() {

    val pikpakAccount: StateFlow<PikPakAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private var addNewAccount = false

    fun beginAddAccount() {
        addNewAccount = true
    }

    fun cancelAddAccount() {
        addNewAccount = false
    }

    /** 账号密码登录；返回 (是否成功, 提示文案)。 */
    suspend fun login(account: String, password: String): Pair<Boolean, String> {
        if (account.isBlank() || password.isBlank()) {
            return false to "请输入 PikPak 账号和密码"
        }
        val result = repository.login(account, password, newAccount = addNewAccount)
        if (result.isSuccess) addNewAccount = false
        return result.fold(
            onSuccess = { true to "登录成功" },
            onFailure = { false to (it.message ?: "PikPak 登录失败，请检查账号密码") }
        )
    }

    /** 保存网页登录凭据；返回是否成功。 */
    suspend fun saveWebCredential(raw: String): Boolean {
        val ok = repository.saveWebCredential(raw, newAccount = addNewAccount)
        if (ok) addNewAccount = false
        return ok
    }

    class Factory(
        private val repository: PikPakAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PikPakAccountViewModel::class.java))
            return PikPakAccountViewModel(repository) as T
        }
    }
}
