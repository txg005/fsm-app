# Windows: независимые сборки Android и backend

## Статус и версии

Два отдельных Gradle root: открывать `android/` в Android Studio, backend собирать из `backend/`. Корень репозитория не является общим Gradle-проектом. Код приложения, API, БД, Kotlin и AGP этим изменением не затронуты.

- Android: **Gradle 8.13**, AGP 8.10.1, Kotlin 2.1.21, **JDK 17**.
- Backend: **Gradle 8.14.3**, Kotlin 2.1.21, **JDK 17**.
- Android SDK: platform `android-36`, Build Tools `36.0.0`, Platform Tools; принять лицензии через SDK Manager.
- Windows launchers рассчитаны на Windows PowerShell **5.1** (`powershell.exe`, не требуется `pwsh`), Java 17 и доступ к официальным HTTPS-источникам. Администратор, WSL, GNU base64/sha256sum/unzip или установленный системный Gradle не нужны.

**Windows/cmd/PowerShell/Android Studio здесь НЕ исполнены:** среда проверки — Linux, без Windows и PowerShell. Ниже инструкция и список проверок для Windows, а не заявление Windows PASS. Две Linux `--version` прошли; компиляция этим этапом не запускалась.

## 1. JDK 17 без путаницы с Android Studio JBR

Если установлен полноценный JDK 17, можно выбрать его. Не использовать JRE, Java 21/25 из PATH или встроенный JBR Android Studio только потому, что он новее. Среда запуска IDE и Gradle JDK — разные настройки. Автоматическая загрузка JDK сборкой не настроена.

Воспроизводимый вариант для **Windows x64** — официальный Amazon Corretto **17.0.20.12.1**, runtime **17.0.20.1+12-LTS**. ZIP скачан с официального HTTPS-адреса, SHA-256 вычислен при подготовке этого документа; Windows binary не исполнялся. Это зафиксированный hash полученного официального ZIP, а не заявление об отдельной опубликованной AWS SHA-256/signature-проверке. Для ARM64 нужен подходящий JDK 17 другого официального пакета и его собственная проверенная сумма.

PowerShell, без администратора:

```powershell
$zip = Join-Path $env:TEMP 'amazon-corretto-17.0.20.12.1-windows-x64-jdk.zip'
$url = 'https://corretto.aws/downloads/resources/17.0.20.12.1/amazon-corretto-17.0.20.12.1-windows-x64-jdk.zip'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
$expected = '1ac8d9dc48b170684ac6223113f524325bd842d14ed74dac28927f8165cffd3a'
if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'JDK SHA-256 mismatch: do not extract or run'
}
$jdks = Join-Path $env:LOCALAPPDATA 'FSM\jdks'
# Для первой установки: если каталог уже существует, сначала проверить его, не перетирать.
Expand-Archive -LiteralPath $zip -DestinationPath $jdks
$env:JAVA_HOME = Join-Path $jdks 'jdk17.0.20_12'
& "$env:JAVA_HOME\bin\java.exe" -version
```

Ожидается `17.0.20.1`. `JAVA_HOME` указывает на каталог JDK, **не** `bin` и без встроенных кавычек. Значение `$env:JAVA_HOME` действует в текущем PowerShell и его дочерних процессах. Для другого уже установленного JDK 17 заменить только путь. Не требуется менять системный PATH; wrappers предпочитают `JAVA_HOME`. Если его нет, используется `java` из PATH — это частая причина неправильного JDK.

Для отдельного окна **cmd** задать ту же установку отдельно:

```bat
set "JAVA_HOME=%LOCALAPPDATA%\FSM\jdks\jdk17.0.20_12"
"%JAVA_HOME%\bin\java.exe" -version
```

## 2. Первый bootstrap и Android Studio

Из корня checkout (пример пути с пробелами):

```powershell
Set-Location 'C:\Work projects\fsm-app'
# Только загрузка и SHA-проверка wrapper JAR, без запуска JVM:
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File '.\android\gradle\wrapper\bootstrap.ps1'
if ($LASTEXITCODE -ne 0) { throw 'Android wrapper bootstrap failed' }
# Запуск Java 17 и загрузка pinned Gradle distribution с проверкой SHA:
.\android\gradlew.bat --no-daemon --no-parallel --max-workers=1 --version
if ($LASTEXITCODE -ne 0) { throw 'Android wrapper smoke failed' }
```

