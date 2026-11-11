#!/system/bin/sh
# Runs as root (uid 0, SELinux permissive) via: libmtksu2.so "sh postroot.sh LIB FILES"
LIB="$1"; FILES="$2"
SSH_READY=0
ADB_ROOT_READY=0
echo "[x08c] uid=$(id -u) enforce=$(getenforce 2>/dev/null)"
echo "[x08c] context=$(id -Z 2>/dev/null)"
WLAN_IP="$(getprop dhcp.wlan0.ipaddress)"
if [ -z "$WLAN_IP" ]; then
    WLAN_IP="$(ip -o -4 addr show dev wlan0 2>/dev/null | awk '{for (i=1; i<=NF; i++) if ($i == "inet") {split($(i+1), a, "/"); print a[1]; exit}}')"
fi
echo "[x08c] network wlan0_ip=$WLAN_IP"

# The stock root filesystem is read-only and does not contain /system/bin/su.
# ADB is already patched to run as uid 0, so a setuid su would add an unsafe
# privilege boundary without providing more access.  Expose a reboot-volatile
# compatibility command for callers that insist on invoking su from an already
# root ADB/SSH session.  It deliberately refuses non-root callers.
SU_COMPAT=/dev/su
cat >"$SU_COMPAT" <<'X08C_SU'
#!/system/bin/sh
if [ "$(id -u)" != "0" ]; then
    echo "x08c su: denied; start a verified root ADB or SSH session first" >&2
    exit 1
fi
if [ "$1" = "-c" ]; then
    shift
    exec /system/bin/sh -c "$*"
fi
if [ "$#" -eq 0 ]; then
    exec /system/bin/sh
fi
exec "$@"
X08C_SU
chown 0:0 "$SU_COMPAT"
chmod 0755 "$SU_COMPAT"
if [ -x "$SU_COMPAT" ]; then
    echo "[x08c] su COMPAT READY: /dev/su (root sessions only; no setuid; cleared on reboot)"
else
    echo "[x08c] su COMPAT FAILED: could not create /dev/su"
fi

is_listening() {
    port="$1"
    grep -i ":$port " /proc/net/tcp 2>/dev/null | grep -q " 0A " && return 0
    grep -i ":$port " /proc/net/tcp6 2>/dev/null | grep -q " 0A "
}

# ---------------- SSHD (dropbear, password, root) ----------------
SSHD=/data/local/sshd

# JobScheduler tears down the app process cgroups after jobFinished().  A root
# daemon left in those cgroups is killed with the app even though it daemonized.
# Move this root shell, and then the final Dropbear child, to the root cgroups.
detach_from_app_cgroups() {
    pid="$1"
    for root_tasks in /dev/cpuctl/tasks /dev/memcg/tasks /acct/tasks; do
        [ -e "$root_tasks" ] && echo "$pid" >"$root_tasks" 2>/dev/null
    done
}
detach_from_app_cgroups "$$"

mkdir -p "$SSHD"
rm -f "$SSHD/.ssh/authorized_keys"
chown -R 0:0 "$SSHD"
chmod 0700 "$SSHD"
[ -f "$SSHD/rsa_host" ] || "$LIB/libdropbear.so" dropbearkey -t rsa -s 2048 -f "$SSHD/rsa_host" >/dev/null 2>&1
[ -f "$SSHD/ed_host" ]  || "$LIB/libdropbear.so" dropbearkey -t ed25519 -f "$SSHD/ed_host" >/dev/null 2>&1
# kill previous instance
[ -f "$SSHD/dropbear.pid" ] && kill "$(cat "$SSHD/dropbear.pid")" 2>/dev/null
"$LIB/libdropbear.so" dropbear -p 2222 -r "$SSHD/rsa_host" -r "$SSHD/ed_host" \
    -P "$SSHD/dropbear.pid" -E </dev/null >/dev/null 2>>"$SSHD/dropbear.log"
DROPBEAR_PID="$(cat "$SSHD/dropbear.pid" 2>/dev/null)"
[ -n "$DROPBEAR_PID" ] && detach_from_app_cgroups "$DROPBEAR_PID"
sleep 1
if is_listening 08AE; then
    SSH_READY=1
    echo "[x08c] ssh READY: port=2222 user=root password=areuok pid=$(cat "$SSHD/dropbear.pid" 2>/dev/null)"
else
    echo "[x08c] ssh FAILED: port=2222 is not listening"
    tail -n 5 "$SSHD/dropbear.log" 2>/dev/null
fi

