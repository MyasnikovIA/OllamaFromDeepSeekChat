# FakeOllamaFromDeepSeekChat — справочник по HTTP API

Документ описывает **все** эндпоинты сервера, форматы запросов/ответов и действия, которые они выполняют. Разбит на четыре группы:

1. **Ollama-совместимый API** (`/api/*`, `/`) — эмуляция Ollama.
2. **OpenAI-совместимый API** (`/v1/*`).
3. **Bridge API** — служебные эндпоинты расширения (`/events`, `/log`, `/tabs`).
4. **Public API** — пользовательские эндпоинты команд и серверного AJAX (`/api/command`, `/api/ajax`).

Все ответы — JSON, CORS разрешён (`Access-Control-Allow-Origin: *`), если не указано иное.

---

## 0. Общие сведения

| Параметр | Значение |
|---|---|
| Базовый URL | `http://localhost:11434` |
| Порт | `port` из `config.json` (по умолчанию `11434`) |
| Content-Type запросов | `application/json` (кроме SSE) |
| Content-Type ответов | `application/json; charset=utf-8`, кроме `/`, `/events`, `/api/ajax/events` |
| CORS | `Access-Control-Allow-Origin: *`, методы `GET, POST, OPTIONS` |
| Модель по умолчанию | `modelName` из `config.json` (по умолчанию `DeepSeekCloud`) |

Все запросы `POST` могут содержать `OPTIONS` preflight — сервер отвечает `204 No Content`.

---

## 1. Ollama-совместимый API

### 1.1. `GET /` — проверка живости

**Действие:** возвращает текстовую строку, что сервер работает.

```http
GET /
```

**Ответ:** `200 text/plain; charset=utf-8`

```
Ollama is running
```

---

### 1.2. `GET /api/version` — версия

**Действие:** возвращает фиктивную версию Ollama (для совместимости клиентов).

```http
GET /api/version
```

**Ответ:**

```json
{ "version": "0.1.0" }
```

---

### 1.3. `GET /api/tags` — список моделей

**Действие:** возвращает одну «модель» — `modelName` из конфига.

```http
GET /api/tags
```

**Ответ:**

```json
{
  "models": [
    {
      "name": "DeepSeekCloud",
      "model": "DeepSeekCloud",
      "modified_at": "2024-01-01T00:00:00Z",
      "size": 0,
      "digest": "sha256:0000...0000",
      "details": {
        "format": "gguf",
        "family": "deepseek",
        "families": ["deepseek"],
        "parameter_size": "0B",
        "quantization_level": "Q4_K_M"
      }
    }
  ]
}
```

---

### 1.4. `GET /api/ps` — запущенные модели

**Действие:** всегда возвращает пустой список (моделей в памяти нет).

**Ответ:**

```json
{ "models": [] }
```

---

### 1.5. `POST /api/chat` — чат (основной эндпоинт)

**Действие:**
1. Берёт последнее `user`-сообщение из `messages`.
2. Если пользовательских сообщений ≤ 1, а в истории уже что-то есть — считает это **новым диалогом**: чистит историю и шлёт в браузер команду `newChat`.
3. Отправляет текст (и вложения, если есть) в активную вкладку DeepSeek через `BridgeCommands.sendChatStream`.
4. **Стримит** ответ DeepSeek обратно в формате NDJSON (или ждёт полного ответа, если `stream:false`).

**Запрос:**

```json
{
  "model": "DeepSeekCloud",
  "messages": [
    { "role": "system", "content": "Ты — помощник." },
    { "role": "user",   "content": "Что такое ИИ?" }
  ],
  "stream": true
}
```

`messages[].content` может быть строкой или массивом частей (OpenAI-стиль):

```json
{
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "text", "text": "Опиши изображение" },
        { "type": "image_url", "image_url": { "url": "data:image/png;base64,iVBORw0..." } }
      ]
    }
  ]
}
```

Поддерживаемые типы частей: `text`, `input_text`, `image_url`, `input_image`, `image` (с `source.type="base64"`), `file` (с `dataBase64`).

**Ответ при `stream:true`** — `application/x-ndjson`, чанки:

```json
{"model":"DeepSeekCloud","created_at":"2024-...","message":{"role":"assistant","content":"ИИ —"},"done":false}
{"model":"DeepSeekCloud","created_at":"2024-...","message":{"role":"assistant","content":" это "},"done":false}
{"model":"DeepSeekCloud","created_at":"2024-...","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":0,"prompt_eval_count":0,"eval_count":0}
```

**Ответ при `stream:false`** — один JSON:

```json
{
  "model": "DeepSeekCloud",
  "created_at": "2024-...",
  "message": { "role": "assistant", "content": "полный текст ответа" },
  "done": true,
  "done_reason": "stop",
  "total_duration": 0,
  "prompt_eval_count": 0,
  "eval_count": 0
}
```

**Ошибки:**

```json
{ "error": "no user content (text or images) in 'messages'" }   // 400
{ "error": "invalid json" }                                     // 400
{ "error": "watchdog timeout" }                                 // 500, если браузер не ответил 120 с
```

---

### 1.6. `POST /api/generate` — генерация по `prompt`

**Действие:** то же, что `/api/chat`, но вход — верхнеуровневый `prompt` (и опционально `images`).

**Запрос:**

```json
{
  "model": "DeepSeekCloud",
  "prompt": "Что такое ИИ?",
  "stream": true
}
```

**Ответ при `stream:true`** — NDJSON:

```json
{"model":"DeepSeekCloud","created_at":"...","response":"ИИ —","done":false}
{"model":"DeepSeekCloud","created_at":"...","response":"","done":true,"done_reason":"stop","total_duration":0,"prompt_eval_count":0,"eval_count":0}
```

**Ответ при `stream:false`:**

```json
{
  "model": "DeepSeekCloud",
  "created_at": "...",
  "response": "полный текст",
  "done": true,
  "done_reason": "stop",
  "total_duration": 0,
  "prompt_eval_count": 0,
  "eval_count": 0
}
```

---

### 1.7. `POST /api/show` — информация о модели

**Действие:** возвращает статичную информацию о «модели».

```http
POST /api/show
Content-Type: application/json

{}
```

**Ответ:**

```json
{
  "modelfile": "FROM DeepSeekCloud",
  "parameters": "",
  "template": "",
  "license": "",
  "details": {
    "format": "gguf",
    "family": "deepseek",
    "families": ["deepseek"],
    "parameter_size": "0B",
    "quantization_level": "Q4_K_M"
  },
  "model_info": { "general.architecture": "deepseek" }
}
```

