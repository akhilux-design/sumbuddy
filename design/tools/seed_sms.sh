#!/bin/bash
# Fills the emulator's SMS inbox (content://sms/inbox) with the bank messages in design/tools/seed_sms.json.
#
#   design/tools/seed_sms.sh                 empty the inbox, then add every message
#   design/tools/seed_sms.sh --keep          add the messages without emptying the inbox first
#   design/tools/seed_sms.sh --render FILE   only write the messages, with real dates, as a parser sample file
#                                            (format: parser/README.md), to check them with SampleCorpusTest
#
# How it works, and why:
# - 'adb shell content insert --uri content://sms/inbox' only works as root, and its --bind cannot carry a ':' in a
#   value, which most bank SMS have (times, "Avl Limit:"). So it is not used for the text.
# - 'adb emu sms send <sender> <text>' delivers a real incoming SMS through the modem: the row lands in
#   content://sms/inbox with the sender header exactly as given (VM-HDFCBK stays VM-HDFCBK), long text is joined into
#   one row, and "\n" in the text becomes a line break. Its date is "now", so the script then sets the row's date and
#   date_sent with 'content update' (needs 'adb root', which the emulator's userdebug image allows).
# - Each message's date is "daysAgo" days before today at "time" (device time zone). Dates printed in a body come
#   from the placeholders {d:<strftime>} (the message date) and {due:<strftime>} (the date plus "dueInDays").
# - Run it BEFORE installing the app: a live SMS_RECEIVED would otherwise be read by the app as it arrives.
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
SEED="design/tools/seed_sms.json"
MODE="${1:-seed}"

if [ "$MODE" = "--render" ]; then
  python3 -I - "$SEED" "${2:?output file}" <<'PY'
import json, sys, datetime
cases = json.load(open(sys.argv[1]))
now = datetime.datetime.now().astimezone()
out = []
for c in cases:
    hh, mm = map(int, c["time"].split(":"))
    d = (now - datetime.timedelta(days=c["daysAgo"])).replace(hour=hh, minute=mm, second=0, microsecond=0)
    due = d + datetime.timedelta(days=c.get("dueInDays", 0))
    out.append({"id": c["id"], "sender": c["sender"], "body": c["body"].format(d=d, due=due),
                "receivedAt": d.isoformat(), "expect": c["expect"]})
json.dump(out, open(sys.argv[2], "w"), indent=1, ensure_ascii=False)
print(f"wrote {len(out)} cases to {sys.argv[2]}")
PY
  exit 0
fi

"$ADB" root >/dev/null
"$ADB" wait-for-device
until [ "$("$ADB" shell id -u | tr -d '\r')" = "0" ]; do sleep 1; done
if [ "$MODE" != "--keep" ]; then
  "$ADB" shell content delete --uri content://sms >/dev/null
fi

python3 -I - "$SEED" "$ADB" <<'PY'
import json, subprocess, sys, time, datetime
cases, adb = json.load(open(sys.argv[1])), sys.argv[2]
now = datetime.datetime.now().astimezone()

def max_id():
    out = subprocess.run([adb, "shell", "content", "query", "--uri", "content://sms/inbox", "--projection", "_id",
                          "--sort", "'_id DESC LIMIT 1'"], capture_output=True, text=True).stdout
    return int(out.split("_id=")[1].split()[0]) if "_id=" in out else 0

for c in cases:
    hh, mm = map(int, c["time"].split(":"))
    d = (now - datetime.timedelta(days=c["daysAgo"])).replace(hour=hh, minute=mm, second=0, microsecond=0)
    due = d + datetime.timedelta(days=c.get("dueInDays", 0))
    body = c["body"].format(d=d, due=due).replace("\n", "\\n")
    before = max_id()
    subprocess.run([adb, "emu", "sms", "send", c["sender"], body], check=True, capture_output=True)
    for _ in range(40):
        row = max_id()
        if row > before:
            break
        time.sleep(0.25)
    else:
        sys.exit(f"{c['id']}: the message did not reach the inbox")
    millis = str(int(d.timestamp() * 1000))
    subprocess.run([adb, "shell", "content", "update", "--uri", "content://sms", "--bind", f"date:l:{millis}",
                    "--bind", f"date_sent:l:{millis}", "--bind", "read:i:1", "--where", f"_id={row}"], check=True)
    print(f"{row:>3}  {d:%Y-%m-%d %H:%M}  {c['sender']:<12} {c['id']}")
PY
echo "inbox now holds $("$ADB" shell content query --uri content://sms/inbox --projection _id | grep -c '_id=') messages"
