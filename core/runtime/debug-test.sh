#!/bin/sh
# Off-car test of the Debug tab's engine side (engine debug): the dumps land on the USB
# stick only, scan finds only what no installed package owns, clean needs scan's token
# and never leaves the app folders, and wipe-data refuses until it's told.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root; U=$T/usb0
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_USB_DIRS=$U java -cp "$CP" hmgapi.engine.Engine debug "$@" 2>&1); rc=$?; set -e; }

mkdir -p $R/data/hmgapi/state $R/data/hmgapi/stock $R/data/hmgapi/logs/hmgapi $R/data/system $R/data/data $R/data/app \
    $R/data/app-lib $R/data/dalvik-cache $R/data/nemesis/backup/x $R/data/misc/x $R/system/app $R/system/framework \
    $R/data/tombstones $R/cache/recovery
cat > $R/data/system/packages.xml <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<packages>
<package name="com.kept.app" codePath="/data/app/com.kept.app-1.apk" nativeLibraryPath="/data/app-lib/com.kept.app-1" flags="0" />
<package name="com.hkmc.stock" codePath="/system/app/Stock.apk" nativeLibraryPath="/data/app-lib/Stock" flags="1" />
<updated-package name="com.hkmc.updated" codePath="/system/app/Updated.apk" />
</packages>
EOF
printf 'com.listed.only 10050 0 /data/data/com.listed.only default\n' > $R/data/system/packages.list
for p in com.kept.app com.hkmc.stock com.hkmc.updated com.listed.only com.gone.app weird_dir; do mkdir -p $R/data/data/$p; done
echo x > $R/data/data/com.gone.app/file
ln -s /data/app-lib/com.gone.app-1 $R/data/data/com.gone.app/lib            # inside an orphan: goes with it
ln -s $R/data/app-lib/missing-1 $R/data/data/com.kept.app/lib                # dangling, in an app folder
ln -s $R/data/misc/nothing $R/data/misc/x/lock                               # dangling, outside: kept
ln -s $R/data/hmgapi/nothing $R/data/hmgapi/state/dead                       # never looked at
echo apk > $R/data/app/com.kept.app-1.apk; echo apk > $R/data/app/com.gone.app-1.apk; echo t > $R/data/app/vmdl123.tmp
mkdir -p $R/data/app-lib/com.kept.app-1 $R/data/app-lib/com.gone.app-1 $R/data/app-lib/Stock
echo apk > $R/system/app/Stock.apk; echo jar > $R/system/framework/core.jar; echo odex > $R/system/framework/core.odex
echo d > $R/data/dalvik-cache/system@app@Stock.apk@classes.dex
echo d > $R/data/dalvik-cache/system@app@Removed.apk@classes.dex
echo d > $R/data/dalvik-cache/data@app@com.gone.app-1.apk@classes.dex
echo crash > $R/data/tombstones/tombstone_00; echo log > $R/data/hmgapi/hmgapid.log; echo log > $R/data/hmgapi/logs/hmgapi/log.log
echo stockbytes > $R/data/hmgapi/stock/app_process

# ---- dumps: USB only
eng dump-sys
check "dump without a stick refused" 1 $rc
has "and says why" "no USB stick" "$out"
mkdir -p $U
eng dump-sys
check "dump-sys" 0 $rc
d=$(ls -d "$U/HMG-API debug/"*-sys)
check "README on the stick" yes $([ -s "$d/README.txt" ] && echo yes || echo no)
check "props captured (host: the command noted)" yes $([ -f "$d/getprop.txt" ] && echo yes || echo no)
eng dump-logs
check "dump-logs" 0 $rc
d=$(ls -d "$U/HMG-API debug/"*-logs)
check "tombstones copied" crash "$(cat "$d/data_tombstones/tombstone_00")"
check "HMG-API's log copied" log "$(cat "$d/data_hmgapi_logs/hmgapi/log.log")"
check "logcat buffers" 4 "$(ls "$d" | grep -c '^logcat-')"
eng dump-apks
check "dump-apks" 0 $rc
d=$(ls -d "$U/HMG-API debug/"*-apks)
check "system app copied" apk "$(cat "$d/system/app/Stock.apk")"
check "framework odex copied" odex "$(cat "$d/system/framework/core.odex")"
has "md5 list" "/data/app/com.kept.app-1.apk" "$(cat "$d/md5s.txt")"
check "nothing written to /data" "" "$(find $R/data -newer $R/data/system/packages.xml -type f -path '*debug*')"