---

### 1.8. `POST /api/embed` — эмбеддинги (заглушка)

**Действие:** возвращает вектор `[0.0]` для каждого входа.

**Запрос:**

```json
{ "model": "DeepSeekCloud", "input": ["текст1", "текст2"] }
```

**Ответ:**

```json
{
  "model": "DeepSeekCloud",
  "embeddings": [[0.0], [0.0]]
}
```

---

### 1.9. `POST /api/pull` — pull модели (заглушка)

**Действие:** отдаёт два NDJSON-события: `pulling manifest` → `success`.

```json
{"status":"pulling manifest"}
{"status":"success"}
```

---

### 1.10. `POST /api/push` — push модели (заглушка)

**Ответ:**

```json
{ "status": "success" }
```

---

### 1.11. `POST /api/create` — create модели (заглушка)

**Ответ** — NDJSON:

```json
{"status":"success"}
```

---

### 1.12. `POST /api/copy` — копирование (не поддерживается)

**Ответ:** `404`

```json
{ "error": "model copy not supported" }
```

---

### 1.13. `POST /api/delete` — удаление (заглушка)

**Ответ:**

```json
{ "status": "success" }
```

---

## 2. OpenAI-совместимый API (`/v1/*`)

### 2.1. `GET /v1/models` — список моделей

**Ответ:**

```json
{
  "object": "list",
  "data": [
    {
      "id": "DeepSeekCloud",
      "object": "model",
      "created": 1704067200,
      "owned_by": "miacomsoft"
    }
  ]
}
```

---

### 2.2. `GET /v1/models/{id}` — модель по id

**Ответ при `id = modelName`:**

```json
{
  "id": "DeepSeekCloud",
  "object": "model",
  "created": 1704067200,
  "owned_by": "miacomsoft"
}
```

**Иначе:** `404 { "error": "model not found: ..." }`

---

### 2.3. `POST /v1/chat/completions` — чат OpenAI

**Действие:** как `/api/chat`, но в формате OpenAI. Стрим — SSE (`text/event-stream`), не NDJSON.

**Запрос:**

```json
{
  "model": "DeepSeekCloud",
  "messages": [{ "role": "user", "content": "Привет" }],
  "stream": false
}
```

**Ответ при `stream:false`:**

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1704067200,
  "model": "DeepSeekCloud",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "полный текст" },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0 }
}
```

**Ответ при `stream:true`** — SSE:

```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"DeepSeekCloud","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"DeepSeekCloud","choices":[{"index":0,"delta":{"content":"ИИ —"},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"DeepSeekCloud","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

**Ошибки** приходят как SSE-чанк:

```
data: {"error":{"message":"..."}}
data: [DONE]
```

---

### 2.4. `POST /v1/completions` — legacy completions

**Запрос:**

```json
{ "model": "DeepSeekCloud", "prompt": "Что такое ИИ?", "stream": false }
```

**Ответ при `stream:false`:**

```json
{
  "id": "cmpl-...",
  "object": "text_completion",
  "created": 1704067200,
  "model": "DeepSeekCloud",
  "choices": [{ "index": 0, "text": "полный текст", "finish_reason": "stop" }],
  "usage": { "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0 }
}
```

**При `stream:true`** — SSE по аналогии с `/v1/chat/completions`, но `object: "text_completion"` и в `choices` поле `text` (не `delta`).

---

### 2.5. `POST /v1/embeddings` — эмбеддинги OpenAI (заглушка)

**Запрос:**

```json
{ "model": "DeepSeekCloud", "input": ["a", "b"] }
```

**Ответ:**

```json
{
  "object": "list",
  "data": [
    { "object": "embedding", "index": 0, "embedding": [0.0] },
    { "object": "embedding", "index": 1, "embedding": [0.0] }
  ],
  "model": "DeepSeekCloud",
  "usage": { "prompt_tokens": 0, "total_tokens": 0 }
}
```

---

## 3. Bridge API (служебные эндпоинты расширения)

> Используются **только расширением Chrome**. Для внешних клиентов обычно не нужны, но описаны для полноты.

### 3.1. `GET /events?tabId=<id>` — SSE-канал вкладки

**Действие:** вкладка подключается, регистрируется в `TabRegistry`; сервер шлёт ей команды через эту очередь.

**Ответ:** `200 text/event-stream`, события:

```
: connected

data: {"type":"send","text":"..."}

data: {"type":"newChat"}

data: {"type":"openTab","url":"...","active":true}

: ping
```

---

### 3.2. `POST /log` — события от вкладки

**Действие:** вкладка присылает сюда `stream`-чанки, `hello`, `bye`, `mode-state`, `result`, `error`, `tab-op`. Сервер маршрутизирует их в `CommandDispatcher`/`TabRegistry`.

**Запрос:**

```json
{ "level": "stream", "tabId": "tab-...", "streamId": "s...", "streamType": "RESPONSE", "text": "..." }
```

```json
{ "level": "hello", "tabId": "tab-...", "text": "{\"url\":\"...\",\"ua\":\"...\"}" }
```

```json
{ "level": "bye", "tabId": "tab-...", "reason": "pagehide" }
```

**Ответ:** `200 { "ok": true }`

---

### 3.3. `GET /tabs` — список вкладок

**Ответ:**

```json
{
  "count": 2,
  "tabs": [
    {
      "tabId": "tab-...",
      "info": "{\"url\":\"https://chat.deepseek.com/\",\"ua\":\"...\"}",
      "thinking": false,
      "smartSearch": true,
      "tokenExhausted": false,
      "selected": true
    },
    {
      "tabId": "tab-...",
      "info": "...",
      "thinking": true,
      "smartSearch": false,
      "tokenExhausted": false,
      "selected": false
    }
  ]
}
```

---

## 4. Public API (пользовательские эндпоинты)

### 4.1. `POST /api/command` — универсальная команда

**Действие:** принимает JSON-команду, ставит её в очередь `CommandDispatcher`, ждёт результата от вкладки, возвращает JSON. Полностью соответствует командам stdin (`send`, `!`, `/new`, `/think on`, …), только по HTTP.

**Формат запроса:**

```json
{
  "type":  "…",
  "text":  "…",
  "code":  "…",
  "url":   "…",
  "tabId": "…",
  "path":  "…"
}
```

Поле `type` — обязательно. Остальные зависят от команды.

#### Поддерживаемые `type`

