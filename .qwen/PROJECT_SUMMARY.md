The user wants me to create a comprehensive project summary based on the conversation history. Let me analyze what was discussed and accomplished:

1. **First task**: Added retry mechanism to OpenAiMarkupApi.kt for failed requests
   - Added MAX_RETRIES = 3 with exponential backoff (1s → 2s → 4s)
   - Added kotlinx.coroutines.delay import
   - Modified sendChat method to retry on failures

2. **Second task**: User asked about logs with Russian characters causing encoding issues in terminal
   - This was mentioned but not completed (user canceled the edit)

3. **Third task**: Fixed folderId dialog appearing for non-Yandex providers
   - The issue was that showFolderIdDialog was shown for all LLM providers
   - Fixed in MainViewModel.kt - removed the premature showFolderIdDialog = true before launchAutoMarkupWithLlm
   - Now folderId dialog only shows for Yandex Cloud (when llmConfig.isConfigured == false)

Let me create a comprehensive summary in Russian (as per the output language preference).# Project Summary

## Overall Goal
Разработка приложения TTS-Story-Helper для разметки текста и синтеза речи через Yandex SpeechKit и LLM-провайдеры (Yandex GPT, OpenAI-совместимые API)

## Key Knowledge
- **Технологии**: Kotlin, Compose Desktop, Ktor Client, kotlinx.coroutines, kotlinx.serialization
- **Сборка**: Gradle Kotlin DSL (`build.gradle.kts`)
- **Архитектура**: Разделение на `data` (API клиенты), `domain` (бизнес-логика), `ui` (ViewModel + Compose)
- **LLM провайдеры**: 
  - `LlmProvider.YandexCloud` — требует `folderId`, `iamToken`, модель в формате `gpt://folderId/model/latest`
  - Другие (OpenAI-совместимые) — требуют `apiKey` и `baseUrl`
- **TTS бэкенды**: Cloud (Yandex SpeechKit) и Local (локальный сервер)
- **Пользовательские предпочтения**:
  - Логи в консоли должны быть на **английском** (проблема с кодировкой кириллицы в терминале)
  - Механизм повторных попыток для сетевых запросов к LLM

## Recent Actions

### 1. [DONE] Добавлен retry-механизм для LLM запросов
**Файл**: `OpenAiMarkupApi.kt`
- Добавлены константы `MAX_RETRIES = 3`, `INITIAL_RETRY_DELAY_MS = 1000L`
- Метод `sendChat` теперь выполняет до 3 попыток с экспоненциальной задержкой (1с → 2с → 4с)
- Логирование номера попытки и статуса ответа
- При исчерпании попыток бросается последнее сохранённое исключение

### 2. [DONE] Исправлено отображение dialog для folderId
**Файл**: `MainViewModel.kt`
- **Проблема**: Диалог ввода `folderId` показывался для всех LLM-провайдеров, включая OpenAI
- **Решение**: Удалена строка `showFolderIdDialog = true` перед вызовом `launchAutoMarkupWithLlm`
- Теперь диалог появляется только для Yandex Cloud (когда `llmConfig.isConfigured == false`)

### 3. [CANCELED] Перевод логов на английский
- Пользователь отменил редактирование `SpeechKitApi.kt`
- Требуется перевести все `println` и `emit` сообщения с русского на английский

## Current Plan

| # | Задача | Статус |
|---|--------|--------|
| 1 | Retry-механизм для OpenAiMarkupApi | [DONE] |
| 2 | Исправление dialog folderId для не-Yandex провайдеров | [DONE] |
| 3 | Перевод консольных логов на английский | [TODO] |
| 4 | Добавить retry для SpeechKitApi (при необходимости) | [TODO] |

## Files Modified
- `composeApp/src/jvmMain/kotlin/by/tigre/speechhelper/data/OpenAiMarkupApi.kt` — retry-логика
- `composeApp/src/jvmMain/kotlin/by/tigre/speechhelper/ui/MainViewModel.kt` — исправление dialog

---

## Summary Metadata
**Update time**: 2026-03-29T07:28:56.861Z 
