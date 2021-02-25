@echo off

set EVENT_FILE=%1
set TOPIC=%2

docker cp %EVENT_FILE% kafka:/event.txt ^
    && docker cp produce_event.sh kafka:/produce_event.sh ^
    && docker exec kafka bash /produce_event.sh %TOPIC% 