@echo off
cd /d "%~dp0"
echo Starting FireDeck server on port 9100...
echo Open http://localhost:9100 in your browser to configure.
start "" http://localhost:9100
python -u deck_server.py --port 9100
if errorlevel 1 py -3 -u deck_server.py --port 9100
pause