`.bat` сам вызывает bootstrap, поэтому отдельная команда нужна главным образом **до открытия Android Studio** или для восстановления JAR без JVM. `-ExecutionPolicy Bypass` относится только к дочернему процессу; `Set-ExecutionPolicy`, изменение реестра или глобальное ослабление политики не выполняются. Если корпоративная Group Policy блокирует скрипт, согласовать разрешённый запуск с администратором; не отключать системные требования безопасности.

В Android Studio:

1. **Open** → выбрать именно `fsm-app\android`, не корень репозитория и не `android\app`.
2. Settings → Build, Execution, Deployment → Build Tools → Gradle: distribution **Wrapper / gradle-wrapper.properties**; **Gradle JDK → выбранный JDK 17** из шага 1 (Add JDK при необходимости). Не выбирать bundled JBR 21/25, если он не Java 17. Терминальный `JAVA_HOME` сам по себе не гарантирует тот же JDK в IDE.
3. SDK Manager: SDK Platform 36, Build Tools 36.0.0, Platform Tools, принять лицензии. Указать SDK через Studio (локальный `local.properties`) либо для CLI `$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'`, если SDK действительно установлен там. `local.properties` не коммитить; он содержит путь конкретного компьютера.
4. Выполнить Sync. При предложении обновить AGP/Kotlin/Gradle отказаться для воспроизводимости этого baseline. После Sync выбрать модуль `app` и нужный эмулятор/телефон.

Если Studio сообщает об отсутствующем `gradle-wrapper.jar`, закрыть Sync, выполнить bootstrap выше и повторить. Не скачивать случайный JAR и не создавать пустой файл. В репозиторий JAR не включён из-за текстового канала публикации; bootstrap создаёт **подлинный** файл рядом с properties, локальный `.gitignore` исключает его и временные файлы.

## 3. Команды сборки

PowerShell, в том же окне с `JAVA_HOME=JDK17`:

```powershell
Set-Location 'C:\Work projects\fsm-app\android'
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 testDebugUnitTest lintDebug assembleDebug
# Только сборка instrumentation APK (не исполнение тестов):
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 :app:assembleDebugAndroidTest
# Только с подключённым эмулятором/устройством:
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 connectedDebugAndroidTest

Set-Location '..\backend'
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --version
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 test installDist
# integrationTest — отдельно, только с приватной конфигурацией disposable PostgreSQL:
# .\gradlew.bat --no-daemon --no-parallel --max-workers=1 integrationTest
```

Проверять `$LASTEXITCODE` после каждой команды; не продолжать после ошибки. В cmd из соответствующего каталога те же аргументы: `gradlew.bat ...` (без `./`); из другого `.bat` использовать `call gradlew.bat ...`, затем проверять `if errorlevel 1 exit /b 1`.

Не запускать обе тяжёлые сборки одновременно на малой RAM. Версии и resource limits уже заданы в Gradle properties; скрипты запускают wrapper JVM с `-Xmx64m -Xms64m`. Настройки JVM сборки задавать через `org.gradle.jvmargs`/`-Dorg.gradle.jvmargs=...`, а не считать, что эти 64 MiB ограничивают всю сборку. Специальная обработка `JAVA_OPTS`/`GRADLE_OPTS` этими небольшими launchers не предоставляется.

APK: `android\app\build\outputs\apk\debug\app-debug.apk`; backend distribution: `backend\build\install\fsm-backend`. Сборка backend не запускает PostgreSQL/сервер и не заменяет настройку переменных окружения. Compose-сборка backend использует Dockerfile, а не host wrapper.

## 4. Что проверяется при bootstrap

Текстовые `gradlew` и `gradlew.bat` запускают **настоящий** `org.gradle.wrapper.GradleWrapperMain`, не собственную реализацию Gradle. Первое скачивание JAR — из официального `gradle/gradle`, тег закреплён в `gradle-wrapper.jar.url`; checksum хранится в `gradle-wrapper.jar.sha256` и проверяется **до установки и перед каждым запуском через launcher**. Android Studio затем использует уже подготовленный JAR напрямую.

| Root | Official wrapper source | SHA-256 wrapper JAR |
| --- | --- | --- |
| Android | `gradle/gradle`, tag `v8.13.0` | `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` |
| Backend | `gradle/gradle`, tag `v8.14.3` | `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172` |

