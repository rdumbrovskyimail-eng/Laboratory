# Contributing to OpusIDE

Thank you for your interest in contributing to OpusIDE! This document provides guidelines and instructions for contributing to the project.

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- JDK 21
- Android SDK API 36
- Git

### Setting Up the Development Environment

1. **Fork the repository**
   ```bash
   # Click "Fork" on GitHub, then clone your fork
   git clone https://github.com/YOUR_USERNAME/OpusIDE.git
   cd OpusIDE
Configure local properties
cp local.properties.example local.properties
# Edit local.properties and add your API keys
Open in Android Studio
Open Android Studio
Select "Open an Existing Project"
Navigate to the cloned repository
Wait for Gradle sync to complete
Create a feature branch
git checkout -b feature/your-feature-name
Code Style
Kotlin Conventions
Follow Kotlin Coding Conventions
Use meaningful variable and function names
Keep functions under 50 lines when possible
Add KDoc comments for public APIs
Example
/**
 * Saves a file to GitHub with conflict handling.
 *
 * @param path The file path in the repository
 * @param content The file content to save
 * @param commitMessage The commit message
 * @return Result indicating success or conflict
 */
suspend fun saveFile(
    path: String,
    content: String,
    commitMessage: String
): ConflictResult {
    // Implementation
}
Formatting
Indentation: 4 spaces (automatically configured via .editorconfig)
Line length: Maximum 120 characters
Imports: Remove unused imports
Trailing commas: Use them in Kotlin (enabled in .editorconfig)
Project Structure
app/src/main/java/com/opuside/app/
├── core/                   # Core functionality
│   ├── data/              # App settings
│   ├── database/          # Room database
│   ├── di/                # Dependency injection
│   ├── git/               # Git conflict resolution
│   ├── network/           # API clients
│   ├── security/          # Encryption & auth
│   └── ui/                # Shared UI components
└── feature/               # Feature modules
    ├── analyzer/          # Chat & analysis
    ├── creator/           # File editor
    └── settings/          # Settings screen
Testing
Running Tests
# Unit tests
./gradlew test

# Instrumented tests (requires emulator or device)
./gradlew connectedAndroidTest
Writing Tests
Write unit tests for ViewModels and business logic
Write UI tests for critical user flows
Aim for meaningful test coverage, not 100% coverage
Example Test
@Test
fun `saveFile should return conflict when remote changed`() = runTest {
    // Given
    val localContent = "local changes"
    val remoteContent = "remote changes"
    
    // When
    val result = gitConflictResolver.saveFileWithConflictHandling(
        path = "test.kt",
        localContent = localContent,
        currentSha = "old-sha",
        branch = "main",
        commitMessage = "Update test"
    )
    
    // Then
    assertTrue(result is ConflictResult.Conflict)
}
Commit Messages
Use Conventional Commits format:
<type>(<scope>): <description>

[optional body]

[optional footer]
Types
feat: New feature
fix: Bug fix
docs: Documentation changes
style: Code formatting (no functional changes)
refactor: Code refactoring
test: Adding or updating tests
chore: Build process or auxiliary tool changes
Examples
feat(analyzer): add streaming response support for Claude API

fix(creator): prevent memory leak in UndoRedoManager

docs(readme): update installation instructions

refactor(network): extract retry logic into separate function

test(git): add tests for conflict resolution
Pull Request Process
Update your branch
git fetch origin
git rebase origin/main
Ensure all tests pass
./gradlew test
Create a pull request
Provide a clear title and description
Reference any related issues (e.g., "Fixes #123")
Add screenshots for UI changes
Request review from maintainers
Address review comments
Make requested changes
Push new commits to your branch
Re-request review when ready
Squash commits (optional)
Maintainers may ask you to squash commits
git rebase -i origin/main
Code Review Guidelines
For Contributors
Be open to feedback
Respond to comments promptly
Ask questions if something is unclear
For Reviewers
Be respectful and constructive
Focus on code quality and maintainability
Approve when ready, request changes when needed
Reporting Bugs
When reporting bugs, please include:
Description: Clear description of the issue
Steps to reproduce: Step-by-step instructions
Expected behavior: What should happen
Actual behavior: What actually happens
Environment:
Android version
Device/emulator
OpusIDE version
Logs: Relevant logcat output
Screenshots: If applicable
Bug Report Template
**Description**
A clear description of the bug.

**Steps to Reproduce**
1. Go to '...'
2. Click on '...'
3. See error

**Expected Behavior**
What you expected to happen.

**Actual Behavior**
What actually happened.

**Environment**
- Android version: 14
- Device: Pixel 7
- OpusIDE version: 1.0.0

**Logs**
Paste relevant logs here
**Screenshots**
If applicable, add screenshots.
Feature Requests
We welcome feature requests! Please:
Check if the feature already exists or is planned
Clearly describe the feature and its use case
Explain why it would be valuable
Provide examples or mockups if possible
Questions?
Issues: Open an issue on GitHub
Discussions: Use GitHub Discussions for questions
Email: Contact the maintainers
License
By contributing to OpusIDE, you agree that your contributions will be licensed under the same license as the project (check LICENSE file).
Thank you for contributing to OpusIDE! 🚀
---

✅ **Файл 6/8 завершен!**

**Структура размещения:**
Laboratory-main/
├── .editorconfig
├── .gitignore
├── CONTRIBUTING.md        ← НОВЫЙ ФАЙЛ (создать здесь)
├── README.md
├── build.gradle.kts
├── local.properties.example
└── ...