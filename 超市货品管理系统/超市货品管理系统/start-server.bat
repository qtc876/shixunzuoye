@echo off
cd /d "%~dp0"
echo Starting SupermarketServer...
java SupermarketServer > server.log 2>&1
echo Server exited with code %errorlevel%
pause