Обе суммы JAR сверены с `https://services.gradle.org/distributions/gradle-<version>-wrapper.jar.sha256`, полученные настоящие JAR совпали. Distribution URL в properties — `https://services.gradle.org/distributions/gradle-<version>-bin.zip`, SHA:

- 8.13: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
- 8.14.3: `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.

Суммы distribution сверены с официальными `*-bin.zip.sha256`. Настоящий wrapper проверяет ZIP при первом скачивании (`distributionSha256Sum`), хранит Gradle в пользовательском `GRADLE_USER_HOME` / `%USERPROFILE%\.gradle\wrapper\dists`. Это стандартный кеш, не полная повторная проверка всех распакованных файлов на каждом запуске. Старый backend `.base64` сохранён без изменений для истории, новые launchers его не используют.

При неверной SHA JAR или ошибке загрузки — ненулевой exit, Java не запускается; автоматического fallback на системный Gradle/другую версию нет. Если проверка существующего JAR не проходит, сначала выяснить причину; для повторной загрузки удалить **только** локальный `gradle\wrapper\gradle-wrapper.jar` соответствующего root и повторить bootstrap. Не обходить SHA/TLS-проверки. Первый запуск требует сети; повторный использует проверенный JAR и обычный кеш Gradle. Linux: `JAVA_HOME=/path/to/jdk17 sh android/gradlew --version` / `sh backend/gradlew --version`; нужны `curl`, `sha256sum`, `mktemp`, `tr` и POSIX shell. Executable bit не требуется.

## 5. Безопасность существующего Windows окружения

Не удалять и не перезаписывать существующий `.env`; не публиковать его или вывод `docker compose config` с секретами. Для проверки конфигурации использовать только `docker compose config --quiet`. Не запускать `docker compose down -v`, `docker volume rm` или prune volumes: это удаляет БД/фото. `docker compose down` без `-v` сохраняет named volumes. Wrapper не читает и не меняет `.env`, Compose, БД или тома. Не коммитить `local.properties`, JDK/SDK, `.gradle`, `build`, tools или восстановленный JAR.

## 6. Фактические проверки и Windows checklist

На Linux, 2026-10-17, последовательно, isolated fresh `GRADLE_USER_HOME`:

```sh
JAVA_HOME=/data/fsm-android-work/tools/jdk17 GRADLE_USER_HOME=/data/fsm-windows-stage2/gradle-home sh android/gradlew --no-daemon --no-parallel --max-workers=1 --version
JAVA_HOME=/data/fsm-backend-work/tools/jdk17 GRADLE_USER_HOME=/data/fsm-windows-stage2/gradle-home sh backend/gradlew --no-daemon --no-parallel --max-workers=1 --version
```

Обе exit 0: Gradle 8.13 / 8.14.3, launcher JVM Corretto 17.0.20.1. Свежие distribution скачаны штатным wrapper с закреплёнными суммами. Не запускались compile/test/daemon задачи. `sh -n android/gradlew`, `sh -n backend/gradlew`, `git diff --check` — PASS. Shell mock tests обеих roots — PASS: checkout и JDK path с пробелами; пробелы/кавычки/wildcard в аргументах сохраняются; exit code Java передаётся; неверный SHA существующего/скачанного JAR блокирует Java; ошибка сети не устанавливает JAR; временные файлы удаляются. Проверены `.gitignore`, allowlist изменённых путей и отсутствие бинарных файлов в payload.

На Windows ещё выполнить, результаты записать отдельно:

- [ ] PowerShell 5.1 и cmd: `.bat --version` для обоих root, Gradle/JVM версии как выше, ненулевые exits не теряются.
- [ ] Fresh clone в каталоге с пробелами; JDK в каталоге с пробелами; запуск как из root, так и из каталога проекта. Повторный запуск с кешем.
- [ ] Неверный SHA в **временной копии** wrapper-папки/launcher (не в рабочем checkout): bootstrap/.bat должны отказать до Java; недоступная сеть при отсутствии JAR должна дать ошибку без установки. Не создавать/коммитить фиктивный JAR.
- [ ] Bootstrap перед Open Android Studio, wrapper distribution, явно JDK 17, SDK 36: Sync, unit/lint/debug APK; backend unit/installDist независимо от Android SDK.
- [ ] Android instrumentation tests — отдельно на устройстве; Windows сборка/Sync не доказывают camera/GPS/FGS/БД/Compose durability.
