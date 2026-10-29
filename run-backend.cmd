@echo off
REM 演示数据回填专用启动包装：打开业务时钟覆盖，让倒签单据的账目归属期间正确。
REM 为什么要有这一层：演示时钟覆盖只能放在进程环境里；仓库路径已改英文，直接相对启动。
REM 同时 ERP_DEMO_CLOCK_OVERRIDE 只能在进程环境里给，写在 yml 会跟着仓库泄露。
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.9
set ERP_DEMO_CLOCK_OVERRIDE=true
cd /d "%~dp0backend\server"
call gradlew.bat bootRun --console=plain
