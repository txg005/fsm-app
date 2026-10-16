# Независимое QA review frozen контракта v1.0

## Итог: PASS

**P0/P1 проблем согласованности не выявлено.** Frozen контракт позволяет реализовать утверждённый студенческий MVP без новых функций. Не требуется останавливать Android/backend работы или повторно согласовывать уже принятые решения. PASS относится к документам/fixtures, не к реализации, сборке, устройству или работающему серверу.

Основание: целиком прочитаны локальные `api-contract.md`, `architecture.md`, `implementation-plan.md`; JSON `contract-fixtures.json` загружен и проверен. По сообщению координатора это публикация commit `88d8f3ed1444e4fade6ac71faa36e541b157eba1`, draft PR #1. Соответствие локальных файлов Git blob побайтно в этом проходе не проверялось. Исходные требования CLAUDE/AGENTS/TASK прочитаны ранее; учитываются утверждённые уточнения пользователя. GitHub и четыре контрактных файла не изменялись.

## Проверка критических мест

| Область / раздел | Вывод review |
|---|---|
| CRUD и финальность: architecture §1/§4; API §5/§8 | Полный CRUD именно локальных inspection_results до QUEUED. Нет server drafts/client create work_order. CLOSED final; seeds и одна роль. Будущее create отделено без нынешних заглушек/API. Согласовано. |
| Атомарный enqueue: architecture §4.3/§5; plan этап 3 | Финальные данные + QUEUED + N upload ops + close op одной Room-транзакцией. Enqueue хранит immutable предметный snapshot с local UUID; после ACK upload сохраняются окончательные close bytes с serverPhotoIds до первого HTTP. Это два разных этапа сериализации, а не противоречие «immutable»: локальные IDs не отправляются как remote refs. |
| Unknown outcome: API §6/§7; architecture §4.4–6/§5 | Timeout/408/5xx, отмена Service и 401 не являются разрешением отменить close/изменить payload. RUNNING после смерти процесса восстанавливается с тем же UUID; replay до snapshot. ACK и canonical Room update атомарны. Соответствует сохранности после commit/потери ответа. |
| Возврат в DRAFT: architecture §4.6–7; API §7 | Только definitive rejection 400/413/422 и явное действие; revision conflict — с показанной canonical ASSIGNED и прежним назначением. Сервер CLOSED/доступ отозван не допускают повторного close. Локальная копия сохраняется до подтверждённого выбора пользователя. Нет неявного rollback неизвестного close. |
| Общий operation key: API §6; plan backend tests 4–5 | `(employeeId,operationId)` общий для upload и close, hash включает method/path. Коллизия с иным маршрутом/телом → IDEMPOTENCY_KEY_REUSED. Успех + receipt commit вместе; конкурентные одинаковые ключи сериализуются. Отдельные новые UUID для нового согласованного close, но не для сетевого retry. |
| Receipt priority: API §4–6 | Exact replay успешного upload/close возвращает прежний status/body, даже после CLOSED/revision change. Auth/текущий доступ проверяются прежде выдачи receipt — отзыв доступа не раскрывает данные, локальная копия при этом сохраняется. Только success receipts, никакой обещанной общей FS+SQL транзакции. |
| Multipart/photo refs: API §3–5; architecture §3/§5 | Две части metadata/file, JPEG bytes/hash/size проверяются. Boundary/filename не влияют на semantic hash. Photo UUID и serverPhotoId различны; close содержит serverPhotoIds. Все прикреплённые фото upload-before-close, optional означает возможность пустого списка, не пропуск failed upload. Private same-origin contentPath без токена в URL. |
| Filesystem durability: API §4; architecture §3/§6 | filesDir/localPath остаются после upload/ACK. Backend rename до SQL с recovery orphan по стабильному key, 201 после durable file+SQL. STAGED и receipts в v1 не удаляются автоматически, потому поздний replay не зависит от скрытого TTL. |
| Delete/no resurrection: architecture §4.2/§4.6–7; plan Android tests 2/5/8 | В исходном DRAFT операций нет, QUEUED нельзя удалять. После явного возврата к DRAFT отклонённый close отменён; старые upload receipts могут переиспользоваться. Явное принятие сервера удаляет локальные операции только после подтверждения. Наш T16 дополнительно проверяет delayed callbacks и DB/file-delete crash; это тест реализации указанной логики, не новый endpoint. |
| Фото/GPS/logs/SDK: architecture §2/§3/§6; API §3/§5 | Фото optional; GPS best effort, MISSING валиден. UUID String у entity/operation/log IDs; 7 доменных таблиц отдельно от outbox/app_logs. Logger Log+DB, 1000/7d и technical screen обязательны. minSdk26/target36/JDK17 зафиксированы; не выдаются за протестированные. |
| Service/Worker: architecture §5–6; plan этап 4 | Настоящий конечный dataSync FGS; Worker использует тот же SyncEngine/Mutex в одном процессе. START_NOT_STICKY, timeout/cancel и отсутствие гарантий force-stop отражены. WorkManager не подменяет Service. |

