# IA-Android-17

Android-приложение с локальной нейросетью: **llama.cpp + Qwen3-8B Q4_K_M**, чат, документы, настройки ресурсов.

- Инструкция для пользователя: [INSTALL_RU.md](INSTALL_RU.md)
- Версия llama.cpp зафиксирована в `app/src/main/cpp/LLAMA_CPP_VERSION.txt`
- Сборка: GitHub Actions (`.github/workflows/android-debug.yml`) → артефакт `IA-Android-17-debug-apk`

## Структура
| Путь | Назначение |
|---|---|
| `app/src/main/cpp/ia_jni.cpp` | JNI-слой над llama.cpp: загрузка модели, потоковая генерация, повторное использование KV-кэша |
| `engine/` | Kotlin-обёртка и поток движка |
| `model/ModelManager.kt` | Загрузка с докачкой, SHA-256, импорт GGUF |
| `chat/` | Формат ChatML (Qwen3), обрезка по контексту, история |
| `docs/` | Извлечение текста (TXT/PDF/DOCX/…), разбиение на фрагменты, поиск BM25 |
| `ui/` | Экраны Compose и `AppViewModel` |
| `scripts/fetch-llama.*` | Скачивание исходников llama.cpp нужной версии |

## Локальная сборка
```
bash scripts/fetch-llama.sh      # или scripts\fetch-llama.ps1 в Windows
gradle :app:assembleDebug        # нужны JDK 17, Android SDK 35, NDK 27.2, CMake 3.22.1
```

## Дальше
1. Проверка на реальном устройстве, замер скорости, подбор потоков и контекста.
2. OCR для сканов, таблицы, архивы.
3. Эмбеддинги для RAG, долговременная память.
4. Управляющий слой: инструменты только через проверяемые разрешения.
