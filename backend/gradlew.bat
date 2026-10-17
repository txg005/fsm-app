@echo off
setlocal DisableDelayedExpansion
rem Bootstrap only verified official bytes, then use the real Gradle wrapper.
set "APP_HOME=%~dp0"
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%APP_HOME%gradle\wrapper\bootstrap.ps1"
if errorlevel 1 exit /b 1
if defined JAVA_HOME goto javaHome
set "JAVA_EXE=java.exe"
goto run
:javaHome
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%JAVA_EXE%" goto run
echo JAVA_HOME must point to a JDK 17 directory, not its bin directory. 1>&2
exit /b 1
:run
"%JAVA_EXE%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain -p "%APP_HOME%." %*
set "EXIT_CODE=%ERRORLEVEL%"
exit /b %EXIT_CODE%