## Fixtures: что действительно проверено

Проверка выполнена локальным read-only Python-скриптом, без запуска реализации:
- JSON успешно разобран; все значения в `ids` — canonical lowercase UUID v4.
- `closeExactBodyUtf8` декодируется в ровно `close.request`; semantic SHA-256 пересчитан по формуле API §6 и совпал.
- Upload semantic hash пересчитан по method/path/photoId/MIME/byteSize/sha256 и совпал.
- Multipart base64 разобран MIME parser: ровно две части в порядке metadata/file, metadata совпадает с JSON примером, bytes file совпадают с `jpegBase64`.
- JPEG действительно декодирован: 8×8, 631 byte; размер и SHA-256 совпали с metadata.
- Close использует именно serverPhotoId из upload receipt; upload/close operation keys различны в общем scope.
- WorkOrder list/detail/snapshot-before совпадают; close receipt workOrder совпадает с snapshot-after.
- Вручную сверены nullable Location CAPTURED/MISSING, no-photo alternative, ошибки и localOnly Outbox ACK/dependency примеры. Fixture notes явно запрещают использовать token placeholders для auth integration и требуют fresh state для альтернативного close без фото.

**Несовпадений fixtures с проверенной логикой не обнаружено.** Это не исчерпывающая JSON Schema validation всех возможных входов, не тест конкурентного PostgreSQL и не проверка production JWT.

## Что не является новой проблемой контракта

- Запрет редактирования при unknown outcome уже есть: не предлагать «исправить» после timeout, даже если UI хочется сделать проще.
- Общая обработка HTTP409 не должна показывать выбор версии для IDEMPOTENCY_KEY_REUSED: API §7 уже отдельно определяет его как invariant error. Реализация обязана ветвиться по code, а не только status.
- Если upload terminal-failed, dependent close не отправляется. Явное исправление возвращает весь соответствующий close workflow в DRAFT только без неизвестных in-flight исходов; нельзя редактировать существующий frozen operation. Основание — architecture §4/§5 и plan Android tests 5–7.
- Max photos/validation, safe logs и same-origin guard — уже включённые требования. Дополнительные роли, delta sync, resumable upload, server TTL и always-on delivery не нужны.

## Изменённые файлы и реальные blockers

- Создан только этот `contract-review.md`.
- Локальная `acceptance-and-risks.md` синхронизирована с frozen: SDK, точные экраны/таблицы, logs retention/GPS timeout, общий key scope, terminal-vs-unknown lifecycle; прежний blocker «договориться о контракте» снят.
- **Оставшиеся P0/P1 blockers контракта: нет.** Следующий gate — фактическая реализация и выполнение уже заданных fault/replay/CRUD/auth/Service тестов. Сборка, emulator, физическое устройство, Docker и backend runtime в этом review не запускались.
