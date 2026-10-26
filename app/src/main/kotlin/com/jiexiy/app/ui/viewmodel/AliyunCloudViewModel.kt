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
import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.network.model.ShareInfo
import com.jiexiy.app.data.repository.AliyunAccountRepository
import com.jiexiy.app.ui.preview.CloudPreview
import com.jiexiy.app.ui.preview.PreviewTypes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

sealed interface AliyunCloudUiState {
    data object Loading : AliyunCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirId: String
    ) : AliyunCloudUiState
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

    var actionFile by mutableStateOf<ShareFile?>(null)
        private set
    var cloudMessage by mutableStateOf<String?>(null)
        private set
    var isOperating by mutableStateOf(false)
        private set
    var folderProgress by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var downloadTriggered by mutableStateOf(0)
        private set
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set
    var multiSelectMode by mutableStateOf(false)
        private set
    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<AliyunCloudUiState>(AliyunCloudUiState.Loading)
    val moveUiState: StateFlow<AliyunCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
        viewModelScope.launch {
            loginState
                .drop(1)
                .distinctUntilChanged()
                .collect { loggedIn -> if (loggedIn) loadRoot() }
        }
    }

    private suspend fun credential(): AliyunCredential =
        accountRepository.buildCredential() ?: throw IllegalStateException("请先登录阿里云盘")

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("", emptyList())
    }

    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        load(file.fid, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "", nameStack.toList())
    }

    fun toggleSelect(file: ShareFile) {
        if (multiSelectMode) {
            if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
        } else {
            actionFile = file
        }
    }

    fun enterMultiSelect() {
        multiSelectMode = true
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        _selected.clear()
        actionFile = null
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            val currentDir = dirStack.lastOrNull() ?: ""
            val names = nameStack.toList()
            load(currentDir, names)
            refreshing = false
        }
    }

    fun selectAll() {
        if (_uiState.value is AliyunCloudUiState.Loaded) {
            val files = (_uiState.value as AliyunCloudUiState.Loaded).files
            _selected.addAll(files)
        }
    }

    fun clearSelection() {
        _selected.clear()
    }

    fun showDownload(file: ShareFile) {
        viewModelScope.launch {
            try {
                val c = credential()
                val link = api.getDownloadUrl(c, file.fid)
                downloadLink = DownloadLink(
                    url = link,
                    fileName = file.fname,
                    fileSize = file.fsize,
                    platform = DownloadPlatform.ALIYUN
                )
            } catch (e: Exception) {
                cloudMessage = "获取下载链接失败：${e.message}"
            }
        }
    }

    fun startDownload(file: ShareFile) {
        viewModelScope.launch {
            try {
                val c = credential()
                val link = api.getDownloadUrl(c, file.fid)
                downloadManager.start(
                    url = link,
                    fileName = file.fname,
                    platform = DownloadPlatform.ALIYUN,
                    fileSize = file.fsize
                )
                downloadTriggered++
            } catch (e: Exception) {
                cloudMessage = "下载失败：${e.message}"
            }
        }
    }

    fun startBatchDownload(files: List<ShareFile>) {
        viewModelScope.launch {
            try {
                val c = credential()
                for (file in files) {
                    if (file.isdir) {
                        cloudMessage = "不支持下载文件夹"
                        return@launch
                    }
                    val link = api.getDownloadUrl(c, file.fid)
                    downloadManager.start(
                        url = link,
                        fileName = file.fname,
                        platform = DownloadPlatform.ALIYUN,
                        fileSize = file.fsize
                    )
                }
                downloadTriggered += files.size
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = "批量下载失败：${e.message}"
            }
        }
    }

    fun rename(file: ShareFile, newName: String) {
        viewModelScope.launch {
            try {
                val c = credential()
                api.rename(c, file.fid, newName)
                cloudMessage = "重命名成功"
                refresh()
            } catch (e: Exception) {
                cloudMessage = "重命名失败：${e.message}"
            }
        }
    }

    fun delete(file: ShareFile) {
        viewModelScope.launch {
            try {
                val c = credential()
                api.delete(c, listOf(file.fid))
                cloudMessage = "删除成功"
                refresh()
            } catch (e: Exception) {
                cloudMessage = "删除失败：${e.message}"
            }
        }
    }

    fun batchDelete(files: List<ShareFile>) {
        viewModelScope.launch {
            try {
                val c = credential()
                val fids = files.map { it.fid }
                api.delete(c, fids)
                cloudMessage = "删除成功"
                exitMultiSelect()
                refresh()
            } catch (e: Exception) {
                cloudMessage = "批量删除失败：${e.message}"
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                val c = credential()
                val dirId = dirStack.lastOrNull() ?: ""
                api.mkdir(c, dirId, name)
                cloudMessage = "文件夹创建成功"
                refresh()
            } catch (e: Exception) {
                cloudMessage = "创建文件夹失败：${e.message}"
            }
        }
    }

    fun createShare(files: List<ShareFile>, title: String, expireDays: Int, passcode: String?) {
        viewModelScope.launch {
            try {
                val c = credential()
                val fids = files.map { it.fid }
                val share = api.createShare(c, fids, title, expireDays, passcode)
                shareResult = ShareInfo(
                    id = share.shareId,
                    link = share.shareUrl,
                    title = title,
                    password = passcode
                )
            } catch (e: Exception) {
                cloudMessage = "创建分享失败：${e.message}"
            }
        }
    }

    fun showMove(file: ShareFile) {
        actionFile = file
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
            loadMove("", emptyList())
        }
    }

    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fid)
        moveNameStack.addLast(file.fname)
        viewModelScope.launch {
            loadMove(file.fid, moveNameStack.toList())
        }
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) {
            loadMoveRoot()
            return
        }
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        viewModelScope.launch {
            loadMove(moveDirStack.lastOrNull() ?: "", moveNameStack.toList())
        }
    }

    fun confirmMove() {
        viewModelScope.launch {
            try {
                val c = credential()
                val destDir = moveDirStack.lastOrNull() ?: ""
                val fids = if (_selected.isNotEmpty()) _selected.map { it.fid } else actionFile?.let { listOf(it.fid) } ?: emptyList()
                if (fids.isEmpty()) {
                    cloudMessage = "未选择文件"
                    return@launch
                }
                api.move(c, fids, destDir)
                cloudMessage = "移动成功"
                exitMultiSelect()
                refresh()
            } catch (e: Exception) {
                cloudMessage = "移动失败：${e.message}"
            }
        }
    }

    fun cancelMove() {
        moveDirStack.clear()
        moveNameStack.clear()
        _selected.clear()
        actionFile = null
    }

    fun preview(file: ShareFile): CloudPreview {
        return when (PreviewTypes.detect(file.fname)) {
            PreviewTypes.IMAGE -> CloudPreview.Image(file)
            PreviewTypes.VIDEO -> CloudPreview.Video(file)
            PreviewTypes.AUDIO -> CloudPreview.Audio(file)
            PreviewTypes.DOCUMENT -> CloudPreview.Document(file)
            PreviewTypes.NONE -> CloudPreview.None(file)
        }
    }

    private suspend fun load(dirId: String, pathNames: List<String>) {
        viewModelScope.launch {
            _uiState.value = AliyunCloudUiState.Loading
            try {
                val c = credential()
                val files = api.listPersonal(c, dirId)
                _uiState.value = AliyunCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _uiState.value = AliyunCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    private suspend fun loadMove(dirId: String, pathNames: List<String>) {
        _moveUiState.value = AliyunCloudUiState.Loading
        try {
            val c = credential()
            val files = api.listPersonal(c, dirId)
            _moveUiState.value = AliyunCloudUiState.Loaded(files, pathNames, dirId)
        } catch (e: Exception) {
            _moveUiState.value = AliyunCloudUiState.Error(e.message ?: "加载失败")
        }
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
