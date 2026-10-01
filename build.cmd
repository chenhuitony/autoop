@echo off
rem AutoOp 一键构建（cmd 版），逻辑与 build.ps1 相同
setlocal
cd /d "%~dp0"

where mvn >nul 2>nul
if %ERRORLEVEL%==0 (
    echo === 用 Maven 构建 ===
    call mvn -q -DskipTests package
    if exist "target\AutoOp-1.0.0.jar" (
        echo [OK] 构建完成: %CD%\target\AutoOp-1.0.0.jar
        goto :done
    )
    echo [WARN] Maven 构建失败，继续尝试 Gradle
)

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
    echo === 用 Gradle 构建 ===
    call gradle jar --no-daemon
    if exist "build\libs\AutoOp-1.0.0.jar" (
        echo [OK] 构建完成: %CD%\build\libs\AutoOp-1.0.0.jar
        goto :done
    )
    echo [WARN] Gradle 构建失败
)

echo.
echo [FAIL] 没找到可用的 Maven / Gradle。
echo        安装其一后重试:  winget install Apache.Maven
echo        或看 README.md 里的「方式三: javac 手动编译」。
exit /b 1

:done
echo.
echo 把 jar 放进服务器的 plugins 目录，然后重启服务器。
endlocal
