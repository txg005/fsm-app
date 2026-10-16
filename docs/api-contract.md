# FSM API contract v1.0 — FROZEN

Источник решений: architecture.md. Авторитет wire форм — этот файл; contract-fixtures.json содержит валидные примеры, не альтернативную схему. Изменения только согласованно двумя исполнителями/координатором. Серверные draft endpoints отсутствуют намеренно: C/R/U/D inspection_results выполняется локально до close enqueue.

## 1. Общие правила

Base `/api/v1`. HTTPS в deployed среде, debug localhost может HTTP. JSON UTF-8 application/json, camelCase. Все поля, указанные в типе, обязательны; `T?` означает присутствующий null или T, не optional. Пустые списки допустимы, если не запрещены отдельно. Request неизвестные поля → 400 MALFORMED_REQUEST; response неизвестные поля клиент игнорирует. JSON duplicate keys отклоняются. Порядок элементов справочников не значим; checklistItems сортированы position,id, photoIds/photo arrays в порядке выбора клиента.

Типы: UUID — lowercase canonical UUID v4 String (включая employee/result/photo/serverPhoto/operation/request IDs); Instant — UTC RFC3339 с Z, millisecond precision (например `2026-10-16T17:00:00.000Z`); int32/int64 JSON integer, revision 1..9007199254740991. decimal — String `^-?(0|[1-9][0-9]*)(\.[0-9]{1,4})?$`, <=12 значащих цифр, exponent/NaN/infinity запрещены. Geo number finite. Strings не обрезать/trim молча; login ASCII case-insensitive (нормализовать lowercase), password case-sensitive.

Каждый ответ имеет `X-Request-Id: UUID`, генерируемый сервером (не отражать непроверенный входной header). Date server clock не используется для LWW. Все защищённые маршруты Bearer; недоступный resource → 404, а не раскрытие чужих ID. JSON ErrorBody ниже, включая ошибки multipart. 204 без body. В v1 нет пагинации, фильтров и delta; неизвестные query params → 400. Серверные ответы read v1 не ограничены искусственным усечением: возвращается полный demo-набор. Максимальный JSON request 256 KiB, multipart file 10 MiB + metadata <=4 KiB + framing, общий multipart <=11 MiB.

## 2. Auth

| Метод/путь | Request | Response |
|---|---|---|
| POST /auth/login | LoginRequest | 200 TokenResponse |
| POST /auth/refresh | RefreshRequest | 200 TokenResponse |
| POST /auth/logout | RefreshRequest, без bearer | 204, идемпотентно; неизвестный/отозванный token также 204 |

LoginRequest `{login:string(1..100),password:string(1..128)}`. RefreshRequest `{refreshToken:string(1..512)}`.
TokenResponse `{accessToken:string,accessExpiresAt:Instant,refreshToken:string,refreshExpiresAt:Instant,employee:EmployeeDto}`.

Access JWT HS256, claims sub=employee UUID, sid=refresh session UUID, iat/exp integer seconds, iss=`fsm-api`, aud=`fsm-android`, TTL15m; секрет >=256 бит окружением, запрет alg substitution. Backend проверяет signature/claims и неотозванную sid на каждом защищённом запросе. Refresh — random 256-bit opaque base64url без padding, TTL30d, hash SHA-256 в БД, **не ротируется** при refresh. Logout отзывает только предъявленную session; password Argon2id со случайной солью. Demo seed использует env credentials, fixtures содержат нерабочие примеры, не действующие секреты. Wrong login/password → одинаковый 401 INVALID_CREDENTIALS; refresh expired/revoked/invalid →401 SESSION_EXPIRED. Login rate limit 10 попыток/мин на IP, 429 Retry-After. Не хранить password в employee DTO.

## 3. DTO чтения