# ---- scan
eng scan
check "scan" 0 $rc
has "orphan data found" "orphan-data remove" "$(echo "$out" | grep com.gone.app)"
hasnt "installed app's data not listed" "/data/data/com.kept.app " "$out"
hasnt "updated-package counts as installed" "com.hkmc.updated" "$out"
hasnt "packages.list counts as installed" "com.listed.only" "$out"
has "non-package folder kept" "orphan-data keep 0 /data/data/weird_dir" "$out"
has "orphan apk" "orphan-apk remove 4 /data/app/com.gone.app-1.apk" "$out"
has "unfinished install" "/data/app/vmdl123.tmp -- unfinished install" "$out"
hasnt "installed apk not listed" "com.kept.app-1.apk" "$out"
has "orphan libs" "orphan-lib remove 0 /data/app-lib/com.gone.app-1" "$out"
hasnt "installed libs not listed" "app-lib/Stock" "$out"
has "orphan dex (system)" "system@app@Removed.apk@classes.dex" "$out"
has "orphan dex (data)" "data@app@com.gone.app-1.apk@classes.dex" "$out"
hasnt "live dex not listed" "system@app@Stock.apk" "$out"
has "dangling link in an app folder" "dangling-link remove 0 /data/data/com.kept.app/lib" "$out"
has "dangling link elsewhere kept" "dangling-link keep 0 /data/misc/x/lock" "$out"
hasnt "HMG-API's folder never walked" "/data/hmgapi" "$out"
hasnt "a link inside an orphan isn't listed twice" "/data/data/com.gone.app/lib" "$out"
tok=$(echo "$out" | sed -n 's/^token: //p')

# ---- clean
eng clean 000000000000
check "clean with a stale token refused" 3 $rc
check "and nothing removed" yes $([ -d $R/data/data/com.gone.app ] && echo yes || echo no)
eng clean "$tok"
check "clean" 0 $rc
check "orphan data removed" no $([ -e $R/data/data/com.gone.app ] && echo yes || echo no)
check "orphan apk removed" no $([ -e $R/data/app/com.gone.app-1.apk ] && echo yes || echo no)
check "orphan dex removed" no $([ -e $R/data/dalvik-cache/system@app@Removed.apk@classes.dex ] && echo yes || echo no)
check "dangling app link removed" no $([ -L $R/data/data/com.kept.app/lib ] && echo yes || echo no)
check "installed app's data kept" yes $([ -d $R/data/data/com.kept.app ] && echo yes || echo no)
check "installed apk kept" yes $([ -f $R/data/app/com.kept.app-1.apk ] && echo yes || echo no)
check "live dex kept" yes $([ -f $R/data/dalvik-cache/system@app@Stock.apk@classes.dex ] && echo yes || echo no)
check "non-package folder kept" yes $([ -d $R/data/data/weird_dir ] && echo yes || echo no)
check "link outside app folders kept" yes $([ -L $R/data/misc/x/lock ] && echo yes || echo no)
check "HMG-API's link kept" yes $([ -L $R/data/hmgapi/state/dead ] && echo yes || echo no)
eng scan
check "nothing removable left" "0 removable" "$(echo "$out" | sed -n 's/^summary: [0-9]* found, \([0-9]* removable\).*/\1/p')"
mv $R/data/system/packages.xml $R/data/system/packages.xml.x
eng scan
check "no package list: refused" 1 $rc
mv $R/data/system/packages.xml.x $R/data/system/packages.xml

# ---- wipe-data
eng wipe-data
check "wipe-data alone only explains" 0 $rc
check "no command written" no $([ -e $R/cache/recovery/command ] && echo yes || echo no)
has "says HMG-API's backups go with it" "its stock backups are on /data" "$out"
eng wipe-data ERASE
check "no recovery: refused" 3 $rc
mkdir -p $R/dev/block/platform/bdm/by-name; : > $R/dev/block/platform/bdm/by-name/recovery
eng wipe-data ERASE
check "HMG-API installed: refused" 3 $rc
has "with the way out" "Uninstall it first" "$out"
check "still no command" no $([ -e $R/cache/recovery/command ] && echo yes || echo no)
rm -rf $U; eng wipe-data ERASE --anyway
check "anyway, but no stick for the backups: refused" 3 $rc
mkdir -p $U; eng wipe-data ERASE --anyway
check "anyway, with a stick" 0 $rc
check "recovery told to wipe" "--wipe_data" "$(cat $R/cache/recovery/command)"
check "stock backups on the stick first" stockbytes "$(cat "$(ls -d "$U/HMG-API debug/"*-before-wipe)/data_hmgapi_stock/app_process")"
has "restart into recovery after the output" "reboot recovery" "$(cat $R/actions.log)"

exit $fail
