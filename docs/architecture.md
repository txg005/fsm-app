# FSM — утверждённая архитектура v1.0

Статус: контракт заморожен для реализации. Учтено последнее сообщение пользователя в родительской сессии (event 209) и уточнение координатора. Обязательные `CLAUDE.md`, `AGENTS.md`, `docs/TASK.md` ранее прочитаны из main, commit `fa07caf349538b9df898cb4b6ad5d539688320b6`. Этот документ заменяет прежнее предложение с серверными черновиками. Wire contract: `api-contract.md`; примеры: `contract-fixtures.json`; границы работ: `implementation-plan.md` в том же каталоге.

## 1. Зафиксированные решения

- Android: Kotlin, Compose, Room, Coroutines/Flow, общий Ktor HTTP client. Backend: Kotlin Ktor REST, PostgreSQL, Docker Compose. Одна роль ENGINEER, демонстрационные аккаунты и данные через backend seed, без регистрации и диспетчерской панели.
- Мобильный клиент читает `work_orders`, окончательно закрывает назначенную заявку. Создания, удаления, переназначения заявки в UI/API v1 нет. `tickets` — только синоним, в коде и схеме всегда `work_orders`.
- Полный C/R/U/D `inspection_results` реализуется **локально до постановки закрытия в очередь**. Сервер не получает черновики. Один результат на одну заявку. Чек-лист — фиксированные пункты заявки; показания — поля по определениям оборудования в заявке; комментарий — одно поле.
- Фото необязательно. Реальные JPEG в Android filesDir; Room хранит `localPath` и отдельно `serverPhotoId`/`contentPath`. Upload не удаляет и не обнуляет localPath. GPS автоматически запрашивается при нажатии «Закрыть», только в видимом UI, best-effort, с явной причиной отсутствия. Закрытие окончательное после подтверждения сервера.
- Обязательный durable Outbox; Service передаёт фото **до** закрытия. Идемпотентность и revision на сервере; конфликт не приводит к молчаливой потере локальных данных. Никаких push, LWW, автоматического merge, карт, фонового GPS или дополнительных ролей.
- UUID lowercase String — все доменные PK, outbox/operation IDs и app_logs IDs. PostgreSQL может хранить native uuid, wire/Room всегда String. Счётчики revision/sequence — числа, но не PK.
- Объём v1 небольшой: полный пользовательский snapshot, без delta/pagination. Загруженные метаданные доступны офлайн; для первого входа и первой загрузки нужна сеть. Лимиты и состояния ниже — окончательные технические решения v1, не новые бизнес-вопросы.

Блокирующих продуктовых вопросов не осталось. Адрес развёрнутого API/секреты задаются окружением; это не препятствует реализации на localhost/emulator с fixtures. Реальная поддержка устройства определяется сборкой и тестами, которые здесь не выполнялись.

## 2. Компоненты и границы

`Compose → ViewModel/StateFlow → Repository → Room/внутренние файлы`.
`SyncService / Worker → один SyncEngine → общий Ktor client → Ktor routes → services → PostgreSQL + photo volume`.

Android minSdk 26, compileSdk/targetSdk 36, JDK 17; версии Kotlin/Compose/Ktor и Gradle зафиксировать совместимыми в build files и проверить сборкой, не считать их уже проверенными. Один Android `:app`, пакеты ui/data/sync/service/logging. UI всегда читает Room Flow, не сервер напрямую. Явные зависимости без framework ради framework. Backend — один процесс, маршруты/предметные сервисы/SQL repositories, миграции; Docker Compose api+postgres и volumes. Секретов в git нет. API вне debug — HTTPS, cleartext разрешён только debug-конфигурацией для локального эмулятора.

Android владеет вводом/черновиками, permissions, файлами, очередью, Service, UX ошибок. Backend владеет авторизацией, назначением, canonical revision, валидацией закрытия, атомарностью и идемпотентностью. Клиентская валидация не заменяет серверную.

## 3. Ровно семь доменных таблиц