| `type` | Дополнительные поля | Действие | Ждёт ответа? |
|---|---|---|---|
| `send` | `text` | `sendToDeepSeek(text)` в активной вкладке | ✅ (`done`) |
| `eval` | `code` | `eval(code)` в консоли страницы | ✅ (результат) |
| `newChat` | — | создать новый чат | ✅ |
| `enableThinking` | — | включить «Глубокое мышление» | ✅ |
| `disableThinking` | — | выключить «Глубокое мышление» | ✅ |
| `enableSmartSearch` | — | включить «Умный поиск» | ✅ |
| `disableSmartSearch` | — | выключить «Умный поиск» | ✅ |
| `stopGeneration` | — | остановить генерацию | ✅ |
| `resumeGeneration` | — | продолжить генерацию | ✅ |
| `openTab` | `url?`, `active?` | открыть новую вкладку DeepSeek | ⏩ queued |
| `closeTab` | `tabId` | закрыть вкладку | ⏩ queued |
| `closeAllTabs` | — | закрыть все `chat.deepseek.com/*` | ⏩ queued |
| `tabs` | — | список вкладок (то же, что `/tabs`) | ✅ сразу |
| `state` | — | режимы активной вкладки | ✅ сразу |
| `tab` | `tabId` (`#n`, `id`, `prefix`) | выбрать вкладку | ✅ сразу |
| `file` | `path`, `text?` | отправить файл (fire-and-forget) | ⏩ queued |
| `help` | — | список всех команд | ✅ сразу |

**Ответ (успех):**

```json
{ "ok": true, "result": { "done": true } }
```

**Ответ (`tabs`):**

```json
{ "ok": true, "result": { "count": 1, "tabs": [ … ] } }
```

**Ответ (`state`):**

```json
{
  "ok": true,
  "result": {
    "active": {
      "tabId": "tab-...",
      "info": "...",
      "thinking": false,
      "smartSearch": true,
      "tokenExhausted": false
    }
  }
}
```

**Ответ (`help`):** описание эндпоинтов и команд.

**Ошибки:**

```json
{ "ok": false, "error": "empty 'text'" }          // 400
{ "ok": false, "error": "unknown type: foo" }     // 400
{ "ok": false, "error": "timeout waiting ..." }   // 504
{ "ok": false, "error": "send failed: ..." }      // 500
```

#### Примеры

```bash
# отправить текст
curl -X POST http://localhost:11434/api/command \
  -H "Content-Type: application/json" \
  -d '{"type":"send","text":"Что такое ИИ?"}'

# выполнить JS в консоли страницы
curl -X POST http://localhost:11434/api/command \
  -H "Content-Type: application/json" \
  -d '{"type":"eval","code":"document.title"}'

# включить режим размышления
curl -X POST http://localhost:11434/api/command \
  -H "Content-Type: application/json" \
  -d '{"type":"enableThinking"}'

# список вкладок
curl -X POST http://localhost:11434/api/command \
  -H "Content-Type: application/json" \
  -d '{"type":"tabs"}'

# открыть новую вкладку
curl -X POST http://localhost:11434/api/command \
  -H "Content-Type: application/json" \
  -d '{"type":"openTab","url":"https://chat.deepseek.com/","active":true}'

# закрыть вкладку
curl -X POST http://localhost:11434/api/command \
  -H "Content-Type: application/json" \
  -d '{"type":"closeTab","tabId":"tab-44e0857b-..."}'

# закрыть все вкладки DeepSeek
curl -X POST http://localhost:11434/api/command \
  -H "Content-Type: application/json" \
  -d '{"type":"closeAllTabs"}'
```

---

### 4.2. `POST /api/ajax` — серверный AJAX-мост

**Действие:** сервер **сам** выполняет HTTP-запрос через `java.net.http.HttpClient` и возвращает ответ клиенту. Браузер и расширение **не участвуют**. CORS разрешён — можно вызывать из любого origin.

> ⚠️ Важно: запросы выполняются **от имени сервера**, а не вкладки. Куки/сессия DeepSeek не используются. С осторожностью — сервер может ходить на произвольные URL (SSRF). Не открывайте порт наружу без reverse-proxy/авторизации.

**Запрос:**

```json
{
  "url":       "https://api.github.com/zen",
  "method":    "GET",
  "headers":   { "Accept": "text/plain" },
  "body":      "",
  "timeoutMs": 60000
}
```

| Поле | Обяз. | По умолч. | Описание |
|---|---|---|---|
| `url` | ✅ | — | Абсолютный URL |
| `method` | ❌ | `GET` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD` |
| `headers` | ❌ | `{}` | Объект заголовков |
| `body` | ❌ | `""` | Тело (для POST/PUT/PATCH) |
| `timeoutMs` | ❌ | `60000` | Таймаут запроса |

**Ответ (успех):**

```json
{
  "ok": true,
  "result": {
    "status": 200,
    "headers": {
      "content-type": "text/plain; charset=utf-8",
      "server": "GitHub.com"
    },
    "body": "Keep it logically awesome.",
    "url": "https://api.github.com/zen"
  }
}
```

**Ответ при ошибке сети/таймауте:** `502`

```json
{ "ok": false, "error": "ajax failed: connect timed out" }
```

**Ответ при неверных входных данных:** `400`

```json
{ "ok": false, "error": "empty 'url'" }
```

```json
{ "ok": false, "error": "invalid url: ..." }
```

**Примеры:**

```bash
# GET
curl -X POST http://localhost:11434/api/ajax \
  -H "Content-Type: application/json" \
  -d '{"url":"https://api.github.com/zen"}'

# POST JSON
curl -X POST http://localhost:11434/api/ajax \
  -H "Content-Type: application/json" \
  -d '{
        "url":"https://httpbin.org/post",
        "method":"POST",
        "headers":{"Content-Type":"application/json"},
        "body":"{\"hello\":\"world\"}"
      }'

