#!/usr/bin/env bash
# check.sh — запуск ktlint и detekt с сохранением отчётов
# Отчёты: ktlint_report.txt, detekt_report.txt (рядом со скриптом)
# В консоль: только краткий статус

set -u  # не падать на unset-переменных, но реагировать на ошибки вручную

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KT_REPORT="$SCRIPT_DIR/ktlint_report.txt"
DT_REPORT="$SCRIPT_DIR/detekt_report.txt"

echo "[*] Запуск ktlint..."
./gradlew ktlintCheck > "$KT_REPORT" 2>&1
KT_EXIT=$?

echo "[*] Запуск detekt..."
./gradlew detekt > "$DT_REPORT" 2>&1
DT_EXIT=$?

# --- Итог в консоль ---
echo "----------------------------------------"
if [ $KT_EXIT -eq 0 ]; then
    echo "[OK]   ktlint: нарушений нет"
else
    # считаем строки-нарушения (формат: путь:строка:колонка ...)
    KT_COUNT=$(grep -cE '^/.*:[0-9]+:[0-9]+' "$KT_REPORT" || true)
    echo "[FAIL] ktlint: нарушений найдено: ${KT_COUNT:-0}  → $KT_REPORT"
fi

if [ $DT_EXIT -eq 0 ]; then
    echo "[OK]   detekt: проблем нет"
else
    # detekt в консоли пишет "path:line:col: message [Rule]" — считаем такие строки
    DT_COUNT=$(grep -cE '^/.*:[0-9]+:[0-9]+' "$DT_REPORT" || true)
    echo "[FAIL] detekt: проблем найдено: ${DT_COUNT:-0}  → $DT_REPORT"
fi
echo "----------------------------------------"

# Код возврата: 0 только если обе проверки прошли
if [ $KT_EXIT -eq 0 ] && [ $DT_EXIT -eq 0 ]; then
    exit 0
else
    exit 1
fi