```text
EmployeeDto { id:UUID, displayName:string, login:string }
SiteDto { id:UUID, name:string, address:string }
EquipmentDto { id:UUID, siteId:UUID, name:string, inventoryNumber:string }
ChecklistItemDto { id:UUID, position:int32>=0, text:string, required:boolean }
MeasurementSpecDto { key:string, label:string, unit:string, required:boolean,
                     min:decimal?, max:decimal? }
AnswerDto { itemId:UUID, checked:boolean }
ReadingDto { key:string, value:decimal }
LocationDto { status:CAPTURED|MISSING,
              reason:DENIED|TIMEOUT|PROVIDER_DISABLED|UNAVAILABLE|null,
              latitude:number?, longitude:number?, accuracyMeters:number?, capturedAt:Instant? }
PhotoDto { id:UUID, serverPhotoId:UUID, mimeType:"image/jpeg", byteSize:int64,
           sha256:string, createdAt:Instant, contentPath:string }
ResultDto { id:UUID, answers:AnswerDto[], readings:ReadingDto[], comment:string,
            location:LocationDto, capturedAt:Instant, photos:PhotoDto[] }
WorkOrderDto { id:UUID, equipmentId:UUID, assigneeId:UUID, title:string,
               description:string, dueAt:Instant?, status:ASSIGNED|CLOSED,
               revision:int64, checklistItems:ChecklistItemDto[],
               measurementSpecs:MeasurementSpecDto[], result:ResultDto? }
ReferenceDataDto { employee:EmployeeDto, sites:SiteDto[], equipment:EquipmentDto[] }
WorkOrderListDto { items:WorkOrderDto[] }
SnapshotDto { generatedAt:Instant, employee:EmployeeDto, sites:SiteDto[],
              equipment:EquipmentDto[], workOrders:WorkOrderDto[] }
```

Location CAPTURED: reason=null, все четыре geo/time значения non-null, lat [-90,90], lon [-180,180], accuracy>=0. MISSING: reason non-null, все четыре null. Нет NOT_REQUESTED в закрытом результате: клиент обязан попробовать автоматически при закрытии, если разрешение не дано — DENIED. Отсутствие геопозиции не блокирует закрытие.

ASSIGNED ⇒ result=null. CLOSED ⇒ result non-null. Server не передаёт IN_PROGRESS/DRAFT/QUEUED — это Room/UI lifecycle. Неизменные определения checklist/specs включены в заявку, measurement key regex `[a-z][a-z0-9_]{0,63}`; item IDs принадлежат одной заявке. Поля read DTO содержат только доступные пользователю данные; min<=max, уникальные keys/positions контролируются seed/миграциями.

| Метод/путь | Успешный результат |
|---|---|
| GET /reference-data | 200 ReferenceDataDto, только текущий employee и объекты/оборудование его заявок |
| GET /work-orders | 200 WorkOrderListDto, все назначенные текущему employee, включая CLOSED |
| GET /work-orders/{workOrderId} | 200 WorkOrderDto |
| GET /sync/snapshot | 200 SnapshotDto — согласованная REPEATABLE READ SQL транзакция |
| GET /photos/{serverPhotoId}/content | 200 binary image/jpeg; доступны и собственные STAGED upload, и ATTACHED к своей заявке |

Photo contentPath **строго** `/api/v1/photos/{serverPhotoId}/content`, relative same-origin, без токена/query/signed expiration. Ktor добавляет bearer; Android не должен отправлять токен на произвольный URL из response. binary response: Content-Length, Content-Type image/jpeg, ETag=`"<lowercase sha256>"`; conditional/range requests v1 не поддерживаются. PhotoDto.sha256 lowercase 64 hex, byteSize 1..10485760. Storage filesystem path никогда не возвращается. ServerPhotoId не равен localPath и не заменяет PhotoDto.id. Android Room localPath остаётся после upload и ACK close.

## 4. Фото upload — multipart и receipt

`POST /work-orders/{workOrderId}/photos`
Headers: Authorization, `Idempotency-Key: UUID`, `Content-Type: multipart/form-data; boundary=...`.
Ровно две части, metadata сначала, file затем; другие/повторные части →400:
1. `Content-Disposition: form-data; name="metadata"`, Content-Type application/json. JSON `PhotoUploadMetadata {photoId:UUID,mimeType:"image/jpeg",byteSize:int64,sha256:string}`.
2. `Content-Disposition: form-data; name="file"; filename="photo.jpg"`, Content-Type image/jpeg, реальные JPEG bytes; filename игнорируется для storage. Path traversal невозможен: путь генерируется backend.

Сервер проверяет длину, SHA-256 фактических bytes и JPEG decode/header, а не только header MIME. Несовпадение →422 PHOTO_CONTENT_INVALID, over limit →413 PHOTO_TOO_LARGE. Фото не конвертируется на сервере, hash относится к полученным окончательным bytes. Android сжимает/удаляет ненужный EXIF до enqueue и не меняет файл после enqueue. GPS передаётся только LocationDto, не извлекается из EXIF.