| Таблица | Основные поля/связи |
|---|---|
| employees | id UUID, display_name, login unique; текущий инженер |
| sites | id UUID, name, address |
| equipment | id UUID, site_id FK, name, inventory_number |
| work_orders | id UUID, equipment_id FK, assignee_id FK employees, title, description, due_at nullable, status, revision, measurement_specs JSON |
| checklist_items | id UUID, work_order_id FK, position, text, required |
| inspection_results | id UUID, work_order_id FK UNIQUE, answers JSON, readings JSON, comment, location JSON, captured_at; на Android lifecycle отдельно от серверных DTO |
| photos | id UUID (клиентский/сквозной), work_order_id FK, result_id FK nullable, owner_id FK employees, mime_type, byte_size, sha256; серверные server_photo_id UUID unique/storage_key/state; локальные local_path/server_photo_id/content_path nullable |

На сервере inspection_results существует только для CLOSED, на Android также для локального DRAFT/QUEUED/CONFLICT. Ответы и readings — вложенные value objects, не новые таблицы. Все FK индексированы, ID не меняется при sync. PhotoDto.id совпадает с Room photos.id, отдельный serverPhotoId выдаётся backend. Пути к файлам и ключи storage не доменные внешние идентификаторы. Фото server-side STAGED до прикрепления и ATTACHED после; STAGED не появляется в snapshot. Серверные файлы в volume, БД не содержит BLOB. В v1 автоматической серверной уборки blob нет: это устраняет гонки с поздними retry; объём demo контролируется оператором. Дальнейшая retention требует отдельного контракта.

Служебные таблицы, **не входят в семь**:
- Room `outbox`: id UUID=operationId, kind UPLOAD_PHOTO/CLOSE_WORK_ORDER, work_order_id, photo_id nullable, sequence integer monotonic, state, frozen_payload_json, base_revision nullable, dependencies JSON UUID[], attempts, next_attempt_at nullable, last_error_code nullable. Запрос immutable после enqueue; retry сохраняет id. Инварианты UNIQUE live CLOSE на заявку, UNIQUE upload на photoId, состояния PENDING/RUNNING/RETRY_WAIT/AUTH_REQUIRED/FAILED/CONFLICT/ACKED/CANCELLED. Технический sequence не заменяет UUID PK.
- Room `app_logs`: id UUID, at UTC, level DEBUG/INFO/WARN/ERROR, event_code, operation_id nullable UUID, request_id nullable UUID, safe_message. Logger пишет console и DB; только whitelist шаблонов, никаких raw exception/response/request bodies, паролей, токенов, точных GPS, комментариев или localPath. Максимум 1000 строк и 7 дней: после каждой вставки в той же транзакции удалить старше 7 дней и всё сверх последних 1000; timestamp ties сортировать по id. Ошибка DB logger — console fallback, без рекурсии, не ломает бизнес-транзакцию.
- Room `server_shadow` (id=workOrder UUID, revision, canonical JSON) и `sync_state` (id UUID, employeeId unique, lastMetadataSyncAt). DB/user partition не позволяет смешивать аккаунты.
- PostgreSQL auth_credentials(employeeId UUID PK/passwordHash), refresh_sessions(id UUID PK/tokenHash/employeeId/expiresAt/revokedAt), idempotency_receipts(employeeId UUID, operationId UUID, method, path, semanticHash, status, body; composite PK employeeId+operationId), schema migrations. Все наши entity/operation PK — UUID; системная нумерация миграций не доменная сущность. Receipt PK(employeeId,operationId), оба UUID; никакого автоинкрементного PK.

## 4. Точный жизненный цикл inspection_results

