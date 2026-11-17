# The Debug tab and `engine debug`

The app's Debug tab is off until Settings > About > "Show the Debug tab" turns it on. It's a
per-app preference, not a hmgapid switch. Every button runs one hmgapid op as the
`hmgapi.app` uid. These are `engine debug ...`, plus `micom-watch` and `micom-log`, which the
app could already use. Over adb they're the same: `hmgapid engine debug <what>`.

| Command | What it does |
|---|---|
| `debug dump-sys` | `getprop`, `ps`, `top`, `mount`, `df`, `pm list packages -f -u`, `netcfg`, `/proc` files, then `dumpsys <service>` for each service in `service list`, 20 s each |
| `debug dump-logs [<secs>]` | `logcat -d` of main, system, events and radio; `dmesg`; `/proc/last_kmsg`; `/data/tombstones`, `/data/anr`, `/cache/recovery`; `/data/hmgapi` logs; `/data/nemesis/*.log`; with `<secs>` (at most 600), a live logcat for that long |
| `debug dump-apks` | `/system/app`, `priv-app`, `framework`, `vendor/app` and `/data/app` (`.apk`, `.odex`, `.jar`), with `md5s.txt`; it checks the stick has room first |
| `debug scan` | prints leftovers as `<kind> remove|keep <bytes> <path> -- <why>`, then `summary:` and `token:` |
| `debug clean <token>` | scans again and removes the `remove` lines, only if the token still matches (else code 3) |
| `debug clear-data` | says which mods would go and what's cleared and kept |
| `debug clear-data CLEAR` | `engine restore`, removes every mod, deletes HMG-API's data (below); restart after |

**Dumps** go to the first USB stick, `HMG-API debug/<yyyyMMdd-HHmmss>-<what>/`, never to /data.
Without a stick, a dump does nothing. This was the owner's choice: the dumps aren't
filtered, so logs and dumpsys can carry phone numbers, device names and addresses, account
names and vehicle identifiers. HMG-API never collects these itself. Each folder's
`README.txt` says so. The Car Report stays the way to send maintainers what they need.
Never read: the telesecurity partition, keys and certificates, and app data (only logs and
listings are taken).

**Leftovers.** "Installed" means the packages and updated packages in `packages.xml`, plus
`packages.list`. Without a readable `packages.xml`, nothing is scanned or removed. What scan
finds, and what clean may remove:

- `orphan-data`: `/data/data/<name>` with no package of that name. Kept if the name isn't a
  package name.
- `orphan-apk`: entries in `/data/app` no package's `codePath` points at, `vmdl*.tmp`
  (an unfinished install) included.
- `orphan-lib`: `/data/app-lib/<dir>` no package's `nativeLibraryPath` points at.
- `orphan-dex`: `/data/dalvik-cache/*@classes.dex` whose source file is gone, or is an
  `orphan-apk`. A whole-cache wipe isn't offered: it would make the next start rebuild every
  app (see CLAUDE.md, `prefill_dalvik`).
- `dangling-link`: symlinks to nothing anywhere on /data. Removable only in `/data/data`,
  `/data/app` and `/data/app-lib`; elsewhere (lock links and the like) they're kept.

`/data/hmgapi`, `/data/nemesis`, `/data/system`, `/data/property` and `/data/media` are never
walked or removed. Links are removed themselves, never what they point at.

**Clear data** is the uninstaller's `--purge` (USB: `UNINSTALL-PURGE`) with HMG-API staying
installed. The steps, in this order:

1. `engine restore` puts every file and setting the mods changed back to stock, while the
   record of them is still there. If it doesn't finish, nothing is removed. An unfinished
   or trial apply refuses, as `engine install` does.
2. Every mod is removed (`engine remove`, its dalvik-cache with it).
3. `settings/` (the owner's switches and `hmgapi.json` too), `logs/`, `public/reports`,
   `inbox`, `state/probes`, `state/local-map.json`, `state/map-check.json`,
   `state/map-probe.json`, `state/micom-watch.txt` and the top-level logs (`hmgapid.log`,
   `boot.log` ...) are deleted.
4. The live state is published again, so hmgapid stops the mods' services and HMG-API's
   keepers. NEMESIS's list drops the mods' packages, and the mods' apps are uninstalled
   (`engine apps`, again once Android is up if it isn't).

Kept: HMG-API's install (`/system` files, the app, `hook/`, `boot.sh` and the other steps,
`install.log`), `stock/` (needed to uninstall), `maps/` and the state of what HMG-API owns.
A restart finishes it. The app asks for CLEAR to be typed.

Tested off the car (`core/runtime/debug-test.sh`: a fake unit with orphans, links and a
stick); on no car yet.
