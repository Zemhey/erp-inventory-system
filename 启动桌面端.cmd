@echo off
rem ===========================================================================
rem  PC 端（Electron）双击启动
rem
rem  为什么做成脚本：Electron 的窗口必须在你自己的登录会话里起，
rem  由工具进程拉起的窗口会被回收（表现为"命令跑完了但没窗口"）。
rem
rem  ★ 编码：本文件必须存成 GBK/ANSI + CRLF（和 启动系统.cmd 一样）。
rem    存成 UTF-8 时 cmd 会按 GBK 误读中文注释，把注释当命令执行，
rem    脚本在第一屏就崩掉 —— 表现就是双击后窗口一闪、什么也没有。
rem
rem  前置：app\desktop\node_modules 已存在（缺的话先在 app\desktop 里 npm install）
rem  后端：默认连 http://127.0.0.1:8080，先跑 启动系统.cmd 把后端拉起来
rem ===========================================================================
chcp 936 >nul 2>&1
setlocal
cd /d "%~dp0app\desktop"

if not exist "node_modules\electron\dist\electron.exe" (
  echo [ERROR] Electron not found: app\desktop\node_modules\electron
  echo         run: cd app\desktop ^&^& npm install
  pause
  exit /b 1
)

rem 某些机器/远程会话没有可用 GPU，加上这个开关走软件渲染：
rem   set DESKTOP_DISABLE_GPU=1
set "ELECTRON_RUN_AS_NODE="
start "ERP Desktop" "node_modules\electron\dist\electron.exe" .

rem 起不来时别让窗口一闪而过：等本地端口监听，再给结论
set "PORT=%DESKTOP_PORT%"
if not defined PORT set "PORT=8788"
set "TRIES=0"
:wait_boot
ping -n 2 127.0.0.1 >nul
netstat -ano | findstr /c:"127.0.0.1:%PORT%" | findstr /c:"LISTENING" >nul && goto booted
set /a TRIES+=1
if %TRIES% lss 10 goto wait_boot
echo.
echo [ERROR] 桌面端 10 秒内没起来（本地端口 %PORT% 未监听）。
echo         手动跑一次看报错：
echo           cd /d "%~dp0app\desktop"
echo           node_modules\electron\dist\electron.exe .
echo         常见原因：端口被占用 / Electron 安装不完整 / 无 GPU 环境需要
echo           set DESKTOP_DISABLE_GPU=1
echo         排错文档：app\desktop\README.md
echo.
pause
exit /b 1

:booted
endlocal
exit /b 0
