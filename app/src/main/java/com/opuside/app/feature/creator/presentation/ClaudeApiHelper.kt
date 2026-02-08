package com.opuside.app.feature.creator.presentation

import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class Message(
    val role: String,
    val content: String
)

@Serializable
data class ClaudeRequest(
    val model: String,
    val max_tokens: Int,
    val messages: List<Message>
)

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null
)

@Serializable
data class ClaudeResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<ContentBlock>? = null,
    val model: String? = null,
    val stop_reason: String? = null,
    val usage: Usage? = null
)

@Serializable
data class Usage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null
)

class ClaudeApiHelper : JFrame("Claude API Helper") {
    private val apiKeyField = JPasswordField(40)
    private val filePathField = JTextField(40)
    private val fileContentArea = JTextArea(5, 50)
    private val queryArea = JTextArea(5, 50)
    private val responseArea = JTextArea(20, 50)
    private val statusLabel = JLabel("Готов к работе")
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private var conversationHistory = mutableListOf<Message>()

    init {
        setupUI()
        defaultCloseOperation = DISPOSE_ON_CLOSE  // Изменено с EXIT_ON_CLOSE
        pack()
        setLocationRelativeTo(null)
        isVisible = true
    }

    private fun setupUI() {
        layout = BorderLayout(10, 10)
        (contentPane as JPanel).border = EmptyBorder(15, 15, 15, 15)

        // Панель API ключа
        val apiPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        apiPanel.add(JLabel("API ключ:"))
        apiPanel.add(apiKeyField)
        
        // Панель файла
        val filePanel = JPanel(BorderLayout(5, 5))
        filePanel.border = BorderFactory.createTitledBorder("Файл с кодом (до 1 МБ)")
        
        val fileTopPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        fileTopPanel.add(JLabel("Путь:"))
        fileTopPanel.add(filePathField)
        
        val browseButton = JButton("Обзор...")
        browseButton.addActionListener { browseFile() }
        fileTopPanel.add(browseButton)
        
        val loadButton = JButton("Загрузить")
        loadButton.addActionListener { loadFile() }
        fileTopPanel.add(loadButton)
        
        filePanel.add(fileTopPanel, BorderLayout.NORTH)
        
        fileContentArea.isEditable = false
        fileContentArea.lineWrap = true
        val fileScroll = JScrollPane(fileContentArea)
        filePanel.add(fileScroll, BorderLayout.CENTER)

        // Панель запроса
        val queryPanel = JPanel(BorderLayout(5, 5))
        queryPanel.border = BorderFactory.createTitledBorder("Ваш запрос")
        
        queryArea.lineWrap = true
        queryArea.wrapStyleWord = true
        val queryScroll = JScrollPane(queryArea)
        queryPanel.add(queryScroll, BorderLayout.CENTER)
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        
        val sendButton = JButton("Отправить запрос")
        sendButton.addActionListener { sendQuery() }
        buttonPanel.add(sendButton)
        
        val clearHistoryButton = JButton("Очистить историю")
        clearHistoryButton.addActionListener { 
            conversationHistory.clear()
            responseArea.text = "История диалога очищена\n"
            statusLabel.text = "История очищена"
        }
        buttonPanel.add(clearHistoryButton)
        
        queryPanel.add(buttonPanel, BorderLayout.SOUTH)

        // Панель ответа
        val responsePanel = JPanel(BorderLayout(5, 5))
        responsePanel.border = BorderFactory.createTitledBorder("Ответ Claude")
        
        responseArea.isEditable = false
        responseArea.lineWrap = true
        responseArea.wrapStyleWord = true
        val responseScroll = JScrollPane(responseArea)
        responsePanel.add(responseScroll, BorderLayout.CENTER)
        
        val copyButton = JButton("Скопировать ответ")
        copyButton.addActionListener { copyResponse() }
        responsePanel.add(copyButton, BorderLayout.SOUTH)

        // Статус
        statusLabel.border = EmptyBorder(5, 0, 0, 0)

        // Компоновка
        val topPanel = JPanel(BorderLayout(5, 5))
        topPanel.add(apiPanel, BorderLayout.NORTH)
        topPanel.add(filePanel, BorderLayout.CENTER)

        val centerPanel = JPanel(BorderLayout(5, 5))
        centerPanel.add(queryPanel, BorderLayout.NORTH)
        centerPanel.add(responsePanel, BorderLayout.CENTER)

        add(topPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun browseFile() {
        val chooser = JFileChooser()
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "Text files", "txt", "kt", "java", "py", "js", "cpp", "c", "h"
        )
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            filePathField.text = chooser.selectedFile.absolutePath
        }
    }

