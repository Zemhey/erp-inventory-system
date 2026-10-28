@echo off
rem ===========================================================================
rem  云裳进销存 · 一键启动
rem
rem  只做一件事：把「后端 Spring Boot」和「Web 前端」拉起来。
rem    后端  http://127.0.0.1:8080
rem    Web   http://127.0.0.1:3030
rem
rem  明确不启动：app\ 下的 Android 演示包、app\uniapp 的小程序 / H5、
rem              桌面端，以及任何第二个浏览器窗口。
rem
rem  本文件必须存成 GBK / ANSI（配合下面的 chcp 936），
rem  存成 UTF-8 时 cmd 会把中文读成乱码。
rem
rem  参数会原样传给 scripts\start-system.ps1，例如：
rem    启动系统.cmd -NoBrowser        不自动打开浏览器
rem    启动系统.cmd -SkipInfra        跳过数据库容器检查
rem    启动系统.cmd -WebPort 3000     换前端端口
rem ===========================================================================
chcp 936 >nul 2>&1
setlocal EnableExtensions
cd /d "%~dp0"

set "PS1=%~dp0scripts\start-system.ps1"
set "ROOT=%~dp0"

if not exist "%PS1%" (
  echo.
  echo [错误] 找不到启动脚本：%PS1%
  echo        请确认 scripts\start-system.ps1 没有被移动或删除。
  echo.
  pause
  exit /b 1
)

if not exist "%ROOT%backend\server\gradlew.bat" (
  echo.
  echo [错误] 找不到后端工程：%ROOT%backend\server\gradlew.bat
  echo.
  pause
  exit /b 1
)

if not exist "%ROOT%frontend\web\package.json" (
  echo.
  echo [错误] 找不到前端工程：%ROOT%frontend\web\package.json
  echo.
  pause
  exit /b 1
)

echo.
echo   ============================================================
echo     云裳进销存 · 本机系统启动
echo   ------------------------------------------------------------
echo     后端 Spring Boot   http://127.0.0.1:8080
echo     Web 前端           http://127.0.0.1:3030
echo     不启动             app / 小程序 / H5 / 桌面端
echo   ============================================================
echo.
echo   首次启动需要编译后端（实测 5-7 分钟），后面会复用已运行的服务。
echo   下面会持续打印进度，长时间没动静不是卡死，可以看 .run\logs 下的日志。
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo.
  echo [失败] 启动脚本返回 %RC%，系统没有就绪。
  echo        常见原因：
  echo          1. Docker Desktop 没启动，PostgreSQL / Redis / RabbitMQ 起不来
  echo          2. 8080 或 3030 被别的程序占着
  echo             查看占用：netstat -ano ^| findstr ":8080 :3030"
  echo          3. 首次运行还没装前端依赖（脚本会自动装，需要联网）
  echo.
  echo        日志目录：%ROOT%.run\logs
  echo.
  pause
  exit /b %RC%
)

echo.
echo   [完成] 系统已就绪，浏览器已打开。
echo          本窗口可以直接关掉 —— 前端和后端都在后台继续运行。
echo          要停止：关闭对应的 java / node 进程即可，或见 .run\logs。
echo.
endlocal
exit /b 0
