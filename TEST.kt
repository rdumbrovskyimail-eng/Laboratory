package com.opuside.app.test

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Тестовый файл для проверки AI Find & Replace
 * Содержит разные паттерны кода для тестирования
 */

// TODO: Добавить валидацию
// TODO: Добавить обработку ошибок
// TODO: Оптимизировать производительность

class TestViewModel : ViewModel() {
    
    // Изменяемые переменные для тестирования замены var на val
    private var _username = MutableStateFlow("")
    var username: StateFlow<String> = _username
    
    private var _email = MutableStateFlow("")
    var email: StateFlow<String> = _email
    
    private var _age = MutableStateFlow(0)
    var age: StateFlow<Int> = _age
    
    var isLoading = false
    var hasError = false
    var data: String? = null
    var count = 0
    
    // Функция для тестирования добавления аннотаций
    fun updateUsername(name: String) {
        viewModelScope.launch {
            _username.value = name
        }
    }
    
    fun updateEmail(newEmail: String) {
        viewModelScope.launch {
            _email.value = newEmail
        }
    }
    
    fun loadData() {
        isLoading = true
        Thread.sleep(1000)
        data = "Loaded data"
        isLoading = false
    }
    
    fun processUserData() {
        val result = data + " processed"
        count = count + 1
        return result
    }
}

// Composable функции БЕЗ аннотации @Composable (для теста добавления)
fun TestButton() {
    Button(onClick = {}) {
        Text("Click Me")
    }
}

fun TestCard() {
    Card(modifier = Modifier.padding(16.dp)) {
        Column {
            Text("Test Card")
            Text("Description")
        }
    }
}

fun TestList() {
    Column {
        TestButton()
        TestCard()
    }
}

// Data классы БЕЗ @Stable (для теста добавления)
data class UserData(val name: String, val age: Int)
data class AppConfig(val theme: String, val language: String)
data class ProfileSettings(val notifications: Boolean)
