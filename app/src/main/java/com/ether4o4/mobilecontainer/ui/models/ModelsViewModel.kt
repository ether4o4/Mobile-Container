package com.ether4o4.mobilecontainer.ui.models

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ether4o4.mobilecontainer.MCApp
import com.ether4o4.mobilecontainer.data.ModelManifest
import com.ether4o4.mobilecontainer.data.ModelRouter
import com.ether4o4.mobilecontainer.data.ModelStore
import com.ether4o4.mobilecontainer.runtime.InferenceService
import com.ether4o4.mobilecontainer.util.FileUtils
import com.ether4o4.mobilecontainer.util.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val router = ModelRouter(app)

    private val _models = MutableStateFlow<List<ModelManifest>>(emptyList())
    val models: StateFlow<List<ModelManifest>> = _models.asStateFlow()

    private val _disk = MutableStateFlow(0L)
    val disk: StateFlow<Long> = _disk.asStateFlow()

    private val _pulling = MutableStateFlow(false)
    val pulling: StateFlow<Boolean> = _pulling.asStateFlow()

    private val _pullLog = MutableStateFlow<List<String>>(emptyList())
    val pullLog: StateFlow<List<String>> = _pullLog.asStateFlow()

    private val _pullProgress = MutableStateFlow(0f)
    val pullProgress: StateFlow<Float> = _pullProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _runningModel = MutableStateFlow<String?>(null)
    val runningModel: StateFlow<String?> = _runningModel.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _models.value = store.list()
            _disk.value = store.diskUsage()
        }
    }

    fun pull(spec: String, name: String?, onDone: (Boolean) -> Unit) {
        if (spec.isBlank()) { onDone(false); return }
        _pulling.value = true
        _pullLog.value = emptyList()
        _pullProgress.value = 0f
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                router.pull(spec, name, { ev ->
                    val msg = when (ev.phase) {
                        "download" -> if (ev.total > 0) {
                            _pullProgress.value = ev.downloaded.toFloat() / ev.total.toFloat()
                            "${ev.message}: ${FileUtils.humanReadableSize(ev.downloaded)} / ${FileUtils.humanReadableSize(ev.total)}"
                        } else "${ev.message}: ${FileUtils.humanReadableSize(ev.downloaded)}"
                        "done" -> "✓ ${ev.message}"
                        else -> "[${ev.phase}] ${ev.message}"
                    }
                    _pullLog.value = _pullLog.value + msg
                    LogStore.add("[pull] $msg")
                }, { false })
                refresh()
                onDone(true)
            } catch (e: Exception) {
                _error.value = e.message
                _pullLog.value = _pullLog.value + "✗ ${e.message}"
                LogStore.add("[pull] failed: ${e.message}")
                onDone(false)
            } finally {
                _pulling.value = false
            }
        }
    }

    fun delete(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(name)
            refresh()
            LogStore.add("[rm] $name")
        }
    }

    fun run(name: String, port: Int = InferenceService.DEFAULT_PORT) {
        val ctx = getApplication<MCApp>()
        val intent = Intent(ctx, InferenceService::class.java).apply {
            action = InferenceService.ACTION_LOAD
            putExtra(InferenceService.EXTRA_MODEL, name)
            putExtra(InferenceService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(ctx, intent)
        _runningModel.value = name
        LogStore.add("[run] $name :$port")
    }

    fun stop() {
        val ctx = getApplication<MCApp>()
        val intent = Intent(ctx, InferenceService::class.java).apply {
            action = InferenceService.ACTION_UNLOAD
        }
        ContextCompat.startForegroundService(ctx, intent)
        _runningModel.value = null
        LogStore.add("[stop] inference")
    }

    fun clearError() { _error.value = null }
}
