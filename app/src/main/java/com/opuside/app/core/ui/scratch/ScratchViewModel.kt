package com.opuside.app.feature.scratch.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.feature.scratch.data.ScratchRepository
import com.opuside.app.feature.scratch.data.local.ScratchEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScratchViewModel @Inject constructor(
    private val repository: ScratchRepository
) : ViewModel() {

    // Текст в основном поле
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    // Сообщение-тост (например "Сохранено!", "Скопировано!")
    private val _snackMessage = MutableStateFlow<String?>(null)
    val snackMessage: StateFlow<String?> = _snackMessage.asStateFlow()

    // Список сохранённых записей
    val records: StateFlow<List<ScratchEntity>> = repository.getAllRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onTextChange(value: String) {
        _text.value = value
    }

    fun clear() {
        _text.value = ""
    }

    fun save() {
        val content = _text.value.trim()
        if (content.isEmpty()) {
            _snackMessage.value = "Нечего сохранять"
            return
        }
        viewModelScope.launch {
            repository.save(content)
            _snackMessage.value = "Сохранено!"
        }
    }

    fun loadRecord(record: ScratchEntity) {
        _text.value = record.content
    }

    fun deleteRecord(record: ScratchEntity) {
        viewModelScope.launch {
            repository.delete(record)
        }
    }

    fun snackShown() {
        _snackMessage.value = null
    }
}
