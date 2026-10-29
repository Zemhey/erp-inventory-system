@echo off
rem ===========================================================================
rem  微信开发者工具 · 安装器（已下载）
rem
rem  静默安装需要管理员权限；从脚本直接跑会被 UAC 拦掉，
rem  所以这里就用普通方式打开安装向导 —— 在向导里把安装目录选成：
rem      D:\Major_Software\微信web开发者工具
rem
rem  安装完成后用它导入小程序产物：
rem      D:\MajorCacheFolder\Company system\自选\app\uniapp\dist\build\mp-weixin
rem  并在「详情 - 本地设置」里勾上"不校验合法域名"，否则 http 后端会被拦。
rem ===========================================================================
chcp 936 >nul 2>&1
setlocal
cd /d "%~dp0.tmp"

if not exist "wxdev-setup.exe" (
  echo [ERROR] installer missing: .tmp\wxdev-setup.exe
  pause
  exit /b 1
)

start "" "wxdev-setup.exe"
endlocal
exit /b 0
