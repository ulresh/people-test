#!/bin/bash
# Замер на устройстве: для каждой комбинации «размер входа / обработчиков / потоков»
# перезапускает приложение и печатает строку статистики из окна настроек.
# Запускать на хосте сборки (там виден adb). Остальные настройки сбрасываются к умолчанию.
# Использование: scripts/bench.sh [секунд_на_замер] [комбинации "индекс_входа:обработчиков:потоков" ...]
#   индекс входа: 0=64 1=96 2=128 3=160 4=192 5=256
PKG=com.github.ulresh.people_test
WAIT=${1:-20}
shift
COMBOS=("$@")
[ ${#COMBOS[@]} -eq 0 ] && COMBOS=(2:1:1 2:1:4 2:4:1 2:2:2)
for c in "${COMBOS[@]}"; do
    IFS=: read -r idx w t <<<"$c"
    adb shell am force-stop $PKG
    adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs; echo \"<map><int name=\\\"input\\\" value=\\\"$idx\\\" /><int name=\\\"workers\\\" value=\\\"$((w-1))\\\" /><int name=\\\"threads\\\" value=\\\"$((t-1))\\\" /></map>\" > shared_prefs/settings.xml'"
    adb logcat -c
    adb shell am start -n $PKG/.MainActivity >/dev/null
    sleep "$WAIT"
    echo -n "вход=$idx обработчиков=$w потоков=$t: "
    adb logcat -d -s people_stats | tail -1 | sed 's/.*people_stats: //'
done
