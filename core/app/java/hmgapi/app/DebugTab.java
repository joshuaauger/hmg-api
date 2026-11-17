package hmgapi.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug (shown once Settings > About > "Show the Debug tab" is on): the owner's tools
 * for looking into their own head unit, through hmgapid engine debug and the MICOM
 * recorder. Dumps go to the USB stick only. Left: the tools; right: what the last one said.
 */
final class DebugTab {
    final MainActivity a;
    private final List<Button> buttons = new ArrayList<Button>();
    private TextView status, output;
    private String token;      // the last scan's, for clean
    private Button clean;
    static String last = "";   // the right side, kept while the app runs

    DebugTab(MainActivity a) {
        this.a = a;
    }

    View view() {
        LinearLayout root = Ui.row(a);
        root.setGravity(0);
        LinearLayout list = Ui.scrolling(root, 1, 0);
        list.setPadding(Ui.dp(a, 36), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 24));
        LinearLayout right = Ui.column(a);
        right.setPadding(0, Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 16));
        status = Ui.text(right, "", 18, false, Ui.WARN);
        LinearLayout box = Ui.scrolling(right, 1, LinearLayout.LayoutParams.MATCH_PARENT);
        output = Ui.mono(box);
        output.setText(last.isEmpty() ? "Output shows here." : last);
        root.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        Ui.text(list, "Debug", 28, true, Ui.TEXT);
        Ui.text(list, "For looking into this head unit when something misbehaves. Hide this tab again in Settings > About.",
                16, false, Ui.GREY);

        Ui.text(list, "Dumps to the USB stick", 21, true, Ui.TEXT);
        Ui.text(list, "Into \"HMG-API debug\" on the stick, nowhere else. Logs and dumpsys can hold personal data (phone "
                + "numbers, device names and addresses, accounts, vehicle identifiers): keep them to yourself, and use the "
                + "Car Report for HMG-API's maintainers.", 16, false, Ui.GREY);
        LinearLayout dumps = Ui.row(a);
        list.addView(dumps);
        add(Ui.button(dumps, "System info", click("Dumping system info (dumpsys of every service: a few minutes)…", 1800000,
                "engine", "debug", "dump-sys")));
        add(Ui.button(dumps, "Logs", click("Dumping logs…", 600000, "engine", "debug", "dump-logs")));
        add(Ui.button(dumps, "Logs + record 60 s", click("Recording 60 s of logs: do what goes wrong now…", 600000,
                "engine", "debug", "dump-logs", "60")));
        add(Ui.button(dumps, "Apps (APKs)", click("Copying the apps and framework (several hundred MB: a few minutes)…", 1800000,
                "engine", "debug", "dump-apks")));
        Ui.line(list);

        Ui.text(list, "Leftovers", 21, true, Ui.TEXT);
        Ui.text(list, "Data, APKs, libraries and optimised code of apps that aren't installed any more, and links that point "
                + "at nothing. Only what no installed package owns can be removed; HMG-API's and NEMESIS's folders are never touched.",
                16, false, Ui.GREY);
        LinearLayout lo = Ui.row(a);
        list.addView(lo);
        add(Ui.button(lo, "Scan", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        }));
        clean = Ui.button(lo, "Remove leftovers…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClean();
            }
        });
        clean.setEnabled(false);
        Ui.line(list);

        Ui.text(list, "MICOM recorder", 21, true, Ui.TEXT);
        Ui.text(list, "Records the frames the car's controllers send the head unit (listening only; telematics, with identity "
                + "and phone numbers, is never recorded). Decode at home with tools/micom-decode.py.", 16, false, Ui.GREY);
        LinearLayout mi = Ui.row(a);
        list.addView(mi);
        add(Ui.button(mi, "Record everything", click("Recording MICOM frames…", 60000, "micom-watch", "all")));
        add(Ui.button(mi, "Stop", click("Stopping…", 60000, "micom-watch", "off")));
        add(Ui.button(mi, "Show recording", click("Reading the recording…", 60000, "micom-log")));
        add(Ui.button(mi, "Status", click("", 60000, "micom-watch")));
        Ui.line(list);

        Ui.text(list, "Clear HMG-API's data", 21, true, Ui.ERROR);
        Ui.text(list, "Like uninstalling with PURGE, but HMG-API stays installed: every mod's changes go back to stock, then "
                + "every mod is removed with its app and NEMESIS package, and HMG-API's settings, logs, Car Reports and this "
                + "car's own map points are deleted. HMG-API's stock backups and maps stay. A restart finishes it.", 16, false, Ui.GREY);
        add(Ui.button(list, "Clear data…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearCheck();
            }
        }));
        return root;
    }

    private void add(Button b) {
        buttons.add(b);
    }

    private void busy(boolean on) {
        for (Button b : buttons) b.setEnabled(!on);
        if (on) clean.setEnabled(false);
        else clean.setEnabled(token != null);
    }

    /** a button that runs one hmgapid op and shows what it said */
    private View.OnClickListener click(final String wait, final int timeout, final String... op) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(wait, timeout, op, null);
            }
        };
    }

    interface After {
        void done(Hmgapid.Result r);
    }

    private void run(String wait, final int timeout, final String[] op, final After after) {
        busy(true);
        status.setText(wait);
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call(timeout, op);
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                busy(false);
                if (e != null) {
                    status.setText(Hmgapid.unreachable(e));
                    return;
                }
                status.setText(r.code == 0 ? "Done." : "Didn't work (code " + r.code + ").");
                String out = r.output.trim();
                last = out.isEmpty() ? "(nothing)" : out;
                output.setText(last);
                if (after != null) after.done(r);
            }
        }.start(a);
    }

    // ---- leftovers

    private void scan() {
        token = null;
        run("Scanning /data…", 600000, new String[] { "engine", "debug", "scan" }, new After() {
            @Override
            public void done(Hmgapid.Result r) {
                int removable = 0;
                for (String l : r.output.split("\n")) {
                    if (l.startsWith("token: ")) token = l.substring(7).trim();
                    if (l.contains(" remove ")) removable++;
                }
                if (r.code != 0 || removable == 0) token = null;
                clean.setEnabled(token != null);
                clean.setText(token != null ? "Remove " + removable + " leftover(s)…" : "Remove leftovers…");
                if (r.code == 0) status.setText(removable == 0 ? "Nothing to remove." : removable + " can be removed (marked \"remove\").");
            }
        });
    }

    private void confirmClean() {
        if (token == null) return;
        new AlertDialog.Builder(a).setTitle("Remove the leftovers?")
                .setMessage("Everything marked \"remove\" in the scan is deleted. None of it belongs to an installed app. "
                        + "If something changed since the scan, nothing is removed and you're asked to scan again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String t = token;
                        token = null;
                        run("Removing…", 600000, new String[] { "engine", "debug", "clean", t }, null);
                    }
                }).show();
    }

    // ---- clear data

    private void clearCheck() {
        run("Checking…", 60000, new String[] { "engine", "debug", "clear-data" }, new After() {
            @Override
            public void done(Hmgapid.Result r) {
                if (r.code != 0) return;
                StringBuilder m = new StringBuilder();
                for (String l : r.output.split("\n")) {
                    if (!l.startsWith("confirm: ")) m.append(l.replaceFirst("^([a-z]+): ", "$1:  ")).append("\n\n");
                }
                confirmClear(m.toString().trim());
            }
        });
    }

    private void confirmClear(String facts) {
        final EditText typed = new EditText(a);
        typed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        typed.setHint("CLEAR");
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 24);
        box.setPadding(p, p / 2, p, 0);
        Ui.text(box, facts + "\n\nThis can't be undone: the mods have to be installed again. Type CLEAR to go on.", 17, false, Ui.TEXT);
        box.addView(typed);
        new AlertDialog.Builder(a).setTitle("Clear HMG-API's data?").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (!"CLEAR".equals(typed.getText().toString().trim())) {
                            status.setText("Not cleared: CLEAR wasn't typed.");
                            return;
                        }
                        run("Putting the mods' changes back to stock and clearing (a few minutes; leave the head unit on)…", 900000,
                                new String[] { "engine", "debug", "clear-data", "CLEAR" }, new After() {
                                    @Override
                                    public void done(Hmgapid.Result r) {
                                        if (r.code == 0) a.needRestart();
                                    }
                                });
                    }
                }).show();
    }
}
