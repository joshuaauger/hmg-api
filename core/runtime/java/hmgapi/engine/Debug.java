package hmgapi.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * engine debug: the owner's tools for looking into their own head unit (the app's Debug
 * tab, hidden until Settings > About turns it on).
 *
 *   debug dump-sys               dumpsys (each service on its own), props, processes, mounts... to USB
 *   debug dump-logs [<secs>]     logcat (every buffer), dmesg, tombstones, ANRs, HMG-API's and
 *                                NEMESIS's logs to USB; <secs>: also record logcat live that long
 *   debug dump-apks              /system/app, priv-app, framework and /data/app (with md5s) to USB
 *   debug scan                   leftovers: app data, APKs, native libraries and dalvik-cache
 *                                entries of packages that aren't installed, and dangling symlinks
 *   debug clean <token>          deletes what scan found (the token: scan's, so nothing new goes)
 *   debug clear-data [CLEAR]     HMG-API's purge without the uninstall: every mod's changes back to
 *                                stock, the mods removed, HMG-API's settings, logs and state cleared
 *
 * Dumps go to the first USB stick, "HMG-API debug/<time>-<what>/", never to /data and
 * never anywhere else: they're the owner's, and they hold what HMG-API itself never
 * collects (logs and dumpsys can carry phone numbers, device addresses, account names),
 * so each folder gets a README saying not to post it publicly. Never read: the
 * telesecurity partition, keys and certificates (only logs and listings are taken).
 *
 * Cleaning only ever removes things no installed package owns, worked out from
 * packages.xml; it refuses without a readable package list. /data/hmgapi, /data/nemesis,
 * /data/system and /data/property are never touched.
 */
final class Debug {
    private Debug() {}

    static final String[] MOUNTS = { "/storage/usb0", "/storage/usb1", "/storage/usb2" };
    /** never removed by clean, never followed by the symlink walk */
    static final String[] KEEP = { "/data/hmgapi", "/data/nemesis", "/data/system", "/data/property", "/data/media" };
    static final String README = "Made by HMG-API's Debug tab on this head unit.\n\n"
            + "This folder can hold personal data: logs and dumpsys output may include phone numbers, contact\n"
            + "and device names, Bluetooth and Wi-Fi addresses, account names and vehicle identifiers. Keep it\n"
            + "to yourself; don't attach it to a public issue. For HMG-API's maintainers, use the Car Report\n"
            + "instead (it holds model-level facts only), or send only the parts asked for, privately.\n";

    static int run(Engine e, String[] args) throws Exception {
        String what = args.length > 1 ? args[1] : "";
        if ("dump-sys".equals(what)) return dumpSys(e);
        if ("dump-logs".equals(what)) return dumpLogs(e, args.length > 2 ? seconds(args[2]) : 0);
        if ("dump-apks".equals(what)) return dumpApks(e);
        if ("scan".equals(what)) return scan(e, null);
        if ("clean".equals(what)) {
            if (args.length < 3) { e.out.add("usage: debug clean <token from debug scan>"); return 2; }
            return scan(e, args[2]);
        }
        if ("clear-data".equals(what)) return clearData(e, args);
        e.out.add("usage: debug dump-sys | dump-logs [<seconds>] | dump-apks | scan | clean <token> | clear-data [CLEAR]");
        return 2;
    }

    static int seconds(String s) {
        try {
            return Math.max(0, Math.min(600, Integer.parseInt(s)));
        } catch (NumberFormatException x) {
            return 0;
        }
    }

    // ---- USB

    /** the USB sticks mounted now (HMGAPI_USB_DIRS off the car) */
    static List<File> sticks(Engine e) {
        List<File> out = new ArrayList<File>();
        String test = Engine.env("HMGAPI_USB_DIRS", null);
        for (String d : e.host && test != null ? test.split(":") : MOUNTS) {
            File f = new File(e.host ? d : e.root + d);
            if (f.isDirectory() && f.list() != null && f.canWrite()) out.add(f);
        }
        return out;
    }

    /** a new folder for one dump on the first stick, with the README; null (and why, in out) if there's no stick */
    static File folder(Engine e, String what) throws IOException {
        List<File> s = sticks(e);
        if (s.isEmpty()) {
            e.out.add("no USB stick found: plug one in (the dumps only go to a stick). ADB over USB uses the same port.");
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File d = new File(s.get(0), "HMG-API debug/" + stamp + "-" + what);
        if (!d.mkdirs() && !d.isDirectory()) {
            e.out.add("can't make " + d.getPath() + " (stick read-only or full?)");
            return null;
        }
        Engine.write(new File(d, "README.txt"), README);
        return d;
    }

    static String shown(Engine e, File f) {
        String p = f.getPath();
        return e.root.length() > 0 && p.startsWith(e.root) ? p.substring(e.root.length()) : p;
    }

    // ---- commands with a time limit, output straight to a file

    /** cmd's output (and errors) into out, killed after secs; the exit code, -1 when it was stopped */
    static int capture(Engine e, File out, int secs, String... cmd) throws IOException {
        if (e.host) {   // off the car: no Android tools, note what would run
            Engine.write(out, "(off the car: " + join(cmd) + ")\n");
            e.hostAction("debug run " + join(cmd));
            return 0;
        }
        out.getParentFile().mkdirs();
        final Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException x) {
            Engine.write(out, "can't run " + join(cmd) + ": " + x.getMessage() + "\n");
            return 127;
        }
        final InputStream in = p.getInputStream();
        final FileOutputStream o = new FileOutputStream(out);
        Thread copy = new Thread("hmgapi-debug-copy") {
            @Override
            public void run() {
                byte[] b = new byte[65536];
                int n;
                try {
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                } catch (IOException ignored) {
                    // the process was stopped
                }
            }
        };
        copy.start();
        long end = System.currentTimeMillis() + secs * 1000L;
        int code = -1;
        while (true) {
            try {
                code = p.exitValue();
                break;
            } catch (IllegalThreadStateException running) {
                if (System.currentTimeMillis() >= end) {
                    p.destroy();
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException x) {
                    break;
                }
            }
        }
        try {
            copy.join(5000);
        } catch (InterruptedException ignored) {
            // done anyway
        }
        o.close();
        return code;
    }

    static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) b.append(b.length() > 0 ? " " : "").append(s);
        return b.toString();
    }

    // ---- dump-sys

    static int dumpSys(Engine e) throws Exception {
        File d = folder(e, "sys");
        if (d == null) return 1;
        String[][] cmds = {
            { "getprop.txt", "/system/bin/getprop" },
            { "ps.txt", "/system/bin/ps", "-p", "-P" },
            { "threads.txt", "/system/bin/ps", "-t" },
            { "top.txt", "/system/bin/top", "-n", "1", "-d", "1" },
            { "mount.txt", "/system/bin/mount" },
            { "df.txt", "/system/bin/df" },
            { "packages.txt", "/system/bin/pm", "list", "packages", "-f", "-u" },
            { "features.txt", "/system/bin/pm", "list", "features" },
            { "netcfg.txt", "/system/bin/netcfg" },
            { "services.txt", "/system/bin/service", "list" },
        };
        int n = 0;
        for (String[] c : cmds) {
            capture(e, new File(d, c[0]), 30, Arrays.copyOfRange(c, 1, c.length));
            n++;
        }
        for (String p : new String[] { "cpuinfo", "meminfo", "version", "cmdline", "partitions", "mounts", "interrupts", "uptime" }) {
            capture(e, new File(d, "proc/" + p + ".txt"), 10, "/system/bin/cat", "/proc/" + p);
            n++;
        }
        // each service on its own, so one that hangs costs 20 s instead of the whole dump
        List<String> services = services(new File(d, "services.txt"));
        int timedOut = 0;
        for (String s : services) {
            if (capture(e, new File(d, "dumpsys/" + s.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt"), 20, "/system/bin/dumpsys", s) < 0) timedOut++;
            n++;
        }
        e.out.add("dumped: " + n + " file(s), " + services.size() + " service(s)" + (timedOut > 0 ? ", " + timedOut + " stopped after 20 s" : ""));
        e.out.add("folder: " + shown(e, d));
        return 0;
    }

    /** "12\tactivity: [android.app.IActivityManager]" -> activity */
    static List<String> services(File list) throws IOException {
        List<String> out = new ArrayList<String>();
        if (!list.isFile()) return out;
        Matcher m = Pattern.compile("(?m)^\\s*\\d+\\s+([^:\\s]+):").matcher(Engine.read(list));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ---- dump-logs

    static int dumpLogs(Engine e, int live) throws Exception {
        File d = folder(e, "logs");
        if (d == null) return 1;
        for (String b : new String[] { "main", "system", "events", "radio" }) {
            capture(e, new File(d, "logcat-" + b + ".txt"), 60, "/system/bin/logcat", "-d", "-v", "threadtime", "-b", b);
        }
        capture(e, new File(d, "dmesg.txt"), 20, "/system/bin/dmesg");
        int files = 0;
        // whole folders of logs (no app data): crashes, ANRs, HMG-API's and NEMESIS's own logs
        String[] trees = { "/data/tombstones", "/data/anr", "/data/hmgapi/logs", "/cache/recovery" };
        for (String t : trees) files += copyTree(new File(e.root + t), new File(d, t.substring(1).replace('/', '_')), null);
        String[] single = { "/proc/last_kmsg", "/data/hmgapi/hmgapid.log", "/data/hmgapi/boot.log", "/data/hmgapi/install.log" };
        for (String s : single) {
            File f = new File(e.root + s);
            if (!f.isFile()) continue;
            Engine.copy(f, new File(d, s.substring(1).replace('/', '_')));
            files++;
        }
        File nem = new File(e.root + "/data/nemesis");
        File[] ns = nem.listFiles();
        if (ns != null) {
            for (File f : ns) {
                if (f.isFile() && f.getName().endsWith(".log")) {
                    Engine.copy(f, new File(d, "data_nemesis/" + f.getName()));
                    files++;
                }
            }
        }
        if (live > 0) capture(e, new File(d, "logcat-live-" + live + "s.txt"), live, "/system/bin/logcat", "-v", "threadtime", "-b", "main", "-b", "system");
        e.out.add("dumped: logcat (main, system, events, radio), dmesg, " + files + " log file(s)"
                + (live > 0 ? ", " + live + " s recorded live" : ""));
        e.out.add("note: the main buffer only reaches back seconds on a running car; record live to catch something as it happens");
        e.out.add("folder: " + shown(e, d));
        return 0;
    }

    /** copies the files under src (symlinks not followed) into dst; ext: only names ending so (null: all). The count. */
    static int copyTree(File src, File dst, String[] ext) throws IOException {
        File[] fs = src.listFiles();
        if (fs == null) return 0;
        Arrays.sort(fs);
        int n = 0;
        for (File f : fs) {
            if (isLink(f)) continue;
            if (f.isDirectory()) n += copyTree(f, new File(dst, f.getName()), ext);
            else if (f.isFile() && wanted(f.getName(), ext)) {
                Engine.copy(f, new File(dst, f.getName()));
                n++;
            }
        }
        return n;
    }

    static boolean wanted(String name, String[] ext) {
        if (ext == null) return true;
        for (String x : ext) if (name.endsWith(x)) return true;
        return false;
    }

    // ---- dump-apks

    static int dumpApks(Engine e) throws Exception {
        String[] dirs = { "/system/app", "/system/priv-app", "/system/framework", "/system/vendor/app", "/data/app" };
        String[] ext = { ".apk", ".odex", ".jar" };
        List<File> files = new ArrayList<File>();
        long bytes = 0;
        for (String dir : dirs) {
            for (File f : list(new File(e.root + dir), ext)) {
                files.add(f);
                bytes += f.length();
            }
        }
        List<File> s = sticks(e);
        if (!s.isEmpty() && s.get(0).getUsableSpace() > 0 && s.get(0).getUsableSpace() < bytes + (16L << 20)) {
            e.out.add("not enough room on the stick: " + mb(bytes) + " needed, " + mb(s.get(0).getUsableSpace()) + " free");
            return 1;
        }
        File d = folder(e, "apks");
        if (d == null) return 1;
        StringBuilder list = new StringBuilder("# md5 bytes path (as on the head unit)\n");
        for (File f : files) {
            String p = shown(e, f);
            Engine.copy(f, new File(d, p.substring(1)));
            list.append(Engine.md5(f)).append(' ').append(f.length()).append(' ').append(p).append('\n');
        }
        Engine.write(new File(d, "md5s.txt"), list.toString());
        e.out.add("dumped: " + files.size() + " file(s), " + mb(bytes));
        e.out.add("folder: " + shown(e, d));
        return 0;
    }

    /** files under dir with one of the extensions, one folder level deep at most (4.4 keeps apps flat) */
    static List<File> list(File dir, String[] ext) {
        List<File> out = new ArrayList<File>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            if (isLink(f)) continue;
            if (f.isFile() && wanted(f.getName(), ext)) out.add(f);
            else if (f.isDirectory()) {
                File[] in = f.listFiles();
                if (in == null) continue;
                Arrays.sort(in);
                for (File g : in) if (g.isFile() && !isLink(g) && wanted(g.getName(), ext)) out.add(g);
            }
        }
        return out;
    }

    static String mb(long b) {
        return (b + (1 << 19)) / (1 << 20) + " MB";
    }

    // ---- scan / clean

    /** what packages.xml says is installed */
    static final class Installed {
        final Set<String> names = new LinkedHashSet<String>();
        final Set<String> code = new LinkedHashSet<String>();
        final Set<String> libs = new LinkedHashSet<String>();
    }

    static final Pattern TAG = Pattern.compile("<(?:package|updated-package)\\s[^>]*>");

    /** null when there's no package list to go by */
    static Installed installed(Engine e) throws IOException {
        File x = new File(e.root + "/data/system/packages.xml");
        if (!x.isFile()) return null;
        String s = Engine.read(x);
        Installed in = new Installed();
        Matcher m = TAG.matcher(s);
        while (m.find()) {
            String t = m.group();
            String n = attr(t, "name"), c = attr(t, "codePath"), l = attr(t, "nativeLibraryPath");
            if (n != null) in.names.add(n);
            if (c != null) in.code.add(c);
            if (l != null) in.libs.add(l);
        }
        if (in.names.isEmpty()) return null;   // not the text format, or empty: don't guess
        File pl = new File(e.root + "/data/system/packages.list");   // also counts as installed
        if (pl.isFile()) {
            for (String line : Engine.read(pl).split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 0 && f[0].length() > 0) in.names.add(f[0]);
            }
        }
        return in;
    }

    static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /** one thing scan found */
    static final class Found {
        final String kind, path, why;
        final long bytes;
        final boolean removable;

        Found(String kind, String path, long bytes, boolean removable, String why) {
            this.kind = kind;
            this.path = path;
            this.bytes = bytes;
            this.removable = removable;
            this.why = why;
        }
    }

    static int scan(Engine e, String token) throws Exception {
        Installed in = installed(e);
        if (in == null) {
            e.out.add("can't read the package list (/data/system/packages.xml): nothing scanned, nothing removed");
            return 1;
        }
        List<Found> found = new ArrayList<Found>();
        // app data with no package
        for (File f : sorted(new File(e.root + "/data/data"))) {
            if (in.names.contains(f.getName())) continue;
            boolean pkg = f.getName().matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
            found.add(new Found("orphan-data", "/data/data/" + f.getName(), size(f), pkg && !isLink(f),
                    pkg ? "no package " + f.getName() + " installed" : "not a package name: kept"));
        }
        // APKs and their folders in /data/app no package points at
        Set<String> goneApks = new LinkedHashSet<String>();
        for (File f : sorted(new File(e.root + "/data/app"))) {
            String p = "/data/app/" + f.getName();
            if (in.code.contains(p)) continue;
            if (!isLink(f)) goneApks.add(p);
            found.add(new Found("orphan-apk", p, size(f), !isLink(f),
                    f.getName().startsWith("vmdl") ? "unfinished install" : "no installed package's code"));
        }
        // native library folders no package points at
        for (File f : sorted(new File(e.root + "/data/app-lib"))) {
            String p = "/data/app-lib/" + f.getName();
            if (in.libs.contains(p)) continue;
            found.add(new Found("orphan-lib", p, size(f), !isLink(f), "no installed package's libraries"));
        }
        // optimised code for files that aren't there any more: system@app@X.apk@classes.dex
        for (File f : sorted(new File(e.root + "/data/dalvik-cache"))) {
            String n = f.getName();
            if (!f.isFile() || !n.endsWith("@classes.dex")) continue;
            String src = "/" + n.substring(0, n.length() - "@classes.dex".length()).replace('@', '/');
            boolean gone = goneApks.contains(src);
            if (!gone && new File(e.root + src).exists()) continue;
            found.add(new Found("orphan-dex", "/data/dalvik-cache/" + n, f.length(), true,
                    "for " + src + (gone ? ", which no package owns" : ", which is gone")));
        }
        // symlinks to nothing, anywhere on /data (removable only in app folders)
        List<String> links = new ArrayList<String>();
        walkLinks(e, new File(e.root + "/data"), links, 0, new int[] { 0 });
        for (String l : links) {
            boolean appArea = l.startsWith("/data/data/") || l.startsWith("/data/app-lib/") || l.startsWith("/data/app/");
            String to = readlink(new File(e.root + l));
            found.add(new Found("dangling-link", l, 0, appArea && !kept(l),
                    (to != null ? "points at " + to + ", which is gone" : "points at nothing") + (appArea ? "" : " (outside app folders: kept)")));
        }
        // drop what's inside a removable folder (it goes with it)
        List<Found> shown = new ArrayList<Found>();
        for (Found f : found) {
            boolean inside = false;
            for (Found g : found) if (g != f && g.removable && f.path.startsWith(g.path + "/")) inside = true;
            if (!inside) shown.add(f);
        }
        String tok = token(shown);
        if (token == null) {
            long bytes = 0;
            int rm = 0;
            for (Found f : shown) {
                e.out.add(f.kind + " " + (f.removable ? "remove" : "keep") + " " + f.bytes + " " + f.path + " -- " + f.why);
                if (f.removable) {
                    rm++;
                    bytes += f.bytes;
                }
            }
            e.out.add("summary: " + shown.size() + " found, " + rm + " removable (" + mb(bytes) + "), " + in.names.size() + " packages installed");
            e.out.add("token: " + tok);
            return 0;
        }
        if (!tok.equals(token)) {
            e.out.add("what scan finds changed since (an app was installed or removed?): scan again, then clean");
            return 3;
        }
        int done = 0, failed = 0;
        for (Found f : shown) {
            if (!f.removable || kept(f.path)) continue;
            File x = new File(e.root + f.path);
            if (delete(x)) {
                done++;
                e.out.add("removed " + f.path);
            } else {
                failed++;
                e.out.add("couldn't remove " + f.path);
            }
        }
        e.out.add("cleaned: " + done + " removed" + (failed > 0 ? ", " + failed + " couldn't be" : ""));
        return failed > 0 ? 1 : 0;
    }

    static boolean kept(String path) {
        for (String k : KEEP) if (path.equals(k) || path.startsWith(k + "/")) return true;
        return false;
    }

    static File[] sorted(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return new File[0];
        Arrays.sort(fs);
        return fs;
    }

    /** dangling symlinks under dir, as /data/... paths; not into KEEP folders, at most 20 levels and 200,000 entries */
    static void walkLinks(Engine e, File dir, List<String> out, int depth, int[] seen) {
        if (depth > 20 || seen[0] > 200000) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            seen[0]++;
            String p = f.getPath().substring(e.root.length());
            if (kept(p)) continue;
            if (!f.exists()) out.add(p);   // listed but not there: only a link to nothing does that
            else if (!isLink(f) && f.isDirectory()) walkLinks(e, f, out, depth + 1, seen);
        }
    }

    /**
     * Java 7 on Dalvik has no Files.isSymbolicLink: a link's canonical path isn't its own.
     * Only for links to something; a link to nothing is a listed name that doesn't exist().
     */
    static boolean isLink(File f) {
        try {
            File parent = f.getParentFile();
            if (parent == null) return false;
            File c = new File(parent.getCanonicalFile(), f.getName());
            return !c.getCanonicalFile().equals(c.getAbsoluteFile());
        } catch (IOException x) {
            return false;
        }
    }

    /** a link's target: java.nio on a desktop JVM, libcore on the unit; null if neither */
    static String readlink(File f) {
        try {
            Class<?> files = Class.forName("java.nio.file.Files");
            Object path = File.class.getMethod("toPath").invoke(f);
            Class<?> pc = Class.forName("java.nio.file.Path");
            return String.valueOf(files.getMethod("readSymbolicLink", pc).invoke(null, path));
        } catch (Throwable ignored) {
            // not a desktop JVM
        }
        try {
            Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
            return (String) os.getClass().getMethod("readlink", String.class).invoke(os, f.getPath());
        } catch (Throwable ignored) {
            return null;
        }
    }

    static long size(File f) {
        if (isLink(f)) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File g : fs) n += size(g);
        return n;
    }

    /** removes f; a link is removed itself, never what it points at */
    static boolean delete(File f) {
        if (!isLink(f) && f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File g : fs) delete(g);
        }
        return f.delete();
    }

    static String token(List<Found> fs) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Found f : fs) if (f.removable) b.append(f.kind).append(' ').append(f.path).append('\n');
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] h = d.digest(b.toString().getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) s.append(String.format("%02x", h[i] & 0xff));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException x) {
            throw new IOException(x);
        }
    }

    // ---- clear-data

    /** under /data/hmgapi: what clear-data deletes (HMG-API's install, stock backups and maps stay) */
    static final String[] CLEARED = { "settings", "logs", "public/reports", "inbox", "state/probes", "state/local-map.json",
        "state/map-check.json", "state/map-probe.json", "state/micom-watch.txt", "hmgapid.log", "boot.log", "recover.log",
        "binds.log", "nemesis-sync.log", "map-local.log" };

    static int clearData(Engine e, String[] args) throws Exception {
        boolean go = args.length > 2 && "CLEAR".equals(args[2]);
        String[] mods = new File(e.data, "mods").list();
        if (mods == null) mods = new String[0];
        Arrays.sort(mods);
        int found = 0;
        for (String c : CLEARED) if (new File(e.data, c).exists()) found++;
        e.out.add("mods: " + mods.length + (mods.length > 0 ? " (" + join(mods) + "): their changes back to stock, then removed "
                + "with their apps and NEMESIS packages" : ""));
        e.out.add("clears: HMG-API's settings (the owner's switches included), logs, Car Reports, this car's own map points (" + found
                + " of " + CLEARED.length + " there)");
        e.out.add("keeps: HMG-API itself, its stock backups and its maps (uninstall with PURGE to remove those too)");
        if (!go) {
            e.out.add("confirm: debug clear-data CLEAR");
            return 0;
        }
        if (e.journalFile.exists()) {
            e.out.add("refused: an earlier apply isn't finished or is a trial: `engine keep` or `engine recover` first");
            return 1;
        }
        // 1. every file and setting the mods changed back to stock, while the record of them is still there
        Engine r = new Engine();
        int rc = r.run(new String[] { "restore" });
        for (String l : r.out) e.out.add("restore: " + l);
        if (rc != 0) {
            e.out.add("stopped: the restore didn't finish, so nothing was removed");
            return rc;
        }
        // 2. the mods (their dalvik-cache with them), then NEMESIS's list and the mods' apps follow
        for (String id : mods) {
            File d = new File(e.data, "mods/" + id);
            if (!d.isDirectory()) continue;
            if (id.matches("[a-z0-9][a-z0-9-]{0,63}")) Packages.remove(e, id);
            else Engine.rm(d);
        }
        // 3. HMG-API's own data
        int n = 0;
        for (String c : CLEARED) {
            File f = new File(e.data, c);
            if (f.exists() || isLink(f)) {
                Engine.rm(f);
                n++;
            }
        }
        e.hmgapi = new LinkedHashMap<String, Object>();
        LiveState.publish(e);   // no services, readings or network asks left: hmgapid stops them
        NemesisSync.run(e);     // the mods' NEMESIS packages unlisted
        ModApps.sync(e);        // the mods' apps uninstalled (or once Android is up)
        e.out.add("cleared: " + mods.length + " mod(s) removed, " + n + " of HMG-API's data folder(s) and file(s) deleted");
        e.out.add("restart the head unit to finish");
        return 0;
    }
}
