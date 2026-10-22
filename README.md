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

Проверенная исходная точка: main `0c3f0ed0e4e4ba630b4f1bb66e8d530d7808c292`. [CI run 39741466813](https://github.com/txg005/fsm-app/actions/runs/39741466813) завершил все три job: backend **9 unit + 11 PostgreSQL**, Android **25 JVM + 15 реально исполненных instrumentation tests на API36/KVM**, без failures/errors/skips; lint без ошибок (19 предупреждений), оба APK собраны. Название instrumentation job сохраняет «14-test»: это baseline floor, фактический результат — 15.

Отдельно пользователь сообщил об успешных Windows wrapper `--version` для обоих проектов, backend `test installDist`, Android unit/lint/debug APK и эмуляторном сценарии нового UI: engineer2, фото/GPS, два ACK и подтверждённое CLOSED. Это пользовательские сообщения, **не автоматическая Windows/device-матрица**. История первого этапа сохранена в [ACCEPTANCE.md](docs/ACCEPTANCE.md).

Полной приёмки пока нет: landscape/recreation/lifecycle Stage3, физический телефон и полная матрица ограничений Android остаются отдельными проверками. Instrumentation использует Room/fixtures/mock HTTP, не настоящий backend. CI APK не обещает обновление уже установленного APK: debug-подписи разных сред могут различаться; не удаляйте приложение/данные для обхода несовпадения. Условия повторной демонстрации и сохранности данных — в [DEMO.md](docs/DEMO.md).

## Документы проекта

- [Правила разработки](CLAUDE.md)
- [Задание и объём первого этапа](docs/TASK.md)
- [Согласованные решения](docs/DECISIONS.md)
- [Порядок работы команды агентов](AGENTS.md)
- [Архитектура](docs/architecture.md)
- [API v1.0](docs/api-contract.md)
- [План реализации](docs/implementation-plan.md)
- [Матрица проверок](docs/acceptance-and-risks.md)
