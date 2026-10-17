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

Для сервера создайте локальный `.env` из `.env.example`, замените все заглушки, затем выполните из корня:

```sh
docker compose config --quiet
docker compose up --build -d
```

Проверка сервера: `http://127.0.0.1:8080/health` должна вернуть `ok`. Android-эмулятор использует `http://10.0.2.2:8080`. Для физического телефона нужен отдельный доступный адрес или настроенный USB-туннель. Локальный HTTP — только для разработки; внешнее развёртывание требует HTTPS.

Не публикуйте `.env`, пароли или токены. `docker compose config` без `--quiet` может вывести подставленные секреты. `docker compose down -v` удаляет демонстрационные данные и фото.

## Статус проверки

Код MVP реализован и прошёл целевое ревью. Backend: 9 unit + 11 PostgreSQL integration tests; Android: 13 JVM tests, lint без ошибок (19 предупреждений), debug APK и instrumentation APK собраны. Пользователь подтвердил основной сценарий на Windows/Docker Compose и Android-эмуляторе, включая офлайн-работу, перезапуск, отправку фото и разделение аккаунтов.

Это не полная сертификация всех сценариев: 14 Android instrumentation tests только скомпилированы, не выполнены; физическое устройство и полная матрица фоновых ограничений, landscape и отказов остаются отдельной проверкой. Подробности и различие автоматических/ручных результатов — в [ACCEPTANCE.md](docs/ACCEPTANCE.md).

## Документы проекта

- [Правила разработки](CLAUDE.md)
- [Задание и объём первого этапа](docs/TASK.md)
- [Согласованные решения](docs/DECISIONS.md)
- [Порядок работы команды агентов](AGENTS.md)
- [Архитектура](docs/architecture.md)
- [API v1.0](docs/api-contract.md)
- [План реализации](docs/implementation-plan.md)
- [Матрица проверок](docs/acceptance-and-risks.md)