    private fun loadFile() {
        val path = filePathField.text.trim()
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажите путь к файлу", "Ошибка", JOptionPane.ERROR_MESSAGE)
            return
        }

        try {
            val file = File(path)
            if (!file.exists()) {
                JOptionPane.showMessageDialog(this, "Файл не найден", "Ошибка", JOptionPane.ERROR_MESSAGE)
                return
            }

            val sizeInMB = file.length() / (1024.0 * 1024.0)
            if (sizeInMB > 1.0) {
                JOptionPane.showMessageDialog(
                    this, 
                    "Файл слишком большой: %.2f МБ (макс 1 МБ)".format(sizeInMB), 
                    "Ошибка", 
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }

            val content = file.readText()
            fileContentArea.text = content
            statusLabel.text = "Загружен файл: ${file.name} (%.2f МБ)".format(sizeInMB)
            
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Ошибка чтения файла: ${e.message}", "Ошибка", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun sendQuery() {
        val apiKey = String(apiKeyField.password).trim()
        if (apiKey.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите API ключ", "Ошибка", JOptionPane.ERROR_MESSAGE)
            return
        }

        val query = queryArea.text.trim()
        if (query.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите запрос", "Ошибка", JOptionPane.ERROR_MESSAGE)
            return
        }

        statusLabel.text = "Отправка запроса..."
        responseArea.text = "Ожидание ответа...\n"

        Thread {
            try {
                // Формируем сообщение
                var userMessage = query
                val fileContent = fileContentArea.text.trim()
                
                if (fileContent.isNotEmpty()) {
                    userMessage = "Вот код из файла:\n\n```\n$fileContent\n```\n\n$query"
                }

                // Добавляем в историю
                conversationHistory.add(Message("user", userMessage))

                // Создаем запрос
                val request = ClaudeRequest(
                    model = "claude-opus-4-6",
                    max_tokens = 128000,
                    messages = conversationHistory.toList()
                )

                val requestBody = json.encodeToString(request)

                val client = HttpClient.newBuilder().build()
                val httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

                val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())

                SwingUtilities.invokeLater {
                    if (response.statusCode() == 200) {
                        val claudeResponse = json.decodeFromString<ClaudeResponse>(response.body())
                        val responseText = claudeResponse.content?.firstOrNull()?.text ?: "Нет ответа"
                        
                        // Добавляем ответ в историю
                        conversationHistory.add(Message("assistant", responseText))
                        
                        responseArea.text = responseText
                        
                        val tokens = claudeResponse.usage
                        statusLabel.text = "Успешно! Входных токенов: ${tokens?.input_tokens}, Выходных: ${tokens?.output_tokens}"
                    } else {
                        responseArea.text = "Ошибка ${response.statusCode()}:\n${response.body()}"
                        statusLabel.text = "Ошибка запроса"
                    }
                }

            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    responseArea.text = "Исключение: ${e.message}\n${e.stackTraceToString()}"
                    statusLabel.text = "Ошибка: ${e.message}"
                }
            }
        }.start()
    }

    private fun copyResponse() {
        val text = responseArea.text
        if (text.isNotEmpty()) {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            statusLabel.text = "Ответ скопирован в буфер обмена"
        }
    }
}