@echo off
chcp 65001 >nul
echo ========================================
echo HBC-Tool Android 快速构建脚本
echo ========================================
echo.

REM ---- 使用本机已安装的环境（无需全局配置 JAVA_HOME / ANDROID_HOME）----
REM JDK 21（AGP 8.2 / Gradle 8.9 兼容；Android Studio 自带的 JBR 是 JDK25，过新）
if "%JAVA_HOME%"=="" set "JAVA_HOME=E:\home\jdk-21.0.12.1"
REM 本机 Android SDK（已含 platform android-34 / build-tools 34.0.0 / NDK）
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=E:\home\SDK"
if "%ANDROID_SDK_ROOT%"=="" set "ANDROID_SDK_ROOT=E:\home\SDK"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

echo 使用 JDK: %JAVA_HOME%
echo 使用 SDK: %ANDROID_HOME%
echo.

echo [1/4] 检查环境...
where gradlew.bat >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ 错误: 请在项目根目录运行此脚本
    pause
    exit /b 1
)
echo ✅ 环境检查通过

echo.
echo [2/4] 清理旧构建...
call gradlew.bat clean
if %errorlevel% neq 0 (
    echo ⚠️  清理失败，继续构建...
)

echo.
echo [3/4] 开始构建 (首次可能需要 10-15 分钟)...
echo 提示: Chaquopy 会下载 Python 3.8 和依赖库
echo.
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo.
    echo ❌ 构建失败！
    echo 请检查错误信息或在 Android Studio 中查看详细日志
    pause
    exit /b 1
)

echo.
echo [4/4] 构建完成！
echo.
echo ========================================
echo ✅ APK 已生成
echo ========================================
echo.
echo 位置: app\build\outputs\apk\debug\app-debug.apk
echo.
echo 下一步:
echo   1. 安装到设备: adb install app\build\outputs\apk\debug\app-debug.apk
echo   2. 或在 Android Studio 中点击 Run 按钮
echo.

pause
