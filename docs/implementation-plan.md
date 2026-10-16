# FSM v1.0 — независимая реализация и приёмка

Контракт заморожен: api-contract.md; архитектура/жизненный цикл: architecture.md; executable input examples: contract-fixtures.json. Четыре бизнес-вопроса решены, новых продуктовых блокеров нет. Настройка URL/секретов и фактическая проверка API levels — задачи реализации. Этот документ не является отчётом об успешных сборках.

## 1. Владение и интеграция

| Исполнитель | Владеет | Не меняет самостоятельно |
|---|---|---|
| Android | android/**, Gradle wrapper/root Android build config по заранее оговорённому размещению, Room migration/schema export, Android tests, android/README.md | backend/**, compose.yaml, wire DTO/enum/paths |
| Backend | backend/** (собственный wrapper/settings), compose.yaml, .env.example с заглушками, SQL миграции/seed, backend tests/README | android/**, wire DTO/enum/paths |
| Координатор | README.md, docs/api-contract.md, docs/architecture.md, docs/implementation-plan.md, docs/contract-fixtures.json | бизнес-контракт без согласования |

Рекомендуемое размещение — два независимых Gradle build root `android/` и `backend/`, чтобы не конфликтовать в settings.gradle.kts. Общий Kotlin DTO-модуль не нужен: два независимых исполнителя используют один wire contract и fixtures. Корневой Compose build context backend/. Backend не нуждается в Android SDK; Android разрабатывается с fixture HTTP engine без запущенного сервера. Оба используют Ktor; SQL library свободна при сохранении атомарности.

Сейчас только локальные проектные документы; их публикацию в repo и последующие feature branches выполняет координатор отдельно. Перед кодом каждый исполнитель читает main CLAUDE.md/AGENTS.md/docs/TASK.md, формулирует план/критерии и фиксирует контрактную версию v1.0. Отдельные ветки/PR, main без разрешения не merge. Если контрактная ошибка действительно блокирует реализацию — сообщить координатору, не придумывать альтернативный endpoint/enum.

## 2. Последовательность вертикальных этапов

### 0 — каркас и проверка контракта
- Backend: Kotlin/Ktor skeleton, error handler, PostgreSQL миграции семи таблиц + auth/receipts, Compose volumes и healthcheck; demo accounts из env.
- Android: Compose navigation пяти экранов, Room 7 доменных + outbox/app_logs/server_shadow/sync_state, UUID String type consistency, user partition, logging wrapper.
- Совместно: fixture loading tests, поля nullability, UUID, timestamps, decimal, HTTP errors. Fixtures содержат настоящий маленький JPEG для multipart. Валидация token example только structural, не пытаться использовать его для auth integration.
- Готово: оба проекта независимо собираются в своих окружениях, Android парсит snapshot/upload/close/error examples, backend принимает валидные request examples. Отчёт указывает реальные команды и результаты, не запланированные проверки как выполненные.

### 1 — login → snapshot → Room → список/карточка
- Backend auth login/refresh/logout, JWT+session revocation, read routes/snapshot/access scopes.
- Android единый Ktor client, refresh Mutex, secure token storage, first sync; list/card из Flow.
- Готово: неверный пароль и чужие ID безопасны; после успешного первого sync метаданные читаются в airplane mode и после process restart. Другой аккаунт не видит предыдущую Room/files/logs. Expired access не запрещает локальную работу.

### 2 — полный локальный CRUD результата и фото
- Android create/read/update/delete DRAFT до enqueue, checklist/decimal/comment validation, Photo Picker/camera→filesDir, стабильные UUID/hash, GPS on close best-effort. Запуск/ошибка синхронизации пишется console+app_logs, технический экран показывает DB logs.
- Backend параллельно реализует multipart upload + protected download и idempotent storage; серверного draft CRUD **нет**.
- Готово: C/R/U/D показаны на inspection_results; сервер не получает DRAFT, deleting DRAFT не требует сети. Фото binary не хранится в Room; localPath существует после рестарта. Logger ограничивает 1000/7d и не содержит тела DTO/токенов/GPS/comment.

### 3 — закрытие и Outbox сквозным срезом
- Android одна Room transaction final result+QUEUED+N upload ops+close op; UUID каждого stable. Operation dependencies; последовательный SyncEngine; upload receipt записан в Room, localPath остаётся. Frozen close bytes записаны до HTTP.
- Backend close FOR UPDATE+revision+attach photos+receipt атомарно. Final result и статус только CLOSED после commit.
- Готово: 0 фото → close сразу, N фото → все upload ACK до close, optional missing GPS accepted; draft editing/delete заблокированы в QUEUED. Ответ потерян после server commit → replay возвращает тот же response, без второй записи/изменения revision.

### 4 — полезный Service, retries, конфликты
- Android foreground dataSync из видимого UI, notification while running, cancel, progress Flow; Worker того же SyncEngine/Mutex; durable backoff/auth/error state. CONFLICT сохраняет обе версии, никаких silent replace.
- Backend concurrent duplicate keys, inconsistent bodies, revision conflicts, terminal error cases; structured безопасные logs.
- Готово: cancel/kill/restart/одновременный Worker не создают дубли. После force-stop не обещать background; после открытия приложения очередь возобновляется. UI даёт явный подтверждённый выбор серверной версии/возврата к draft только если ещё ASSIGNED.

### 5 — landscape и end-to-end приёмка
- Проектные README: команды запуска, env, API URL для emulator, demo seed, разрешения, известные ограничения; обновление UI при Service status/error.
- Готово: приведённая ниже матрица выполнена доступными средствами; physical/emulator checks честно помечены «не выполнено», если среды нет. Test success не означает проверку устройства.

## 3. Обязательная матрица автоматических тестов

### Android JVM/Room/HTTP fixture tests
1. Все domain/outbox/log IDs UUID String; FK/unique и миграции. Room бизнес-finalization и insert outbox rollback вместе при ошибке.
2. CRUD DRAFT без HTTP; удаление только до QUEUED. Если enqueue DB fail, фото/результат не исчезают, UI не сообщает о принятом закрытии.
3. Process restart восстанавливает RUNNING→PENDING с исходным UUID/frozen bytes, attempts/nextAttemptAt. Загруженные фото получают serverPhotoId/contentPath, localPath не обнуляется и файл остаётся.
4. Multipart retry меняет boundary, но semantic hash/key постоянны; jpeg bytes/hash неизменны. Неотправленное фото нельзя физически удалять во время QUEUED.
5. Upload dependency failure блокирует только close этой заявки, не делает ложный ACK; независимая заявка может быть обработана последовательно позже. Close JSON содержит serverPhotoIds, не local IDs.
6. Worker+Service одновременно: один runner через Mutex, сервер видит максимум один текущий запрос. Coroutine cancellation не интерпретируется как terminal no-effect и не очищает очередь.
7. Timeout/408/429/5xx → stable retry; 401 → single refresh; bad refresh → auth required без потери данных; validation → UI исправления; key-reused → invariant error, не новый произвольный key.
8. Snapshot ASSIGNED result=null не стирает локальный draft/queued/conflict. Собственное commit без ack не даёт ложный revision conflict до replay. Исчезнувшее назначение сохраняет local copy/блокирует upload.
9. Logger insert+bounded cleanup, console fallback на DB failure без рекурсии; whitelist messages не интерполируют PII. Mock timestamps детерминированно проверяют 7d/1000.
10. Same-origin contentPath guard не отправляет bearer на внешний URL; nullability/unknown response fields handling.

### Backend integration tests с настоящим PostgreSQL
1. Login wrong/unknown одинаковые, logout revokes sid и refresh, истёкший access, scope чужих объектов и фото →404. Rate-limit/reset изолирован в тестах.
2. Snapshot согласован при конкурентном close, result/status invariant, только текущий employee; STAGED фото отсутствуют в snapshot.
3. Close required answers/readings/ranges/duplicates/result UUID reuse/optional photo/GPS. ASSIGNED revision r→CLOSED r+1 ровно один раз; новый close после CLOSED →409.
4. Одинаковый key/hash конкурентно → один business effect и тот же receipt. Тот же key с другим body/method/path →409, включая upload→close reuse. Разные keys и одна revision → один success, другой ORDER_CLOSED/REVISION_CONFLICT согласно состоянию.
5. JSON exact bytes участвуют в close hash, multipart boundary/filename не участвуют в upload hash. Same photo UUID/bytes/new key → same serverPhotoId; different bytes →409.
6. Multipart parts count/order/content type/size/hash/invalid JPEG/path filename, protected binary ETag/content length; fixture JPEG успешно декодируется.
7. Fault injection после atomic rename/до SQL commit и после commit/до response: повтор безопасно подбирает файл/receipt, не создаёт duplicate row/blob. SQL failure rollback status/result/receipt вместе. Долговечность volume после container restart.
8. Ошибки по контракту, нет stack traces/паролей/токенов/GPS/comment в logs. Недоступная заявка после старого receipt не раскрывает данные.

## 4. Ручная/инструментальная Android матрица

- На целевых min/target API (выбор зафиксировать build config, target актуальный на момент реализации): GPS denied/approximate/disabled/timeout; camera cancel/return, picker URI исчез до копирования; notifications denied; FGS start из видимого UI/cancel/system timeout.
- Пять экранов portrait/landscape, клавиатура/прокрутка, rotation при заполнении и upload, возврат в Activity, process death после подтверждённого локального сохранения.
- Сценарий: login→snapshot→airplane→create/edit/delete draft→recreate→фото→close offline→restart→restore network→Service uploads→close→ещё sync. Проверить один result, число photos не выросло, revision не увеличилась повторно, localPath цел.
- Конфликт: server CLOSED другим клиентом, исходный frozen local сохранён; принять серверную только после подтверждения; если server всё ещё ASSIGNED после revision conflict, явный возврат к draft сохраняет содержимое/обновляет base.
- Force-stop: **отсутствие** обещания фонового продолжения; открыть приложение снова, очередь восстанавливается. Удаление данных/приложения не покрывается сохранностью очереди.

## 5. Предлагаемые команды (ещё не выполнены)

Backend после появления wrapper: `cd backend && ./gradlew test`; `docker compose config`; `docker compose up --build`; интеграционные тесты с отдельной PostgreSQL DB/volume, не рабочими данными.
Android: `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug`; `connectedDebugAndroidTest` только при наличии SDK/emulator/device. JSON fixture syntax: `python3 -m json.tool docs/contract-fixtures.json >/dev/null`.

PR handoff каждого исполнителя: изменённые файлы, команды и exit codes, покрытые сценарии, непроверенное, риски. Не приписывать прошедшие сборки документационному этапу. Не добавлять неиспользуемый API/роль ради «будущего CRUD заявок».