# ---------------- ADB ----------------
# Native code already restarted and patched adbd while it still held kernel
# R/W. Do not write adb_enabled or USB properties here: framework observers can
# restart adbd again and discard the verified init_cred pointers.
sleep 1
if is_listening 15B3; then
    ADB_PID="$(pidof adbd 2>/dev/null | awk '{print $1}')"
    ADB_UID=""
    [ -n "$ADB_PID" ] && ADB_UID="$(awk '/^Uid:/{print $2; exit}' "/proc/$ADB_PID/status" 2>/dev/null)"
    if [ "$ADB_UID" = "0" ]; then
        ADB_ROOT_READY=1
        echo "[x08c] adb ROOT READY: tcp=5555 pid=$ADB_PID uid=0 (shell is already root; optional /dev/su)"
    else
        echo "[x08c] adb READY but NOT ROOT: tcp=5555 pid=$ADB_PID uid=${ADB_UID:-unknown}"
    fi
else
    echo "[x08c] adb UNAVAILABLE: tcp=5555 is not listening"
    echo "[x08c] adb props: service=$(getprop service.adb.tcp.port) persist=$(getprop persist.adb.tcp.port)"
fi

# ---------------- SHINDO EARTHQUAKE GUARDIAN ----------------
# Keep rua.moe.shindo alive 24/7 with system-level OOM immunity (-900),
# root cgroups, Doze whitelist, and automatic restart if ever killed.
runcon u:r:init:s0 setprop persist.vendor.duraspeed.app.on 0 2>/dev/null
runcon u:r:init:s0 setprop logd.logpersistd.enable false 2>/dev/null
runcon u:r:init:s0 setprop ctl.stop logcatd 2>/dev/null
KEEPER="$SSHD/shindo-keeper.sh"
KEEP_PID_FILE="$SSHD/shindo-keeper.pid"
[ -f "$KEEP_PID_FILE" ] && kill "$(cat "$KEEP_PID_FILE")" 2>/dev/null
cat >"$KEEPER" <<'X08C_KEEPER'
#!/system/bin/sh
for root_tasks in /dev/cpuctl/tasks /dev/memcg/tasks /acct/tasks; do
    [ -e "$root_tasks" ] && echo "$$" >"$root_tasks" 2>/dev/null
done
runcon u:r:shell:s0 dumpsys deviceidle whitelist +rua.moe.shindo >/dev/null 2>&1
for op in SYSTEM_ALERT_WINDOW RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK; do
    runcon u:r:shell:s0 appops set rua.moe.shindo "$op" allow >/dev/null 2>&1
done
while true; do
    PIDS="$(pidof rua.moe.shindo rua.moe.shindo:watchdog 2>/dev/null)"
    if [ -z "$PIDS" ]; then
        runcon u:r:shell:s0 am broadcast -a android.intent.action.BOOT_COMPLETED \
            -n rua.moe.shindo/.BootReceiver --include-stopped-packages >/dev/null 2>&1
        runcon u:r:shell:s0 am broadcast -a android.intent.action.BOOT_COMPLETED \
            -n rua.moe.shindo/com.pravera.flutter_foreground_task.service.RebootReceiver \
            --include-stopped-packages >/dev/null 2>&1
        sleep 4
        PIDS="$(pidof rua.moe.shindo rua.moe.shindo:watchdog 2>/dev/null)"
    fi
    for p in $PIDS; do
        echo -900 >"/proc/$p/oom_score_adj" 2>/dev/null
        renice -n -10 -p "$p" >/dev/null 2>&1
        for root_tasks in /dev/cpuctl/tasks /dev/memcg/tasks /acct/tasks; do
            [ -e "$root_tasks" ] && echo "$p" >"$root_tasks" 2>/dev/null
        done
    done
    sleep 12
done
X08C_KEEPER
chmod 0755 "$KEEPER"
"$KEEPER" </dev/null >/dev/null 2>&1 &
KEEPER_PID="$!"
echo "$KEEPER_PID" >"$KEEP_PID_FILE"
detach_from_app_cgroups "$KEEPER_PID"
echo "[x08c] shindo GUARDIAN READY: pid=$KEEPER_PID oom_score_adj=-900"

if [ "$SSH_READY" = "1" ] && [ "$ADB_ROOT_READY" = "1" ]; then
    echo "[x08c] POSTROOT VERIFIED: ssh=root adb=root"
    echo "[x08c] done. ssh -p2222 root@<ip>  /  adb connect <ip>:5555"
    exit 0
fi
echo "[x08c] POSTROOT FAILED: ssh_ready=$SSH_READY adb_root_ready=$ADB_ROOT_READY"
exit 1
