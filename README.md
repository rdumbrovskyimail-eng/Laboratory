# 🚀 OpusIDE

[![Build Status](https://github.com/YOUR_USERNAME/OpusIDE/workflows/Android%20CI/badge.svg)](https://github.com/YOUR_USERNAME/OpusIDE/actions)
[![API](https://img.shields.io/badge/API-36%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=36)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-purple.svg?logo=kotlin)](http://kotlinlang.org)

**AI-Powered Mobile Development Environment for Android**

Умное приложение для работы с Claude Opus 4.5 API и GitHub на Samsung S23 Ultra.

---

## 📋 Требования

- **Android 16** (API 36)
- **Kotlin 2.1.0**
- **Android Studio Ladybug** или новее
- **JDK 21**

---

## 🛠 Настройка

### 1. Клонирование

```bash
git clone https://github.com/YOUR_USERNAME/OpusIDE.git
cd OpusIDE
2. API Ключи
Скопируйте local.properties.example в local.properties:
cp local.properties.example local.properties
Заполните свои ключи:
ANTHROPIC_API_KEY=sk-ant-api03-...
GITHUB_TOKEN=ghp_...
GITHUB_OWNER=your_username
GITHUB_REPO=your_repo
3. Сборка
./gradlew assembleDebug
🏗 Архитектура
OpusIDE/
├── app/
│   └── src/main/java/com/opuside/app/
│       ├── core/           # Общие компоненты
│       │   ├── di/         # Hilt модули
│       │   ├── network/    # Ktor клиенты (GitHub, Claude)
│       │   ├── database/   # Room (кеш, история)
│       │   └── ui/         # Theme, компоненты
│       ├── feature/        # Экраны
│       │   ├── creator/    # Окно 1: File Browser + Editor
│       │   ├── analyzer/   # Окно 2: Cache + Chat + Actions
│       │   └── settings/   # Настройки
│       └── navigation/     # Jetpack Navigation
📱 Экраны
🎨 Creator (Окно 1)
File Browser — навигация по репозиторию GitHub
Code Editor — редактирование с подсветкой синтаксиса
Git Actions — Commit, Push, Create Branch
🔬 Analyzer (Окно 2)
Cache Panel — до 20 файлов, таймер 5 минут
Claude Chat — streaming ответы в реальном времени
GitHub Actions — запуск workflows, просмотр логов
⚙️ Settings
API ключи (Anthropic, GitHub)
Настройки репозитория
Параметры кеша
🔧 Технологии
Компонент
Технология
UI
Jetpack Compose + Material 3
Network
Ktor 3.x + SSE Streaming
DI
Hilt
Database
Room
Async
Coroutines + Flow
Navigation
Jetpack Navigation Compose
🤝 Contributing
Contributions are welcome! Please read CONTRIBUTING.md for details on our code of conduct and the process for submitting pull requests.
📄 Лицензия
MIT License
👤 Автор
Ruslan — Android Developer