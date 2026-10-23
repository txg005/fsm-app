# FSM App

Система учёта полевых работ: Android-приложение инженера и REST-сервер.

## Состав

- `android/`: Kotlin, Jetpack Compose, Room, Coroutines/Flow, Ktor client; локальные черновики, фото/GPS, durable Outbox, Foreground Service и журнал.
- `backend/`: Kotlin/Ktor, PostgreSQL, авторизация, приватное хранение фото, атомарное закрытие заявок и идемпотентность.
- `compose.yaml`: сервер и PostgreSQL для локального запуска.

## Запуск

- [Сервер, переменные окружения и Docker Compose](backend/README.md)
- [Android: сборка, эмулятор, разрешения и демонстрационный сценарий](android/README.md)
- [Итоговая проверка и остающиеся ограничения](docs/ACCEPTANCE.md)
- [Демонстрация за 5–10 минут и её предусловия](docs/DEMO.md)
- [Windows: JDK, wrapper и Android Studio](docs/BUILD-WINDOWS.md)
- [CI: команды, артефакты и границы покрытия](docs/CI.md)

Для первой настройки создайте локальный `.env` из `.env.example`, **только если `.env` ещё нет**, и замените заглушки. Существующий `.env` не перезаписывать. Затем выполните из корня:

```sh
docker compose config --quiet
docker compose up --build -d
```

Проверка сервера: `http://127.0.0.1:8080/health` должна вернуть `ok`. Android-эмулятор использует `http://10.0.2.2:8080`. Для физического телефона нужен отдельный доступный адрес или настроенный USB-туннель. Локальный HTTP — только для разработки; внешнее развёртывание требует HTTPS.

Не публикуйте `.env`, пароли или токены. `docker compose config` без `--quiet` может вывести подставленные секреты. `docker compose down -v` удаляет демонстрационные данные и фото.

## Статус проверки

Актуальная проверенная исходная точка: main `c651a29c659f79baa3f39e5d8ef61d56300d0856` после объединения PR9–12. [Итоговый CI run 39797634913](https://github.com/txg005/fsm-app/actions/runs/39797634913): backend **9 unit + 15 PostgreSQL integration**, Android **25 JVM + 21 реально исполненный instrumentation test на API36/KVM**, все без failures/errors/skips; lint — **0 errors**, оба APK и backend installDist — PASS. Число предупреждений последнего lint здесь не пересчитывалось; 19 warnings относится к прежнему baseline.

Stage3 проверил landscape/IME и Activity recreation пяти основных экранов в одном hosted-эмуляторе. Регрессия выявила преждевременный сброс `saving` при очереди локальных записей; после минимального pending-count fix повторный прогон GREEN. Четыре новые HTTP/PostgreSQL-регрессии покрывают upload/replay, дедупликацию фото и доступ к историческим receipts/фото. Подробности и сохранённая история baseline — в [ACCEPTANCE.md](docs/ACCEPTANCE.md).

Отдельно пользователь сообщил об успешных Windows wrapper `--version` для обоих проектов, backend `test installDist`, Android unit/lint/debug APK и эмуляторном сценарии нового UI: engineer2, фото/GPS, два ACK и подтверждённое CLOSED. Это пользовательские сообщения, **не автоматическая Windows/device-матрица**. История первого этапа сохранена в [ACCEPTANCE.md](docs/ACCEPTANCE.md).

Полной приёмки пока нет: физический телефон, process death/force-stop, полный lifecycle/permission/Service E2E и Netty packet-drop остаются отдельными проверками. Activity recreation не равна process death; переход Room ACK → UI не доказывает реальную передачу Service. Instrumentation использует Room/fixtures/mock HTTP, не настоящий backend. CI APK не обещает обновление уже установленного APK: debug-подписи разных сред могут различаться; не удаляйте приложение/данные для обхода несовпадения. Условия повторной демонстрации и сохранности данных — в [DEMO.md](docs/DEMO.md).

## Документы проекта

- [Правила разработки](CLAUDE.md)
- [Задание и объём первого этапа](docs/TASK.md)
- [Согласованные решения](docs/DECISIONS.md)
- [Порядок работы команды агентов](AGENTS.md)
- [Архитектура](docs/architecture.md)
- [API v1.0](docs/api-contract.md)
- [План реализации](docs/implementation-plan.md)
- [Матрица проверок](docs/acceptance-and-risks.md)