1. Нет результата → «Начать осмотр»: Room C с новым result UUID, DRAFT. Список показывает «В работе» локально; canonical server status остаётся ASSIGNED. Нет endpoint ради промежуточного IN_PROGRESS.
2. R/U в DRAFT, сохранение в Room; удалить DRAFT (D) — удалить его локальные фото/временные файлы после DB commit, заявка снова без результата. Эти локальные CRUD не ставят сетевые операции: серверных draft данных нет.
3. «Закрыть»: блокировать повторное действие, проверить обязательные поля, автоматически запросить GPS (15s максимум, nullable reason), затем **одна Room-транзакция**: сохранить финальные данные и lifecycle QUEUED; вставить N UPLOAD_PHOTO operations и 1 CLOSE operation с UUID/dependencies/frozen payload/baseRevision. Если транзакция не commit — никакой закрытой локальной заявки без очереди. Файлы до неё уже атомарно скопированы в filesDir и хешированы.
4. QUEUED — immutable для правок/удаления. UI: «Ожидает отправки», не подтверждённое CLOSED. Остановка Service не означает отзыв закрытия и не разблокирует результат: запрос мог уже commit на сервере. Снятия неизвестной in-flight операции нет.
5. Успех close → CLOSED/SYNCED, результат read-only, локальные файлы остаются. ACK и canonical aggregate применяются одной Room-транзакцией. Успешные операции можно хранить для диагностики, чистить спустя 7 дней при отсутствии зависимостей; серверные receipts v1 не чистятся.
6. Только **окончательный** 400/413/422 до принятия close позволяет «Исправить»: явно пометить close FAILED→CANCELLED, вернуть DRAFT, сохранить все данные/фото. Новый close — новый operationId и актуальная revision. Успешные upload receipts/ссылки можно переиспользовать. После timeout/5xx/401 такого перехода нет, сначала восстановить исход через replay.
7. CONFLICT сохраняет локальную frozen копию и отдельно canonical версию. Экран показывает обе; «Принять серверную» требует подтверждения удаления локального результата/операций и не выполняется молча. Если сервер ASSIGNED и назначение не изменилось, «Вернуться к черновику» явно отменяет отклонённый close, обновляет baseRevision из показанной серверной версии и сохраняет локальное содержимое; пользователь проверяет и закрывает заново. Если сервер CLOSED/доступ отозван — повторное закрытие запрещено; локальная копия остаётся доступной этому аккаунту до явного подтверждения удаления. Это ручной выбор версии, не merge.

Будущий create-work-order: отдельный CreateWorkOrderRequest, server service/repository create с проверками FK, новый endpoint и UI после согласования. UUID/FK и разделение repository read/close не мешают расширению. Сейчас нет зарезервированных полей, мёртвого endpoint, роли dispatcher или заглушки create в UI.

## 5. Sync и Outbox

Все outbound business mutations (постановка закрытия вместе с фото) атомарны с outbox в Room. Локальные несетевые правки DRAFT не требуют фиктивных outbox операций. Один последовательный runner на процесс: Service и Worker используют один SyncEngine и общий Mutex, без отдельных HTTP циклов. Android process один, исключить android:process у компонентов. Внутри Mutex claim RUNNING в DB, после убийства процесса RUNNING восстанавливается в PENDING с теми же UUID. Разные задачи не отправляются одновременно.

Сначала восстановить/replay зависшие операции, затем refresh snapshot. Зависимости upload→close проверяются по ACKED; close собирает только serverPhotoIds из durable upload receipts в порядке frozen photoIds. Собранный close JSON фиксируется в Room **перед первой отправкой** и далее не меняется. Первый enqueue хранит immutable предметный payload/локальные photo UUID; смена draft или файла невозможна. Upload semantic hash не зависит от multipart boundary.

Service идёт по sequence готовых операций; retry одной заявки не блокирует независимую другую, но параллельной передачи нет. Retry: network/timeout/408/429/5xx с тем же UUID, backoff 2,4,…300s+jitter, учитывать Retry-After. Счётчик/следующее время сохраняются. До 5 попыток/10 минут одного запуска; затем остановить Service и оставить durable очередь. 401 — один refresh и повтор, иначе AUTH_REQUIRED. Остальные terminal ошибки без горячего retry. Отмена корутины не обнуляет операцию/файл. Close response потерян после commit → replay по receipt, а не ложный конфликт revision.

