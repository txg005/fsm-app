# Итоговая проверка первого этапа FSM

## Объём и границы вывода

Первый этап: Android-приложение инженера и собственный REST-сервер, уровень B с локальной работой и синхронизацией. Полный CRUD относится к локальным черновикам inspection_results; заявки создаются серверными demo seeds. Нет диспетчерской панели, регистрации, push, непрерывного GPS или автоматического слияния конфликтов.

Документ фиксирует выполненные проверки, пользовательские сообщения о ручном прогоне и ещё не проверенные сценарии. Успешная сборка не означает исполнения instrumentation tests или полной приёмки на физическом устройстве.

## Актуальный статус: main `0c3f0ed…`

Источник актуального автоматического результата — [CI run 39741466813](https://github.com/txg005/fsm-app/actions/runs/39741466813) на commit `0c3f0ed0e4e4ba630b4f1bb66e8d530d7808c292`. Все три job завершились успешно; report-gate annotations фиксируют фактически исполненные XML cases, а не только компиляцию APK.

| Среда / источник | Фактический результат | Граница вывода |
| --- | --- | --- |
| CI Backend PostgreSQL | 9 unit + 11 PostgreSQL integration; 0 failures/errors/skips; installDist PASS | Hosted Linux + disposable PostgreSQL; не Docker/Windows production |
| CI Android build | 25 JVM; 0 failures/errors/skips; lint 0 errors/19 warnings; app + test APK PASS | Не гарантия обновления установленного APK и не визуальная приёмка |
| CI Android emulator | **15 instrumentation tests реально исполнены**, 0 failures/errors/skips, API36/google_apis/x86_64, KVM | Room/media/engine, fixtures/mock HTTP; не live backend и не вся API/device-матрица |
| Windows — сообщение пользователя | Оба wrapper `--version`, backend `test installDist`, Android `testDebugUnitTest lintDebug assembleDebug` успешны | Ручное окружение пользователя, не Windows CI |
| Новый UI — сообщение пользователя | Эмулятор, engineer2: фото/GPS, 2 ACK, подтверждённое CLOSED | Один конкретный UI/API сценарий; не landscape/permission/lifecycle matrix |

Workflow/gate сохраняет в имени «14 baseline tests», но допускает дополнительные tests: исходные14 методов сохранены, EXIF regression расширен на8 orientations и добавлен no-EXIF case; actual total15. Android25 JVM =13 контрактных +12 UI-mapping tests. Подробности среды/команд/артефактов — [CI.md](CI.md); bootstrap/Windows — [BUILD-WINDOWS.md](BUILD-WINDOWS.md). CI не запускает environment-specific Netty smoke; его прежний успешный результат приведён ниже отдельно.

**Это не all-acceptance PASS.** Следующий этап landscape/recreation/lifecycle ещё проверяется отдельно; его будущие результаты этому commit/run не приписываются. Короткий сценарий защиты — [DEMO.md](DEMO.md); прохождение сценария также записывается отдельно, а не выводится из наличия инструкции.

## История первого этапа (baseline до CI/UI/EXIF продолжения)

Ниже сохранены первоначальные версии и результаты: выражение «только скомпилированы» описывает **тот baseline**, не актуальный статус15 tests выше.

### Проверенные версии baseline

- Android PR #3: `9a7118cf373936e6ba16736e776238630c02816a`. Все 29 файлов сверены автором с локальными исходниками по Git blob SHA. QA сверил exact Git commit с проверенным снимком: 28/29 совпали, единственное ожидаемое отличие — README с результатами. Критичные исправления проверены через GitHub.
- Backend PR #2: финальный код/отчёт `6da898f6e5d52b62862178888c5c4ba26283bbf4`; multipart runtime fix — `cf0c5c8c7b806a372969c63f4c714f102636983b`.
- Контракт API v1.0, первоначальная заморозка: `88d8f3ed1444e4fade6ac71faa36e541b157eba1`.

### Автоматические проверки baseline

| Область | Фактически выполнено | Результат |
|---|---|---|
| Backend unit | 9 тестов | 0 failures/errors/skips |
| Backend integration | 11 тестов с настоящим PostgreSQL | 0 failures/errors/skips |
| Backend build | test, integrationTest, installDist с принудительным повтором задач | Exit 0 |
| Backend HTTP smoke | Настоящий Netty + PostgreSQL; реальный Ktor multipart, фото, close/replay, restart, auth/scoping/NUL | Exit 0 |
| Android JVM | 13 тестов | 0 failures/errors/skips |
| Android lint | lintDebug | 0 ошибок, 19 предупреждений |
| Android APK | assembleDebug и assembleDebugAndroidTest | Оба APK собраны, Exit 0 |
| Android instrumentation | 14 Room/media/engine tests | Только скомпилированы; НЕ выполнены |

XML-сводки тестов сверены координатором. Полные команды и условия запуска находятся в `android/README.md`, `backend/README.md`, `backend/VERIFICATION.md` и `backend/MULTIPART-INCIDENT.md`. Сборки в sandbox выполнялись последовательно с ограничением памяти и числа Gradle workers. Прерванные/неуспешные ранние запуски не засчитаны как успешные.

### Ручной прогон пользователя на первом этапе

Пользователь запускал backend через Docker Compose на Windows и установленный debug APK в Android-эмуляторе. Следующие результаты сообщены пользователем, а не получены автоматическим инструментальным тестом:

- Успешный запуск сервера, health check, вход engineer1 и получение списка заявок.
- CRUD локального черновика, заполнение и закрытие без сети, сохранность после повторного открытия приложения, отправка после восстановления связи.
- Отправка фотографии и закрытие заявки после server-only multipart fix. На присланном экране: revision 2, CLOSED, UPLOAD_PHOTO ACKED, CLOSE_WORK_ORDER ACKED, фото сохранено локально и upload подтверждён. Точная последовательность отдельных UI-нажатий в момент фонового завершения по скриншоту не устанавливалась.
- Переключение engineer1 -> engineer2 -> engineer1: пользователь подтвердил отсутствие данных первого инженера у второго и сохранность данных при обратном входе.

Эти сообщения подтверждают прохождение указанного демонстрационного сценария в окружении пользователя. Они не заменяют отдельные проверки всех разрешений, landscape, остановок ОС, утечек данных или конкурентных изменений.

## Исправленные дефекты и регрессии

1. Backend: NUL/непарные UTF-16 surrogates в комментарии больше не приводят к постоянной SQL-ошибке под видом retryable 503. До записи возвращается 422 VALIDATION_ERROR / result.comment / INVALID_FORMAT; проверены отсутствие бизнес-эффекта, повтор отклонённого ключа и успешный исправленный close.
2. Android: оба explicit resolve действия получают свежий canonical, проверяют идентичность/назначение/доступность/статус и требуют нового подтверждения при изменившейся версии. Неудачный GET не удаляет локальную работу.
3. Android: durable CONFLICT записывается атомарно до suspend HTTP; предусмотрено восстановление legacy QUEUED + terminal-CONFLICT. Старый shadow не считается свежим.
4. Android: недостоверный success response после dispatch означает UNKNOWN_OUTCOME с повтором того же operationId и frozen bytes, а не неповторяемый FAILED. Ошибки локального файла до отправки отделены.
5. Backend multipart: реальные Ktor 3.1.3 bytes до правки воспроизвели 400. Теперь необязательный одиночный per-part Content-Length принимается только при ASCII decimal, попадании в Long и точном равенстве фактическому числу bytes. Проверки двух частей, MIME, JPEG, hash, размеров, порядка и дубликатов сохранены. Реальная Ktor HTTP/PG-регрессия и Netty smoke прошли.

QA: все перечисленные блокирующие замечания закрыты в границах целевого review. Это не утверждение об отсутствии любых дефектов.

## Непроверенное и ограничения

- Baseline gap исполнения14 tests закрыт указанным CI run: реально выполнены15. Это не закрывает физический телефон, Compose UI rotation/recreation или live-backend E2E во всех отказах.
- Физический Android-телефон: камера/Photo Picker, GPS, Keystore, разрешения, FGS и ограничения конкретного производителя.
- Полная матрица landscape/клавиатуры/rotation, Activity recreation, process death/force-stop/timeout/cancel и переключения аккаунтов во время передачи. Room/engine regressions исполнялись в CI, но не заменяют эту UI/ОС матрицу. Результаты Stage3 пока не зафиксированы здесь.
- Docker: локальный запуск на Windows подтверждён пользователем; формальные контейнерные/volume durability tests, уничтожение питания, перезапуск хоста и потеря диска не выполнялись.
- Production TLS/reverse proxy, публичное развёртывание, нагрузка и исчерпывающий security/fuzz testing не проверены.
- Room schema v1: upgrade migration не заявляется. После аварии до callback камеры может остаться приватный scratch-файл без автоматического срока очистки.
- Foreground Service не гарантирует бессмертие процесса или отправку после force-stop. Очередь сохраняется, выполнение зависит от разрешённых Android условий запуска.

## Непройденная ручная матрица Stage3

Это список к выполнению, не протокол успешного прогона. Для каждой строки записать commit/APK origin, устройство/API, точные шаги, результат и evidence без секретов/точных координат. Подпись APK проверять отдельно; тестовые данные не сбрасывать ради результата.

| Проверка | Что считать успехом | Статус |
| --- | --- | --- |
| Portrait/landscape всех5 основных экранов, IME/прокрутка | Кнопки/ошибки доступны, сохранённый ввод/выбранная заявка не теряются | Не зафиксировано |
| Activity recreation с сохранённым draft и при передаче | Сохранённые поля/фото/route согласованы; ACK/CLOSED не выдуман | Не зафиксировано |
| Реальный process stop/reopen offline, затем Service retry | UUID/frozen bytes/local media сохранены; итоговое закрытие одно | Пользовательский основной прогон есть; полная контролируемая матрица не пройдена |
| Cancel/Home/возврат, notifications denied, FGS ограничения | Нет false ACK, отмена передачи не отменяет close; продолжение только допустимым ОС способом | Не зафиксировано полностью |
| GPS denied/approximate/disabled/timeout + camera/picker cancel | Честный MISSING либо свежий fix, фото/результат не теряются | Один фото/GPS user report есть; полная матрица не пройдена |
| Account switch во время pending/Service, auth expiry | Нет чужих данных/отправки, повторный вход тем же пользователем сохраняет очередь | Межаккаунтный user report есть; конкурентная матрица не пройдена |
| Физический телефон/OEM background и permissions | Согласованный сценарий на записанной модели/API | Не выполнено |

## Debug APK и повторяемая демонстрация

`applicationId=com.fsm`, baseline versionCode1/versionName1.0; совпадение package/version **не означает** совпадение сертификата. Между средами debug-подпись уже различалась. CI создаёт свои debug artifacts без обещания обновить установленное приложение. До установки сравнить signing certificate/package/version; при несовпадении остановиться, не uninstall/clear data. Сохранять один стабильный локальный debug keystore в приватном профиле того же build-окружения разумно для дальнейших локальных обновлений; не коммитить/публиковать keystore, пароли и private keys, не переносить их в CI artifacts. Инструкция не требует экспорта приватного ключа.

## Продолжение работы

После объединения PR использовать main как общую исходную ветку, а дальнейшие изменения выполнять отдельными feature/fix ветками. Секреты и реальные пользовательские данные в репозиторий не добавлять. Новые проверки записывать отдельно с окружением, командами, фактическим результатом и ограничениями.
