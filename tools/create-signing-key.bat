@echo off
setlocal EnableExtensions EnableDelayedExpansion
title AirMirror TV - create the app signing key

rem ------------------------------------------------------------------------------------------
rem  Creates the private key that signs every AirMirror TV release, so in-app updates can
rem  install over the previous version. Run it ONCE, then add the two GitHub secrets it shows.
rem  Needs a Java "keytool" (included with Android Studio or any JDK). No admin rights needed:
rem  everything is written to your own user folder.
rem ------------------------------------------------------------------------------------------

set "REPO=mbnetworku/AirPlay-Android-TV-"
set "OUT=%USERPROFILE%\AirMirrorTV-signing"
set "KEYSTORE=%OUT%\airmirror-release.keystore"
set "B64=%OUT%\airmirror-signing-base64.txt"

echo.
echo  ===============================================
echo   AirMirror TV - create the app signing key
echo  ===============================================
echo.

rem --- find keytool ---------------------------------------------------------------------------
set "KEYTOOL="
for %%K in (keytool.exe) do if not "%%~$PATH:K"=="" set "KEYTOOL=%%~$PATH:K"
if not defined KEYTOOL if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
if not defined KEYTOOL if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe" set "KEYTOOL=%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe"
if not defined KEYTOOL if exist "%ProgramFiles%\Android\Android Studio\jre\bin\keytool.exe" set "KEYTOOL=%ProgramFiles%\Android\Android Studio\jre\bin\keytool.exe"
if not defined KEYTOOL (
    echo  [!] Could not find keytool.exe.
    echo      Install Android Studio or a JDK 17, e.g.:  winget install EclipseAdoptium.Temurin.17.JDK
    echo      then run this file again.
    goto :end
)
echo  Using keytool: "%KEYTOOL%"

if exist "%KEYSTORE%" (
    echo.
    echo  [!] A key already exists: "%KEYSTORE%"
    echo      Keep using it. Making a new one means everyone has to reinstall the app once.
    echo      To start over anyway, delete that file and run this again.
    goto :showsecrets
)

rem --- password ------------------------------------------------------------------------------
:askpass
echo.
set "PASS="
set /p "PASS=  Choose a password for the key (6+ letters or numbers): "
if not defined PASS goto :askpass
if "!PASS:~5,1!"=="" (
    echo  [!] Too short.
    goto :askpass
)
if not "!PASS: =!"=="!PASS!" (
    echo  [!] Letters and numbers only, please.
    goto :askpass
)

rem --- create the key --------------------------------------------------------------------------
if not exist "%OUT%" mkdir "%OUT%"
"%KEYTOOL%" -genkeypair -keystore "%KEYSTORE%" -storetype PKCS12 -alias airmirror ^
    -keyalg RSA -keysize 2048 -validity 36500 ^
    -storepass "!PASS!" -keypass "!PASS!" -dname "CN=AirMirror TV" >nul
if errorlevel 1 (
    echo  [!] keytool failed to create the key.
    goto :end
)
echo.
echo  Key created: "%KEYSTORE%"

:showsecrets
rem --- base64 for the GitHub secret, copied to the clipboard ----------------------------------
powershell -NoProfile -Command ^
  "$b=[Convert]::ToBase64String([IO.File]::ReadAllBytes('%KEYSTORE%'));" ^
  "Set-Content -NoNewline -Path '%B64%' -Value $b; Set-Clipboard -Value $b"
if errorlevel 1 (
    echo  [!] Could not encode the key. Open "%B64%" manually if it exists.
    goto :end
)

echo.
echo  ------------------------------------------------------------------------------
echo   Now add 2 secrets on GitHub (the page opens in your browser):
echo.
echo     1. Name:  AIRMIRROR_KEYSTORE_BASE64
echo        Value: press Ctrl+V  (already copied to your clipboard;
echo               also saved in "%B64%")
echo.
echo     2. Name:  AIRMIRROR_KEYSTORE_PASSWORD
echo        Value: the password you chose
echo.
echo   Then on GitHub: Actions - Build APK - Run workflow (branch: main).
echo   It builds and publishes the release that the TVs update from.
echo  ------------------------------------------------------------------------------
echo.
echo   BACK UP "%OUT%" somewhere safe and never share it or commit it.
echo   Lose it, and every TV has to uninstall and reinstall the app once.
echo.
start "" "https://github.com/%REPO%/settings/secrets/actions/new"

:end
echo.
pause
endlocal
