# IA-Android-17

Локальный Android-помощник: **llama.cpp + Qwen3-8B Q4_K_M**. Основные вычисления выполняются на телефоне; веса модели скачиваются отдельно и не входят в APK.

- Инструкция: [INSTALL_RU.md](INSTALL_RU.md)
- Сборка: GitHub Actions → **Android Debug APK** → Artifacts → `IA-Android-17-debug-apk`
- Опциональная сборка OCR: запустите workflow вручную с параметром **Собрать с модулем OCR**; отдельный артефакт заканчивается на `-ocr`.
- Целевая архитектура: Android arm64, минимальная версия API 33 (Android 13).
- Версия приложения: 0.3.0 (debug-пакет использует идентификатор `com.iaandroid17.debug`).

## Функции текущей версии

- Чат с локальной GGUF-моделью Qwen3, история и настройки генерации.
- Импорт модели или загрузка с проверкой SHA-256.
- Работа с выбранными пользователем документами: извлечение доступного текста, анализ и поиск по фрагментам (RAG/BM25).
- Анализ некоторых файлов и метаданных; точные поддерживаемые форматы и ограничения приведены в [INSTALL_RU.md](INSTALL_RU.md).
- Фоновое голосовое ожидание запускается пользователем вручную и работает как foreground microphone service с постоянным уведомлением.
- При первой настройке задаётся имя помощника; вызов строится как «имя, код один/два/три».
- Встроенные тестовые сценарии: 1 — озвучить время, 2 — дату, 3 — произнести результат самопроверки. Количество коротких виброимпульсов соответствует номеру сценария; длительность регулируется в настройках.
- Варианты оформления: системная динамическая тема, индиго, лесная и закатная палитры. Круглая иконка с монограммой IA.
- Голосовой сервис зависит от установленного на Android движка распознавания. Предпочтение офлайн-режима передаётся движку, но офлайн-работа и непрерывное распознавание не гарантируются.
- OCR доступен только в отдельной сборке с соответствующим модулем.

## Архитектура

| Путь | Назначение |
|---|---|
| `app/src/main/cpp/ia_jni.cpp` | JNI-слой llama.cpp: загрузка модели и генерация |
| `app/src/main/java/com/iaandroid17/engine/` | Kotlin-обёртка движка |
| `model/ModelManager.kt` | Загрузка/импорт модели и проверка целостности |
| `chat/` | Форматирование диалога и история |
| `docs/` | Извлечение и организация документов, поиск |
| `agent/` | Планирование и исполнение ограниченных действий |
| `files/` | Анализ выбранных файлов |
| `voice/` | Речь и озвучивание |
| `ui/` | Compose-интерфейс и состояние приложения |
| `scripts/fetch-llama.*` | Получение зафиксированной версии llama.cpp |

## Сборка

Требуются JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1 и Gradle 8.9.

```bash
bash scripts/fetch-llama.sh
gradle --no-daemon :app:assembleDebug
```

CI выполняет сборку APK на GitHub Actions. Успешная компиляция не заменяет проверку на физическом устройстве.

## Безопасность и ограничения

- По умолчанию сетевой доступ приложения отключён; включайте его только для загрузки модели/необходимых данных.
- Выбирайте только те файлы, которые действительно нужны приложению.
- Accessibility даёт агенту расширенные возможности взаимодействия с экраном. Не включайте его без необходимости; проверяйте каждое предложенное действие.
- Не поручайте агенту ввод паролей, подтверждение платежей или другие критические действия.
- Модель может ошибаться; ответ агента не является гарантией корректного выполнения.
- Текстовая Qwen3-8B не понимает содержимое изображений, аудио и видео как мультимодальная модель. OCR/метаданные не заменяют vision/ASR.
- Перед повседневным использованием отдельно проверьте загрузку модели, генерацию, импорт файлов, речь и доступность функций агента на своём устройстве.

## Следующие этапы

1. Тестирование на реальном устройстве и профилирование производительности/нагрева.
2. Полноценная проверка безопасности агентских сценариев и тесты.
3. Сценарный редактор и строго ограниченный планировщик действий.
4. Проверка жизненного цикла microphone foreground service и работы при блокировке экрана на поддерживаемых устройствах.


## Голосовой профиль владельца (предварительный модуль)

- Можно записать три голосовых образца в настройках.
- Файлы образцов шифруются AES-GCM ключом Android Keystore и сохраняются в no-backup каталоге приложения.
- Доступно удаление профиля и ключа, после чего возможна новая регистрация.
- **Voice ID/speaker verification не реализован.** Сбор и шифрование образцов не являются идентификацией владельца. Поэтому голосовые коды распознаются, но сценарии fail-closed блокируются с голосовым уведомлением до подключения и валидации модели проверки говорящего.
- Распознавание фразы «имя помощника + код» само по себе не предотвращает воспроизведение или синтез голоса.


## Voice ID security gate (current branch)

**Security mode: chat only. Voice command execution is disabled**, both while unlocked and while locked, until a real owner speaker verifier is integrated, validated, and tested. The foreground service now fails closed before opening a recognition session.

The owner setup screen can collect three enrollment recordings encrypted with AES-GCM using an Android Keystore key. These clips are enrollment material only; they are **not** speaker embeddings and do not establish identity.

| # | Requirement | Current status |
|---:|---|---|
| 1 | Speaker embedding extraction | Not implemented |
| 2 | Compare live voice to owner profile | Not implemented |
| 3 | Calibrated accept/reject threshold | Not calibrated |
| 4 | Recording-quality validation | Not implemented |
| 5 | Replay/synthetic voice detection | Not implemented |
| 6 | Owner-confirmed profile reset/re-enrollment | System biometric/device credential confirmation is present for reset |
| 7 | Audit decisions/failures and resist repeated attempts | Bounded local audit log added; attempt rate-limiter not integrated |

The log avoids saving audio and recognized phrases. A successful enrollment or name match must never be interpreted as successful identity verification.

Before unlocking voice actions, integrate a dedicated on-device speaker-verification model and validate thresholds against false-accept/false-reject measurements, noisy and distant speech, playback attacks, synthetic speech, and real handset microphones. Keep consequential actions separately confirmed even after Voice ID passes.
