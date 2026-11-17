package hmgapi.engine;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * engine libs: copies the CarPlay and Android Auto libraries (and HMG-API's and its
 * mods' own) from /system/lib to "HMG-API libs/<car>-<time>" on every USB stick, for
 * debugging a car's firmware away from the car. Hook points and recipes work in memory,
 * so these are the files as the car has them. Where HMG-API holds a stock backup of one
 * (/data/hmgapi/stock, kept when a mod replaced the file) that copy goes in "stock/".
 * manifest.txt lists each file's size and md5.
 *
 * These are the head unit's own files: they go to the owner's stick only, never into a
 * repository or a public post.
 */
final class LibDump {
    private LibDump() {}

    static final String DIR = "HMG-API libs";
    /** lower-case name prefixes of the libraries worth having when CarPlay or Android Auto misbehaves */
    static final String[] PREFIX = { "libcarplay", "libiap2", "libautoreceiver", "libhmgapi", "libleafia" };

    static int run(Engine e) throws Exception {
        List<File> libs = find(new File(e.root + "/system/lib"));
        if (libs.isEmpty()) {
            e.out.add("libs: no CarPlay or Android Auto libraries found in /system/lib");
            return 1;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date());
        String name = Report.safe(e.facts.get("model")) + "-" + Report.safe(e.facts.get("region")) + "-"
                + Report.safe(e.facts.get("firmware")) + "-" + stamp;

        StringBuilder manifest = new StringBuilder();
        manifest.append("HMG-API libs ").append(name).append("\n");
        manifest.append("car: ").append(e.facts.get("product")).append("\n");
        manifest.append("hmgapi: ").append(Engine.HMGAPI_VERSION).append("\n\n");
        long total = 0;
        for (File f : libs) {
            String md5 = Engine.md5(f);
            File backup = stockOf(e, f);
            manifest.append(md5).append("  ").append(f.length()).append("  ").append(f.getName());
            if (backup != null) manifest.append("  (stock backup: ").append(Engine.md5(backup)).append(")");
            manifest.append("\n");
            total += f.length();
        }

        int sticks = 0;
        for (String u : Report.USB) {
            File root = new File(e.root + u);
            if (!root.isDirectory() || root.list() == null) continue;
            File d = new File(new File(root, DIR), name);
            int failed = 0;
            for (File f : libs) {
                try {
                    Engine.copy(f, new File(d, f.getName()));
                    File backup = stockOf(e, f);
                    if (backup != null) Engine.copy(backup, new File(d, "stock/" + f.getName()));
                } catch (Exception x) {
                    failed++;
                    e.out.add("libs: " + f.getName() + " not copied to " + u + ": " + Report.scrub(String.valueOf(x)));
                }
            }
            try {
                Engine.write(new File(d, "manifest.txt"), manifest.toString());
            } catch (Exception x) {
                failed++;
                e.out.add("libs: manifest not written to " + u + ": " + Report.scrub(String.valueOf(x)));
            }
            if (failed == 0) {
                sticks++;
                e.out.add("usb: " + u + "/" + DIR + "/" + name);
            }
        }
        if (sticks == 0) {
            e.out.add("libs: no USB stick found, or nothing could be written to it (is it full or read-only?)");
            return 1;
        }
        e.out.add("libs: " + libs.size() + " file(s), " + (total / 1024) + " KB, md5s in manifest.txt");
        return 0;
    }

    /** the libraries in dir whose names match, in name order */
    static List<File> find(File dir) {
        List<File> out = new ArrayList<File>();
        File[] all = dir.listFiles();
        if (all == null) return out;
        Arrays.sort(all);
        for (File f : all) {
            String n = f.getName().toLowerCase();
            if (!f.isFile() || !n.endsWith(".so")) continue;
            for (String p : PREFIX) {
                if (n.startsWith(p)) {
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    /** HMG-API's backup of the stock file, when there is one */
    static File stockOf(Engine e, File lib) {
        File b = new File(e.root + "/data/hmgapi/stock/system/lib/" + lib.getName());
        return b.isFile() ? b : null;
    }
}