Успех 201 `PhotoUploadReceipt {operationId:UUID,photo:PhotoDto}`. По первому upload backend создаёт serverPhotoId UUID, фиксирует связь `(employeeId,workOrderId,photoId)` и metadata. Unique photoId глобально; другой владелец →404 без раскрытия, тот же ID с иным workOrderId/hash/size →409 PHOTO_ID_REUSED. Новый operationId с тем же photoId и теми же metadata/bytes возвращает тот же serverPhotoId и 201, не создаёт дубликат. STAGED не меняет revision. Новые upload для CLOSED →409 ORDER_CLOSED; точный replay ранее принятого upload после закрытия возвращает receipt.

Файл temp→flush/atomic rename в volume, затем SQL метаданные+receipt в одной транзакции. Нет ложного обещания filesystem+SQL общей транзакции: crash после rename до commit оставляет orphan, повтор по детерминированному storage key photoId/hash проверяет/подбирает файл. Response 201 только после durable файла и SQL commit. Не удалять STAGED/receipts автоматически в v1.

## 5. Окончательное закрытие

`POST /work-orders/{workOrderId}/close`
Headers Authorization, Idempotency-Key UUID. Body application/json:
```text
CloseRequest { baseRevision:int64, result:ResultWriteDto }
ResultWriteDto { id:UUID, answers:AnswerDto[], readings:ReadingDto[], comment:string,
                 location:LocationDto, capturedAt:Instant, photoIds:UUID[] }
CloseReceipt { operationId:UUID, workOrder:WorkOrderDto }
```

photoIds — **serverPhotoId**, не local photoId. До первой отправки close Android атомарно сохраняет окончательные JSON bytes после получения всех upload receipts; повторяет те же bytes/key. Порядок photoIds сохраняется. Успех 200 CloseReceipt, revision увеличена ровно на 1, status=CLOSED. При отсутствии фото `photoIds:[]`, upload операций нет.

Авторизация и revision: транзакция резервирует idempotency key, блокирует work_order FOR UPDATE, проверяет назначение и baseRevision; CLOSED с новым key →409 ORDER_CLOSED (приоритет над revision mismatch), иначе несовпадение revision →409 REVISION_CONFLICT. Все answers itemId уникальны и принадлежат заявке; required=true обязаны присутствовать checked=true. Optional может отсутствовать/false. Все readings.key уникальны/из specs; required reading обязателен; числовые границы включительны. Comment 0..4000 Unicode code points, без обязательного содержания. Максимум 10 фото, все существуют, доступны владельцу/заявке, hash validated, не прикреплены к иному result. Result id глобально уникален, чужой/иной work order ID →409 RESULT_ID_REUSED. Передать результат текущей другой заявки запрещено. Нет правила обязательного фото/GPS; MISSING валиден.

Атомарно insert inspection_results, attach photos, update status/revision, insert receipt. Не разрешать частичный commit. Для GET результат появится только после commit. Нет серверных PUT/DELETE result или PATCH статуса, нет create/delete work_order. Локальный статус «В работе» не нуждается в API. Такое сокращение исключает конфликт с утверждённым CRUD черновиков до отправки.

## 6. Идемпотентность

Scope key `(employeeId,operationId)` **общий для всех mutation маршрутов upload/close**. Receipt stores method+canonical path+semanticHash+HTTP status+body. Не удалять v1 receipts. Запросы login/refresh/logout и GET не используют ключи. Отсутствующий/невалидный key mutation →400.

Hash input UTF-8:
- close: `POST\n/api/v1/work-orders/<id>/close\n` + SHA256 exact body bytes hex. JSON bytes клиент сохраняет до первой отправки.
- upload: `POST\n/api/v1/work-orders/<id>/photos\n<photoId>\nimage/jpeg\n<byteSize decimal>\n<sha256 lowercase>`; фактический file digest/size обязаны совпасть с metadata. JSON whitespace, multipart boundary и filename не участвуют.
В обоих случаях semanticHash=SHA256 полученной строки. Revision/close payload входят через JSON body hash. Все SHA значения lowercase hex.

