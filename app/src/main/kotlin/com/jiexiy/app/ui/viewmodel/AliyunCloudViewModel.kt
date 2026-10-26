package com.jiexiy.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jiexiy.app.data.download.DownloadManager
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.AliyunApi
import com.jiexiy.app.data.network.AliyunCredential
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.repository.AliyunAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

sealed interface AliyunCloudUiState {
    data object Loading : AliyunCloudUiState
    data class Loaded(val files: List<ShareFile>, val pathNames: List<String>, val dirId: String) : AliyunCloudUiState
    data class Error(val message: String) : AliyunCloudUiState
}

class AliyunCloudViewModel(
    private val api: AliyunApi,
    private val accountRepository: AliyunAccountRepository,
    private val downloadManager: DownloadManager,
    private val loginState: Flow<Boolean>
) : ViewModel() {

    private val _uiState = MutableStateFlow<AliyunCloudUiState>(AliyunCloudUiState.Loading)
    val uiState: StateFlow<AliyunCloudUiState> = _uiState.asStateFlow()

    var actionFile = mutableStateOf<ShareFile?>(null)
    var cloudMessage = mutableStateOf<String?>(null)
    var isOperating = mutableStateOf(false)
    var refreshing = mutableStateOf(false)
    var downloadTriggered = mutableStateOf(0)
    var shareResult = mutableStateOf<String?>(null)
    var multiSelectMode = mutableStateOf(false)
    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<AliyunCloudUiState>(AliyunCloudUiState.Loading)
    val moveUiState: StateFlow<AliyunCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
        viewModelScope.launch {
            loginState.drop(1).distinctUntilChanged().collect { loggedIn -> if (loggedIn) loadRoot() }
        }
    }

    private suspend fun credential(): AliyunCredential =
        accountRepository.buildCredential() ?: throw IllegalStateException("请先登录阿里云盘")

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        viewModelScope.launch {
            try {
                val c = credential()
                val files = api.listPersonal(c, "")
                _uiState.value = AliyunCloudUiState.Loaded(files, emptyList(), "")
            } catch (e: Exception) {
                _uiState.value = AliyunCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        viewModelScope.launch {
            try {
                val c = credential()
                val files = api.listPersonal(c, file.fid)
                _uiState.value = AliyunCloudUiState.Loaded(files, nameStack.toList(), file.fid)
            } catch (e: Exception) {
                _uiState.value = AliyunCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun back() {
        if (nameStack.isEmpty()) { loadRoot(); return }
        dirStack.removeLast()
        nameStack.removeLast()
        val dirId = dirStack.lastOrNull() ?: ""
        viewModelScope.launch {
            try {
                val c = credential()
                val files = api.listPersonal(c, dirId)
                _uiState.value = AliyunCloudUiState.Loaded(files, nameStack.toList(), dirId)
            } catch (e: Exception) {
                _uiState.value = AliyunCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun toggleSelect(file: ShareFile) {
        if (multiSelectMode.value) {
            if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
        } else {
            actionFile.value = file
        }
    }

    fun enterMultiSelect() { multiSelectMode.value = true }
    fun exitMultiSelect() { multiSelectMode.value = false; _selected.clear(); actionFile.value = null }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            val currentDir = dirStack.lastOrNull() ?: ""
            val names = nameStack.toList()
            try {
                val c = credential()
                val files = api.listPersonal(c, currentDir)
                _uiState.value = AliyunCloudUiState.Loaded(files, names, currentDir)
            } catch (e: Exception) {
                _uiState.value = AliyunCloudUiState.Error(e.message ?: "加载失败")
            }
            refreshing.value = false
        }
    }

    fun selectAll() {
        if (_uiState.value is AliyunCloudUiState.Loaded) {
            _selected.addAll((_uiState.value as AliyunCloudUiState.Loaded).files)
        }
    }

    fun clearSelection() { _selected.clear() }

    fun startDownload(file: ShareFile) {
        viewModelScope.launch {
            try {
                val c = credential()
                val link = api.getDownloadUrl(c, file.fid, file.fname, file.fsize)
                downloadManager.enqueue(
                    url = link.downloadUrl,
                    fileName = link.filename,
                    size = link.size,
                    platform = DownloadPlatform.ALIYUN
                )
                downloadTriggered.value++
            } catch (e: Exception) {
                cloudMessage.value = "下载失败：${e.message}"
            }
        }
    }

    fun rename(file: ShareFile, newName: String) {
        viewModelScope.launch {
            try {
                val c = credential()
                api.rename(c, file.fid, newName)
                cloudMessage.value = "重命名成功"
                refresh()
            } catch (e: Exception) {
                cloudMessage.value = "重命名失败：${e.message}"
            }
        }
    }

    fun delete(file: ShareFile) {
        viewModelScope.launch {
            try {
                val c = credential()
                api.delete(c, listOf(file.fid))
                cloudMessage.value = "删除成功"
                refresh()
            } catch (e: Exception) {
                cloudMessage.value = "删除失败：${e.message}"
            }
        }
    }

    fun batchDelete(files: List<ShareFile>) {
        viewModelScope.launch {
            try {
                val c = credential()
                api.delete(c, files.map { it.fid })
                cloudMessage.value = "删除成功"
                exitMultiSelect()
                refresh()
            } catch (e: Exception) {
                cloudMessage.value = "批量删除失败：${e.message}"
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                val c = credential()
                val dirId = dirStack.lastOrNull() ?: ""
                api.mkdir(c, dirId, name)
                cloudMessage.value = "文件夹创建成功"
                refresh()
            } catch (e: Exception) {
                cloudMessage.value = "创建文件夹失败：${e.message}"
            }
        }
    }

    fun createShare(files: List<ShareFile>, title: String, expireDays: Int, passcode: String?) {
        viewModelScope.launch {
            try {
                val c = credential()
                val share = api.createShare(c, files.map { it.fid }, title, expireDays, passcode)
                shareResult.value = share.shareUrl
            } catch (e: Exception) {
                cloudMessage.value = "创建分享失败：${e.message}"
            }
        }
    }

    fun showMove(file: ShareFile) {
        actionFile.value = file
        moveDirStack.clear()
        moveNameStack.clear()
        loadMoveRoot()
    }

    fun batchMove(files: List<ShareFile>) {
        _selected.clear()
        _selected.addAll(files)
        moveDirStack.clear()
        moveNameStack.clear()
        loadMoveRoot()
    }

    private fun loadMoveRoot() {
        viewModelScope.launch {
            try {
                val c = credential()
                val files = api.listPersonal(c, "")
                _moveUiState.value = AliyunCloudUiState.Loaded(files, emptyList(), "")
            } catch (e: Exception) {
                _moveUiState.value = AliyunCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fid)
        moveNameStack.addLast(file.fname)
        viewModelScope.launch {
            try {
                val c = credential()
                val files = api.listPersonal(c, file.fid)
                _moveUiState.value = AliyunCloudUiState.Loaded(files, moveNameStack.toList(), file.fid)
            } catch (e: Exception) {
                _moveUiState.value = AliyunCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun confirmMove() {
        viewModelScope.launch {
            try {
                val c = credential()
                val destDir = moveDirStack.lastOrNull() ?: ""
                val fids = if (_selected.isNotEmpty()) _selected.map { it.fid } else actionFile.value?.let { listOf(it.fid) } ?: emptyList()
                if (fids.isEmpty()) { cloudMessage.value = "未选择文件"; return@launch }
                api.move(c, fids, destDir)
                cloudMessage.value = "移动成功"
                exitMultiSelect()
                refresh()
            } catch (e: Exception) {
                cloudMessage.value = "移动失败：${e.message}"
            }
        }
    }

    fun cancelMove() {
        moveDirStack.clear()
        moveNameStack.clear()
        _selected.clear()
        actionFile.value = null
    }

    class Factory(
        private val api: AliyunApi,
        private val accountRepository: AliyunAccountRepository,
        private val downloadManager: DownloadManager,
        private val loginState: Flow<Boolean>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AliyunCloudViewModel::class.java))
            return AliyunCloudViewModel(api, accountRepository, downloadManager, loginState) as T
        }
    }
}