Snapshot — полный набор доступных данных в одной серверной REPEATABLE READ транзакции. Canonical сохраняется отдельно от draft/queued/conflict. Не перезаписывать локальную работу серверным result=null. Для отсутствующей заявки с local work сохранять её локальную копию и ACCESS_REVOKED; для чистой — удалить из доступного списка. Binary cache заполняется отдельно, UI честно отмечает, что конкретное серверное фото ещё не доступно офлайн. Snapshot generatedAt не cursor и не средство conflict resolution.

## 6. Service, permissions, безопасность, UI

Полезный `SyncService` dataSync сам загружает фото и close HTTP, уведомление с прогрессом/«Остановить» пока работает. Start из видимого Activity по кнопке через startForegroundService, быстро startForeground, manifest FGS/dataSync permissions. START_NOT_STICKY; stopForeground/stopSelf при завершении/cancel/timeout; обрабатывать ограничения бюджета Android 15+ и onTimeout на поддерживаемых API. Не стартовать FGS из BOOT_COMPLETED или произвольного background. Foreground **не гарантирует неубиваемость**, продолжение после force-stop или немедленную отправку: очередь переживает обычное убийство процесса, выполнение возобновляется при разрешённом запуске. После force-stop пользователь должен снова открыть приложение.

Worker — unique one-time work с network constraint, ограниченные порции той же очереди, без long-running worker-FGS; разрешённые триггеры: enqueue, открытие/возврат приложения, неуспешный transient цикл с backoff. Планировщик может отложить работу. При занятом Mutex Worker завершает/перепланирует, не запускает второй runner. Service ждёт освобождения Mutex отменяемо; не блокирует main thread. UI↔Service — start/stop intent и Room StateFlow, не volatile события.

Permissions: INTERNET/ACCESS_NETWORK_STATE; POST_NOTIFICATIONS 13+ с rationale (отказ не обходить, системная видимость FGS зависит от ОС); COARSE/FINE при закрытии, approximate допустимо. Нет BACKGROUND_LOCATION. GPS current fix timeout 15s, не выдавать stale за новую; DENIED/TIMEOUT/PROVIDER_DISABLED/UNAVAILABLE отражать wire reason. Фото через Photo Picker без storage permission и ACTION_IMAGE_CAPTURE+FileProvider, без CAMERA permission при отсутствии своего camera API. URI grants минимальны, копировать в filesDir; temp cleanup только незарегистрированных файлов. Upload/rotation/restart не удаляют localPath. Не утверждать, что выбранное из галереи фото снято сейчас.

Access JWT в памяти; refresh opaque token AES-GCM ciphertext в приватном DataStore/file, non-exportable Android Keystore key. Пароли нигде не сохранять. Room/files/tokens исключены из Auto Backup. Изоляция Room/files по employeeId; другой пользователь не видит черновики/логи прежнего. Logout с pending требует предупреждение и выбор «отмена/выйти с сохранением»; logout не удаляет работу. Offline logout стирает локальный токен, но не обещает серверный отзыв. Повторный вход тем же пользователем восстанавливает очередь.

Пять основных экранов: вход; мои заявки; карточка; форма результата; фото/закрытие. Вспомогательные: просмотр фото, конфликт, technical logs (app_logs read-only последние события/фильтр уровня), системные permission/camera/picker dialogs, sync status. Технический экран — обязательная демонстрация console+DB logger, не admin panel. Landscape поддерживается без fixed orientation: прокрутка, доступные действия, при rotation ViewModel/SavedStateHandle и Room; подтверждённые записи переживают process death. Неподтверждённый ввод сохранять с debounce/flush, показывать «Сохранение»; не обещать сохранение последних несохранённых keystrokes при аварии.

## 7. Проверки и ограничения

Критерии и команды определены в implementation-plan.md. Здесь только проектирование документов: никакой код/ветки/PR не создавались; сборки, Android API levels, permissions на устройстве и Docker не проверены. Прежнее предложение про серверные draft PUT/DELETE, IN_PROGRESS wire и отсутствие DB logs больше не действует.