После auth и проверки текущей доступности work_order: разобрать/проверить hash запроса, найти receipt. Тот же key/method/path/hash → **тот же HTTP status/body**, даже если revision изменилась/статус CLOSED; X-Request-Id новый. Иной hash/path/method при том же key →409 IDEMPOTENCY_KEY_REUSED. Два конкурентных одинаковых key сериализуются UNIQUE lock, один эффект. Receipt успешной операции и бизнес-запись commit вместе; rollback снимает reservation.

В v1 сохраняются **только успешные 200/201** receipts. 4xx терминальный ответ — гарантия отсутствия нового бизнес-эффекта этого запроса (408 — исключение с неизвестным исходом). Это не означает отсутствия ранее принятой операции; в частности при IDEMPOTENCY_KEY_REUSED: исходная операция с таким key могла ранее успешно выполниться; клиент не должен менять тело/key вслепую, это invariant failure. 5xx/timeout/408 — исход неизвестен, повтор исходного key/bytes. Upload повтор после смены назначения может дать404: клиент сохраняет локальные данные, не раскрывает старый receipt при потере доступа. Не обещать доступ к отозванным данным через receipt.

## 7. Ошибки и retry

```text
ErrorBody { error:{code:string,message:string,retryable:boolean,details:object}, requestId:UUID }
```

Поле details всегда object (хотя бы {}); message для человека, алгоритм по code. Таблица исчерпывает прикладные коды v1:

| HTTP | code | retryable/details |
|---|---|---|
| 400 | MALFORMED_REQUEST | false / {} (невалидный JSON/UUID/null/enum/key/unknown field/query) |
| 401 | INVALID_CREDENTIALS | false / {} (login) |
| 401 | AUTH_REQUIRED | false / {} (нет/истёк access; клиент один раз refresh) |
| 401 | SESSION_EXPIRED | false / {} (refresh невалиден) |
| 404 | NOT_FOUND | false / {} (нет или чужой resource) |
| 408 | REQUEST_TIMEOUT | true / {} |
| 409 | REVISION_CONFLICT | false / {currentRevision:int64} |
| 409 | ORDER_CLOSED | false / {currentRevision:int64} |
| 409 | PHOTO_ID_REUSED | false / {} |
| 409 | RESULT_ID_REUSED | false / {} |
| 409 | IDEMPOTENCY_KEY_REUSED | false / {} |
| 413 | PHOTO_TOO_LARGE | false / {maxBytes:10485760} |
| 413 | REQUEST_TOO_LARGE | false / {maxBytes:262144} (JSON) |
| 422 | VALIDATION_ERROR | false / {fields:[{path:string,code:string}]} |
| 422 | PHOTO_CONTENT_INVALID | false / {} |
| 422 | PHOTO_MISSING | false / {photoIds:UUID[]} |
| 429 | RATE_LIMITED | true / {}, Retry-After integer seconds |
| 500/503 | INTERNAL_ERROR/SERVICE_UNAVAILABLE | true / {} |

Validation field.code: REQUIRED, UNKNOWN_REFERENCE, DUPLICATE, OUT_OF_RANGE, TOO_LONG, INVALID_FORMAT. paths JSON dotted/indexed: `result.answers[0].itemId`, `result.readings`, `result.location`. 422 PHOTO_MISSING относится к serverPhotoIds close; у клиента сохраняются localPath и upload receipts, он останавливает автоцикл и показывает ошибку целостности; автоматическая очистка server blob v1 запрещена.

Network/408/429/5xx: durable retry того же UUID с exponential backoff+jitter. 401 refresh один раз под Mutex, повтор запроса; failure AUTH_REQUIRED оставляет очередь. 409: fetch current order, сохранить canonical рядом с frozen local, UI явного выбора. IDEMPOTENCY_KEY_REUSED — ошибка инварианта, не retry с новым ID. При окончательном validation failure можно явно вернуть к локальному draft и закрыть заново с новым key. Snapshot никогда не стирает local draft/queued/conflict. Исчезновение назначения — NOT_FOUND/ACCESS_REVOKED **локальный** код, последнего в wire enum нет.

## 8. Вне контракта v1

Нет server draft API, create-work-order endpoint, admin/dispatcher role, token в URL, push, signed public фото URL, delta sync, автоматического разрешения конфликтов, гарантии выполнения Android после force-stop. Для будущего создания заявок добавляются отдельные DTO/service/endpoint после согласования, существующие UUID/FK не требуют переделки.
