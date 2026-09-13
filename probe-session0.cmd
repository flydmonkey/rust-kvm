@echo off
rem Wrapper so probe-audio runs in session 0 (where services live). Physical
rem audio endpoints are invisible inside a Remote Desktop session, so this is
rem the only way to see the capture card's audio from here.
rem
rem Two gotchas, both learned the hard way:
rem   1. Passing the whole command line to schtasks /TR gets mangled by its
rem      quote parsing, hence a file.
rem   2. ASCII only, comments included. cmd reads this file in the OEM code
rem      page (GBK here), so UTF-8 text -- even inside rem -- can decode into
rem      something cmd tries to execute.
cd /d "%~dp0"
echo === identity ===
whoami
echo.
"%~dp0target\debug\probe-audio.exe"