# HEAD
curl -X POST http://localhost:11434/api/ajax \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com","method":"HEAD"}'
```

---

## 5. Сводная таблица эндпоинтов

| Метод | Путь | Назначение | Кто исполняет |
|---|---|---|---|
| `GET` | `/` | проверка живости | сервер |
| `GET` | `/api/version` | версия Ollama | сервер |
| `GET` | `/api/tags` | список моделей | сервер |
| `GET` | `/api/ps` | запущенные модели | сервер |
| `POST` | `/api/chat` | чат Ollama | вкладка DeepSeek |
| `POST` | `/api/generate` | генерация Ollama | вкладка DeepSeek |
| `POST` | `/api/show` | информация о модели | сервер |
| `POST` | `/api/embed` | эмбеддинги (заглушка) | сервер |
| `POST` | `/api/pull` | pull (заглушка) | сервер |
| `POST` | `/api/push` | push (заглушка) | сервер |
| `POST` | `/api/create` | create (заглушка) | сервер |
| `POST` | `/api/copy` | copy (не поддерживается) | сервер |
| `POST` | `/api/delete` | delete (заглушка) | сервер |
| `GET` | `/v1/models` | список моделей OpenAI | сервер |
| `GET` | `/v1/models/{id}` | модель по id | сервер |
| `POST` | `/v1/chat/completions` | чат OpenAI | вкладка DeepSeek |
| `POST` | `/v1/completions` | completions OpenAI | вкладка DeepSeek |
| `POST` | `/v1/embeddings` | эмбеддинги (заглушка) | сервер |
| `GET` | `/events?tabId=X` | SSE-канал вкладки | расширение |
| `POST` | `/log` | события от вкладки | расширение |
| `GET` | `/tabs` | список вкладок | сервер |
| `POST` | `/api/command` | универсальная команда | вкладка DeepSeek |
| `POST` | `/api/ajax` | серверный AJAX | сервер |

---

## 6. Общие правила и подводные камни

### 6.1. Очередь команд

`CommandDispatcher` выполняет **одну** команду за раз. Если вы шлёте `POST /api/command` с `type:"send"` и одновременно второй `send` — второй будет ждать, пока первый не завершится (или пока watchdog не истечёт через `watchdogTimeoutMs`, по умолчанию **120 000 мс**). Это ожидаемо: одно окно DeepSeek — одна генерация.

`POST /api/ajax` **не блокируется** этой очередью: он выполняется независимо, на стороне сервера, параллельно с любой генерацией.

### 6.2. Новый диалог

`/api/chat`, `/api/generate`, `/v1/chat/completions`, `/v1/completions` перед отправкой вызывают `SessionManager.resetIfNewDialog(messages)`. Если во входных `messages` **ровно одно** `user`-сообщение, а история сервера уже непустая — сервер:
1. очищает `ChatSession`,
2. шлёт в активную вкладку команду `newChat`.

Поэтому не удивляйтесь, если в браузере сам собой создастся новый чат — это часть протокола.

### 6.3. Вложения

Вложения поддерживаются в:
- `/api/chat` и `/v1/chat/completions` — через `content`-массив (`image_url`, `input_image`, `image`, `file`) или `images[]`;
- `/api/generate` и `/v1/completions` — через `images[]`;
- `POST /api/command` с `type:"file"` — файл с диска сервера, но без ожидания ответа.

Текстовые файлы (по расширению/MIME) **инлайнятся** прямо в тело сообщения (см. `injected.js` → `isTextFile`). Бинарные — загружаются через `input[type=file]` вкладки.

### 6.4. Watchdog

Если браузер не прислал `stream-done` за `watchdogTimeoutMs`, ожидающий `StreamListener` получает `onError("watchdog timeout")`. Клиент увидит `500` на `/api/chat` (non-stream) или оборванный NDJSON/SSE (stream).

### 6.5. Лимит токенов DeepSeek

Если DeepSeek отвечает страницей «usage limit», вкладка шлёт `level:"token-limit"`. `CommandDispatcher.onTokenLimit`:
- помечает `BrowserTab.tokenExhausted = true`,
- прерывает текущий `StreamListener` с `onError("usage limit reached: <resetOn>")`,
- исключает эту вкладку из `pickActiveForDispatch` (пока её не переизберут через `/tab`).

Клиент увидит ошибку в ответе на `/api/*`.

### 6.6. CORS

`BridgeUtils.setCors` вызывается во всех новых хендлерах и в `OllamaHttpUtils.setCors` — во всех Ollama/OpenAI. Разрешены любые origin, методы `GET, POST, OPTIONS` и заголовки `Content-Type, Authorization`. Для браузерных клиентов preflight обрабатывается корректно.

### 6.7. Безопасность

- Сервер не имеет авторизации. Не выставляйте порт `11434` в интернет.
- `/api/ajax` — потенциальный SSRF: сервер может сходить на любой URL. Ограничьте его reverse-proxy или добавьте whitelist хостов.
- `/api/command` с `type:"eval"` исполняет **произвольный JS** в контексте `chat.deepseek.com` — это RCE-эквивалент для вкладки. Используйте только в доверенной сети.
- `/api/command` с `type:"file"` читает **любой файл** с диска сервера (в пределах `MAX_FILE_BYTES` из конфига). Тоже только для доверенной сети.

---

## 7. Быстрый smoke-test

```bash
BASE=http://localhost:11434

# 1. живость
curl -s $BASE/ | head -1                          # Ollama is running

# 2. модели
curl -s $BASE/api/tags | jq .

# 3. вкладки
curl -s $BASE/tabs | jq '.count'

# 4. состояние
curl -s -X POST $BASE/api/command \
  -H 'Content-Type: application/json' \
  -d '{"type":"state"}' | jq .

# 5. чат (non-stream)
curl -s -X POST $BASE/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"model":"DeepSeekCloud","messages":[{"role":"user","content":"Что такое ИИ?"}],"stream":false}' | jq .

# 6. чат (stream)
curl -N -X POST $BASE/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"model":"DeepSeekCloud","messages":[{"role":"user","content":"Скажи привет"}],"stream":true}'

# 7. команда send
curl -s -X POST $BASE/api/command \
  -H 'Content-Type: application/json' \
  -d '{"type":"send","text":"Что такое ИИ?"}' | jq .

# 8. серверный AJAX
curl -s -X POST $BASE/api/ajax \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://api.github.com/zen"}' | jq .
```

---
# Сценарий использования `POST /api/command`

Документ описывает, как вызывать `/api/command` **из браузерного JS**, из `curl`, из Node.js/Python, как обрабатывать ответы, ошибки, таймауты, и какие подводные камни есть у каждой команды. В конце — готовый мини-клиент (`DSCommandClient`), который можно вставить в HTML-страницу или в userscript.

---

## 1. Что делает эндпоинт

```
POST http://localhost:11434/api/command
Content-Type: application/json
```

**Назначение:** принять JSON-команду, поставить её в очередь `CommandDispatcher`, дождаться ответа от активной вкладки DeepSeek и вернуть JSON.

**Когда использовать:**
- нужно дёрнуть DeepSeek из своего скрипта/страницы без запуска Ollama-клиента;
- нужно управлять вкладками (открыть/закрыть/выбрать);
- нужно выполнить JS в контексте страницы (`eval`);
- нужно переключать режимы (`thinking`, `search`) до/после отправки.

**Когда НЕ использовать:**
- нужен **потоковый** ответ по токенам → используйте `/api/chat` (`stream:true`) или `/v1/chat/completions`;
- нужен **полный** ответ без стрима → `/api/chat` с `stream:false` (это блокирующий запрос, но вернёт готовый текст);
- нужен AJAX-запрос к произвольному URL → `/api/ajax`.

`/api/command` с `type:"send"` возвращает только факт «команда принята и обработана», **без текста ответа DeepSeek**. Текст идёт через SSE `/events` (служебный канал расширения) или через `/api/chat`.

---

## 2. Формат запроса

```json
{
  "type":  "…",
  "text":  "…",
  "code":  "…",
  "url":   "…",
  "tabId": "…",
  "path":  "…"
}
```

| Поле | Обязательно | Для каких `type` | Описание |
|---|---|---|---|
| `type` | ✅ | все | Идентификатор команды (см. таблицу ниже) |
| `text` | для `send`, `file` | `send`, `file` | Текст сообщения / подпись к файлу |
| `code` | для `eval` | `eval` | JS-код для `eval` в контексте страницы |
| `url` | для `openTab` | `openTab` | URL (по умолчанию `https://chat.deepseek.com/`) |
| `tabId` | для `closeTab`, `tab` | `closeTab`, `tab` | `#n` (индекс из `/tabs`), полный `tab-…` или префикс |
| `path` | для `file` | `file` | Путь к файлу на **диске сервера** |

Остальные поля игнорируются.

---

## 3. Команды — что делают, что возвращают, чего ждут

| `type` | Побочный эффект в браузере | Что вернёт `result` | Блокирует очередь? |
|---|---|---|---|
| `send` | вызывает `sendToDeepSeek(text)`: печатает текст в поле ввода, кликает «Отправить» | `{ "done": true }` — **без текста ответа** | ✅ до `stream-done` / watchdog |
| `eval` | `eval(code)` в контексте страницы (main world) | `{ "done": true }` — результат уходит в SSE `/events` как `result` | ✅ до `result`/`error` |
| `newChat` | клик по «+» → «Новый чат» | `{ "done": true }` | ✅ |
| `enableThinking` | включает «DeepThink» | `{ "done": true }` | ✅ |
| `disableThinking` | выключает «DeepThink» | `{ "done": true }` | ✅ |
| `enableSmartSearch` | включает «Search» | `{ "done": true }` | ✅ |
| `disableSmartSearch` | выключает «Search» | `{ "done": true }` | ✅ |
| `stopGeneration` | клик по «Стоп» | `{ "done": true }` | ❌ (fire-and-forget, см. ниже) |
| `resumeGeneration` | клик по «Продолжить» | `{ "done": true }` | ❌ |
| `openTab` | `chrome.tabs.create` через background.js | `{ "queued": true, "url": "…" }` | ⏩ queued |
| `closeTab` | `chrome.tabs.remove` | `{ "queued": true, "tabId": "…" }` | ⏩ queued |
| `closeAllTabs` | закрывает все `chat.deepseek.com/*` | `{ "queued": true }` | ⏩ queued |
| `tabs` | нет | `{ count, tabs: [...] }` | ✅ мгновенно (локально на сервере) |
| `state` | нет | `{ active: {...} }` | ✅ мгновенно |
| `tab` | выбор активной вкладки | `{ selected: true/false, tabId }` | ✅ мгновенно |
| `file` | отправляет файл с диска сервера | `{ queued: true }` | ⏩ queued (fire-and-forget) |
| `help` | нет | `{ endpoints: [...], commands: [...] }` | ✅ мгновенно |

> `stopGeneration` / `resumeGeneration` в `BridgeCommands` идут через `sendFireAndForget` — они **не блокируют** очередь и **не ждут** ответа. Но `ApiCommandHandler.awaitCommand` для них всё равно подписывает `StreamListener`: если вкладка пришлёт `result` — ответ вернётся; если нет — вернётся `504 timeout`. На практике они приходят как `result` через ~100–500 мс.

---

## 4. Форматы ответов

### 4.1. Успех

```json
{ "ok": true, "result": { "done": true } }
```

```json
{ "ok": true, "result": { "count": 1, "tabs": [ … ] } }
```

```json
{
  "ok": true,
  "result": {
    "active": {
      "tabId": "tab-44e0857b-...",
      "info": "{\"url\":\"https://chat.deepseek.com/\",...}",
      "thinking": false,
      "smartSearch": true,
      "tokenExhausted": false
    }
  }
}
```

```json
{ "ok": true, "result": { "queued": true, "url": "https://chat.deepseek.com/" } }
```

```json
{ "ok": true, "result": { "selected": true, "tabId": "#1" } }
```

### 4.2. Ошибка клиента (400)

```json
{ "ok": false, "error": "missing 'type'" }
```

```json
{ "ok": false, "error": "empty 'text'" }
```

```json
{ "ok": false, "error": "unknown type: foo" }
```

### 4.3. Ошибка исполнения (500)

```json
{ "ok": false, "error": "send failed: [DS] Поле ввода не найдено" }
```

```json
{ "ok": false, "error": "watchdog timeout" }
```

```json
{ "ok": false, "error": "usage limit reached: 3h" }
```

### 4.4. Таймаут ожидания ответа (504)

```json
{ "ok": false, "error": "timeout waiting for command result" }
```

### 4.5. Нет активной вкладки (503)

`/api/command` сам по себе не отвечает `503` (он не блокируется, если вкладки нет — команда просто уйдёт в `sendFireAndForget` и `ConsoleWriter` напечатает `[!] no active tab...`). Но фактически `send`/`eval`/… вернут `504 timeout`, если вкладок нет вообще. Проверяйте через `type:"tabs"`.

---

## 5. Сценарии (пошагово)

### 5.1. Базовый сценарий: отправить текст и увидеть ответ

`/api/command` текст ответа **не возвращает**. Есть 3 способа получить его:

#### A. Через `/api/chat` (проще всего)

```js
// 1) опционально — подготовить вкладку
await fetch("http://localhost:11434/api/command", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ type: "newChat" })
});

// 2) отправить и получить текст через /api/chat (stream:false)
const resp = await fetch("http://localhost:11434/api/chat", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    model: "DeepSeekCloud",
    messages: [{ role: "user", content: "Что такое ИИ?" }],
    stream: false
  })
});
const data = await resp.json();
console.log(data.message.content);  // ← полный ответ
```

#### B. Через `/api/chat` со стримом

```js
const resp = await fetch("http://localhost:11434/api/chat", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    model: "DeepSeekCloud",
    messages: [{ role: "user", content: "Что такое ИИ?" }],
    stream: true
  })
});

const reader = resp.body.getReader();
const decoder = new TextDecoder();
let buf = "";
while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buf += decoder.decode(value, { stream: true });
  let idx;
  while ((idx = buf.indexOf("\n")) !== -1) {
    const line = buf.slice(0, idx).trim();
    buf = buf.slice(idx + 1);
    if (!line) continue;
    const chunk = JSON.parse(line);
    if (chunk.message?.content) process.stdout.write(chunk.message.content);
    if (chunk.done) console.log("\n[done]");
  }
}
```

#### C. Через `/api/command` + `/events` (низкоуровнево)

`/events` — служебный SSE-канал расширения, но он открыт по CORS, поэтому **из браузера к нему тоже можно подключиться**. Это единственный способ увидеть «сырой» поток стрима, который идёт от вкладки, без `/api/chat`.

```js
// подключиться к SSE (нужен tabId — см. type:"tabs")
const tabs = await fetch("http://localhost:11434/api/command", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ type: "tabs" })
}).then(r => r.json());

const tabId = tabs.result.tabs[0].tabId;
const es = new EventSource(`http://localhost:11434/events?tabId=${tabId}`);

// !!! Внимание: этот канал одновременно слушает расширение.
// Если вы подпишетесь сюда, команды из очереди будут доставляться
// И вам, И расширению — двойное исполнение. Для боевого кода
// используйте вариант A или B.

es.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.type === "stream") console.log("chunk:", msg.text);
};

// отправить текст
await fetch("http://localhost:11434/api/command", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ type: "send", text: "Что такое ИИ?" })
});
```

> ⚠️ **Не рекомендуется**: `/events` — это канал, через который расширение получает **все** команды (включая `send`, `eval`, `newChat`). Если вы подключитесь сюда своим `EventSource`, команды начнут доставляться двум потребителям. Для чтения стрима используйте `/api/chat`. `/events` пригоден только для диагностики.

**Итог:** для «отправить и получить ответ» используйте `/api/chat`. `/api/command` с `send` полезен, когда ответ вам **не нужен** (например, чтобы прогреть вкладку, синхронизировать состояние, отправить файл).

---

### 5.2. Управление режимами (thinking / search)

Классический сценарий: включить «DeepThink» и «Search» перед отправкой запроса, потом выключить.

```js
async function cmd(type, extra = {}) {
  const resp = await fetch("http://localhost:11434/api/command", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ type, ...extra })
  });
  const json = await resp.json();
  if (!json.ok) throw new Error(json.error);
  return json.result;
}

// 1) проверить текущее состояние
const st = await cmd("state");
console.log("before:", st.active);

// 2) включить режимы
await cmd("enableThinking");
await cmd("enableSmartSearch");

// 3) отправить запрос через /api/chat (поток)
const r = await fetch("http://localhost:11434/api/chat", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    model: "DeepSeekCloud",
    messages: [{ role: "user", content: "Найди свежие новости про ИИ" }],
    stream: false
  })
});
const answer = (await r.json()).message.content;
console.log(answer);

// 4) выключить (по желанию)
await cmd("disableThinking");
await cmd("disableSmartSearch");
```

**Порядок важен:** `enableThinking`/`enableSmartSearch` блокируют очередь до ответа вкладки, поэтому следующий `send`/`/api/chat` увидит уже переключённые режимы. Если отправить `/api/chat` параллельно с `enableThinking` — есть шанс, что отправка произойдёт **до** переключения.

---

### 5.3. Остановка и продолжение генерации

```js
// запустить долгую генерацию
const controller = new AbortController();
const chatPromise = fetch("http://localhost:11434/api/chat", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    model: "DeepSeekCloud",
    messages: [{ role: "user", content: "Напиши очень длинный текст" }],
    stream: true
  }),
  signal: controller.signal
});

// через 3 секунды — остановить
setTimeout(async () => {
  await cmd("stopGeneration");         // клик по «Стоп»
}, 3000);

// через 6 секунд — продолжить
setTimeout(async () => {
  await cmd("resumeGeneration");       // клик по «Продолжить»
}, 6000);

// обработать стрим как обычно
const resp = await chatPromise;
// ...
```

> После `stopGeneration` вкладка шлёт `stream-done` → `awaitingResponse = false` → `/api/chat` завершает NDJSON. `resumeGeneration` создаст **новый** стрим (новый `streamId`), но `/api/chat` его уже не увидит — если нужен «продолженный» текст, читайте его через `/events` или через повторный `/api/chat` (но это создаст **новый** запрос, а не продолжение).

---

### 5.4. Управление вкладками

```js
// 1) список вкладок
const list = await cmd("tabs");
console.log(list.count, list.tabs.map(t => t.tabId));

// 2) открыть новую
await cmd("openTab", { url: "https://chat.deepseek.com/", active: false });

// 3) выбрать по индексу (из /tabs)
await cmd("tab", { tabId: "#1" });

// 4) выбрать по префиксу
await cmd("tab", { tabId: "tab-44e08" });

// 5) закрыть конкретную
await cmd("closeTab", { tabId: "tab-fbadc099-..." });

// 6) закрыть все
await cmd("closeAllTabs");
```

> `openTab`/`closeTab`/`closeAllTabs` — fire-and-forget: ответ `{queued:true}` приходит сразу, до фактического открытия/закрытия. Чтобы дождаться эффекта — поллинг `type:"tabs"`.

---

### 5.5. Выполнение JS на странице (`eval`)

```js
// прочитать заголовок
const r = await cmd("eval", { code: "document.title" });
console.log(r);   // { done: true }  — а сам результат уйдёт в /events
```

Результат `eval` **не возвращается** через `/api/command` (это особенность `CommandDispatcher.onResult`: он печатает в консоль сервера и не сопоставляется с конкретным запросом). Чтобы **получить** результат в браузер, используйте `/events`:

```js
const es = new EventSource(`http://localhost:11434/events?tabId=${tabId}`);
es.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.type === "result") console.log("eval result:", msg.text);
  if (msg.type === "error")  console.error("eval error:", msg.text);
};

await fetch("http://localhost:11434/api/command", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ type: "eval", code: "document.title" })
});
```

> ⚠️ Опять же: `/events` — общий канал расширения. На практике для `eval` проще написать свой одноразовый скрипт на сервере или использовать `type:"send"`, если нужна только отправка. Для чтения произвольных значений — используйте `eval` через собственный WebSocket/HTTP на стороне расширения (вне этого API) или через `/api/ajax` (если нужен HTTP-запрос со страницы).

---

### 5.6. Отправка файла

`type:"file"` — **fire-and-forget**: файл читается с диска **сервера** (`path`), кодируется в base64, шлётся в активную вкладку как `send` с `files[]`. Ответа с текстом генерации не будет.

```js
await cmd("file", {
  path: "C:/docs/report.pdf",
  text: "Проанализируй этот PDF"
});
```

> Ограничения: `MAX_FILE_BYTES` (по умолчанию 20 МБ) и `MAX_TOTAL_BYTES` (50 МБ) из `config.json`. Текстовые файлы (по расширению) будут **инлайнены** в тело сообщения (см. `isTextFile` в `injected.js`); бинарные — загружены через `input[type=file]`.

---

### 5.7. Полный «чат-цикл» из браузерной страницы

Готовый сценарий: страница с полем ввода, кнопкой «Отправить», чекбоксами «DeepThink»/«Search» и кнопкой «Стоп». **Текст ответа** читается через `/api/chat` (stream), а режимы/новый чат/стоп — через `/api/command`.

```html
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <title>DeepSeek via bridge</title>
  <style>
    body { font-family: sans-serif; max-width: 720px; margin: 2em auto; }
    #log { white-space: pre-wrap; background: #f4f4f4; padding: 1em; border-radius: 6px; min-height: 200px; }
    .row { display: flex; gap: 1em; align-items: center; margin: .5em 0; }
    button { padding: .4em 1em; }
  </style>
</head>
<body>
  <h1>DeepSeek через bridge</h1>

  <div class="row">
    <label><input type="checkbox" id="think"> DeepThink</label>
    <label><input type="checkbox" id="search"> Search</label>
    <button id="newChat">Новый чат</button>
  </div>

  <div class="row">
    <input id="prompt" type="text" placeholder="Что спросить у DeepSeek?" style="flex:1">
    <button id="send">Отправить</button>
    <button id="stop">Стоп</button>
  </div>

  <div id="log"></div>

<script>
const BASE = "http://localhost:11434";

async function cmd(type, extra = {}) {
  const resp = await fetch(BASE + "/api/command", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ type, ...extra })
  });
  const json = await resp.json();
  if (!json.ok) throw new Error(json.error);
  return json.result;
}

function log(s) {
  const el = document.getElementById("log");
  el.textContent += s;
  el.scrollTop = el.scrollHeight;
}

document.getElementById("newChat").onclick = () => cmd("newChat").then(() => log("\n[new chat]\n"));

document.getElementById("send").onclick = async () => {
  const text = document.getElementById("prompt").value.trim();
  if (!text) return;
  document.getElementById("prompt").value = "";

  // синхронизировать режимы
  await cmd(document.getElementById("think").checked ? "enableThinking" : "disableThinking");
  await cmd(document.getElementById("search").checked ? "enableSmartSearch" : "disableSmartSearch");

  // отправить через /api/chat stream
  const resp = await fetch(BASE + "/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "DeepSeekCloud",
      messages: [{ role: "user", content: text }],
      stream: true
    })
  });

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  log("\n[you] " + text + "\n[ai] ");

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf("\n")) !== -1) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line) continue;
      const chunk = JSON.parse(line);
      if (chunk.message?.content) log(chunk.message.content);
      if (chunk.done) log("\n");
    }
  }
};

document.getElementById("stop").onclick = () => cmd("stopGeneration").then(() => log("\n[stopped]\n"));
</script>
</body>
</html>
```

> CORS уже разрешён на стороне сервера (`BridgeUtils.setCors`), поэтому страница может работать с любого origin, включая `file://` (некоторые браузеры блокируют `file://` fetch — тогда откройте через локальный HTTP, например `python -m http.server`).

---

## 6. Готовый клиент `DSCommandClient`

Мини-обёртка для удобной работы из браузера. Поддерживает `fetch` с таймаутом, `AbortController`, повторные попытки.

```js
class DSCommandClient {
  constructor(base = "http://localhost:11434") {
    this.base = base;
  }

  async cmd(type, extra = {}, { timeoutMs = 130000, signal } = {}) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(new Error("client timeout")), timeoutMs);
    const onAbort = () => ctrl.abort(signal.reason);
    if (signal) signal.addEventListener("abort", onAbort, { once: true });

    try {
      const resp = await fetch(this.base + "/api/command", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ type, ...extra }),
        signal: ctrl.signal
      });
      const json = await resp.json();
      if (!json.ok) throw new Error(json.error || "unknown error");
      return json.result;
    } finally {
      clearTimeout(timer);
      if (signal) signal.removeEventListener("abort", onAbort);
    }
  }

  // удобные шорткаты
  send(text)               { return this.cmd("send", { text }); }
  eval(code)               { return this.cmd("eval", { code }); }
  newChat()                { return this.cmd("newChat"); }
  thinking(on = true)      { return this.cmd(on ? "enableThinking" : "disableThinking"); }
  search(on = true)        { return this.cmd(on ? "enableSmartSearch" : "disableSmartSearch"); }
  stop()                   { return this.cmd("stopGeneration"); }
  resume()                 { return this.cmd("resumeGeneration"); }
  openTab(url, active=true){ return this.cmd("openTab", { url, active }); }
  closeTab(tabId)          { return this.cmd("closeTab", { tabId }); }
  closeAllTabs()           { return this.cmd("closeAllTabs"); }
  tabs()                   { return this.cmd("tabs"); }
  state()                  { return this.cmd("state"); }
  tab(tabId)               { return this.cmd("tab", { tabId }); }
  file(path, text = "")    { return this.cmd("file", { path, text }); }
  help()                   { return this.cmd("help"); }

  // стрим через /api/chat
  async *chatStream(text, { model = "DeepSeekCloud", signal } = {}) {
    const resp = await fetch(this.base + "/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model,
        messages: [{ role: "user", content: text }],
        stream: true
      }),
      signal
    });
    if (!resp.ok) throw new Error("chat failed: " + resp.status);
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (!line) continue;
        const chunk = JSON.parse(line);
        if (chunk.message?.content) yield chunk.message.content;
        if (chunk.done) return;
      }
    }
  }
}
```

**Пример использования:**

```js
const ds = new DSCommandClient();

// подготовить вкладку
await ds.newChat();
await ds.thinking(true);
await ds.search(true);

// отправить и напечатать потоком
for await (const piece of ds.chatStream("Что нового в мире ИИ?")) {
  process.stdout.write(piece);
}

// выключить
await ds.thinking(false);
await ds.search(false);
```

---

## 7. Подводные камни и рекомендации

### 7.1. `/api/command` и `/api/chat` не должны идти одновременно

`/api/command` с `type:"send"` и `/api/chat` (`stream:true`) оба идут через `CommandDispatcher`, который выполняет **одну** команду за раз. Если запустить их параллельно, вторая встанет в очередь и выполнится только после первой.

**Правильно:**
```js
// подготовка через /api/command (последовательно)
await ds.thinking(true);
await ds.search(true);

// затем — стрим
for await (const piece of ds.chatStream("...")) { ... }
```

**Неправильно (гонка):**
```js
await Promise.all([
  ds.send("..."),          // через /api/command
  ds.chatStream("...")     // через /api/chat
]);
```

### 7.2. `/api/command send` не возвращает текст

Если нужен текст — используйте `/api/chat`. `send` возвращает только `{done:true}` после `stream-done`. Он полезен, когда текст не нужен: прогрев, синхронизация, отправка файла.

### 7.3. Таймауты

- Клиентский таймаут `fetch` — по умолчанию **нет**. Если вкладка не ответила за 120 с, сервер вернёт `504 timeout`, но `fetch` может ждать ещё дольше. Всегда задавайте `AbortController` с таймаутом (см. `DSCommandClient.cmd`).
- Watchdog сервера (`watchdogTimeoutMs`, по умолчанию 120 000 мс) — срабатывает, если вкладка не прислала `stream-done`. После этого `awaitingResponse = false`, и следующий запрос пройдёт.

### 7.4. `eval` и `file` — только в доверенной сети

- `eval` исполняет **произвольный JS** в контексте `chat.deepseek.com` — это RCE-эквивалент для вкладки.
- `file` читает **любой файл** с диска сервера (до `MAX_FILE_BYTES`).
- `/api/ajax` — SSRF.
- Порт `11434` не имеет авторизации. Не выставляйте в интернет; при необходимости — reverse-proxy с Basic Auth или IP-whitelist.

### 7.5. `type:"tabs"` и пустой список

Если `tabs` возвращает `{count: 0}`, значит ни одна вкладка `chat.deepseek.com/*` не подключена к SSE (`/events`). Проверьте, что расширение установлено, вкладка открыта и `background.js` жив (Service Worker иногда засыпает — Chrome будит его при сообщениях, но при простое SSE-подключение может отвалиться; `content.js` переподключается каждые 5 с через `scheduleReconnect`).

### 7.6. `thinking` / `search` в `state` могут отставать

`state` возвращает данные из `BrowserTab` на сервере, которые заполняются из `mode-state`-событий вкладки. После `enableThinking` вкладка шлёт `mode-state` асинхронно — подождите 100–300 мс перед `state`, если нужен свежий снимок.

### 7.7. `stop` и `resume`

- `stopGeneration` кликает по кнопке «Стоп» (`STOP_PATH_MARKER` в `injected.js`). Если генерация уже остановлена — вернёт `500 { "error": "[DS] Кнопка 'Стоп' не найдена..." }`.
- `resumeGeneration` кликает по «Продолжить» (`CONTINUE_TEXT`). Если кнопки нет — `500`.
- После `stop` авто-продолжение выключается (`stopRequested = true` в `injected.js`). Включается обратно при `resume` или новом `send`.

### 7.8. `newChat` и `/api/chat`

`/api/chat` сам вызывает `resetIfNewDialog(messages)` → если во входных `messages` только одно `user`-сообщение и история сервера не пуста, он **сам** шлёт `newChat` в вкладку. Поэтому перед `/api/chat` **не обязательно** вручную вызывать `newChat` — сервер сделает это сам, если решит, что это новый диалог. Явный `newChat` через `/api/command` полезен, если вы хотите **гарантированно** начать с чистого листа.

### 7.9. Формат вложений в `/api/chat`

`/api/command send` не поддерживает `files[]` (только `type:"file"` с диска сервера). Если нужно отправить файл **из браузера**, используйте `/api/chat` с `content`-массивом:

```js
await fetch("http://localhost:11434/api/chat", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    model: "DeepSeekCloud",
    messages: [{
      role: "user",
      content: [
        { type: "text", text: "Что на картинке?" },
        { type: "image_url", image_url: { url: "data:image/png;base64,iVBOR..." } }
      ]
    }],
    stream: false
  })
});
```

Текстовые файлы (`txt`, `md`, `json`, `csv`, код) можно инлайнить прямо в `text` — `injected.js` умеет их распознавать, но проще передать как часть `content` с `type:"text"`.

### 7.10. CORS preflight

Все `POST` из браузера с `Content-Type: application/json` вызовут `OPTIONS` preflight. Сервер отвечает `204 No Content` с правильными `Access-Control-*` — это уже настроено, дополнительных действий не нужно.

---

## 8. Шпаргалка

```bash
# 1. Проверить, что сервер жив
curl -s http://localhost:11434/ || echo "server down"

# 2. Есть ли вкладки?
curl -s -X POST http://localhost:11434/api/command \
  -H 'Content-Type: application/json' -d '{"type":"tabs"}' | jq '.result.count'

# 3. Состояние активной вкладки
curl -s -X POST http://localhost:11434/api/command \
  -H 'Content-Type: application/json' -d '{"type":"state"}' | jq .

# 4. Включить DeepThink + Search
curl -s -X POST http://localhost:11434/api/command \
  -H 'Content-Type: application/json' -d '{"type":"enableThinking"}'
curl -s -X POST http://localhost:11434/api/command \
  -H 'Content-Type: application/json' -d '{"type":"enableSmartSearch"}'

# 5. Новый чат
curl -s -X POST http://localhost:11434/api/command \
  -H 'Content-Type: application/json' -d '{"type":"newChat"}'

# 6. Отправить и получить ответ (через /api/chat, не /api/command!)
curl -s -X POST http://localhost:11434/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"model":"DeepSeekCloud","messages":[{"role":"user","content":"Что такое ИИ?"}],"stream":false}' | jq '.message.content'

# 7. Остановить
curl -s -X POST http://localhost:11434/api/command \
  -H 'Content-Type: application/json' -d '{"type":"stopGeneration"}'

# 8. Помощь по всем командам
curl -s -X POST http://localhost:11434/api/command \
  -H 'Content-Type: application/json' -d '{"type":"help"}' | jq .
```
---
