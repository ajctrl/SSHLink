@echo off
setlocal EnableDelayedExpansion
set GRADLE_VERSION=9.5.0
set GRADLE_SHA256=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
if "%GRADLE_USER_HOME%"=="" (set BASE_DIR=%USERPROFILE%\.gradle\sshlink-bootstrap) else (set BASE_DIR=%GRADLE_USER_HOME%\sshlink-bootstrap)
set ZIP=%BASE_DIR%\gradle-%GRADLE_VERSION%-bin.zip
set DIST=%BASE_DIR%\gradle-%GRADLE_VERSION%
set URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip

if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%BASE_DIR%" mkdir "%BASE_DIR%"
  if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%URL%' -OutFile '%ZIP%'"
  for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%ZIP%').Hash.ToLower()"') do set ACTUAL=%%H
  if /I not "!ACTUAL!"=="%GRADLE_SHA256%" (
    echo Gradle distribution checksum mismatch 1>&2
    del /q "%ZIP%" 2>nul
    exit /b 1
  )
  if exist "%DIST%" rmdir /s /q "%DIST%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%BASE_DIR%'"
)
call "%DIST%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
