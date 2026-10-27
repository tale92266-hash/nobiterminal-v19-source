package com.termux.app.nobi;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v12: "Auto repo setup on app open".
 *
 * On app open, after bootstrap is complete, makes sure the NobiTerminal
 * apt repositories are configured, without ever blocking the UI thread
 * and without depending on the manual one-paste setup block:
 *
 * - $PREFIX/etc/apt/sources.list.d/nobi1.list, nobi2.list, nobi3.list
 *   must contain exactly the NobiTerminal repo lines below. Only a file
 *   that is missing or whose content differs is (re)written.
 * - Lines in $PREFIX/etc/apt/sources.list that serve the official
 *   Termux repositories (whose debs carry com.termux paths and can
 *   never install under NobiTerminal) are commented out with '#'.
 *   Before that first rewrite, the original file is copied to
 *   sources.list.nobi-backup; an existing backup is never overwritten.
 *   No other file in sources.list.d is touched, and in sources.list
 *   only official Termux lines are touched.
 *
 * State is derived from the files themselves on every open: if all
 * three nobi files are already correct and no official Termux line is
 * left uncommented, nothing is written and no `apt update` runs, so a
 * normal app open costs only three small file reads.
 *
 * If anything was fixed, `apt update` runs once on the same background
 * thread, through the codebase's own background-command pattern
 * (AppShell.execute with a TermuxShellEnvironment, which supplies the
 * correct PREFIX, PATH, HOME and TMPDIR — see TermuxService
 * createTermuxTask and TermuxUtils apt-info). It is failure tolerant:
 * a missing apt binary, an offline device, or a non-zero exit code is
 * only logged; nothing crashes and the next app open retries, because
 * the files are re-checked from scratch.
 *
 * No new dependency, no existing preference key is changed, and no
 * user-visible text is shown by this class (any text it logs is
 * English). This class is the dex marker for the feature: its name,
 * NobiRepoSetup, is what a later dex check should look for.
 */
public final class NobiRepoSetup {

    private static final String LOG_TAG = "NobiRepoSetup";

    /** Guards one in-flight run; reset when the background run ends. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static final String[] NOBI_FILE_NAMES = {
        "nobi1.list",
        "nobi2.list",
        "nobi3.list"
    };

    private static final String[] NOBI_FILE_LINES = {
        "deb [trusted=yes] https://tale92266-hash.github.io/nobiterminal-repo ./",
        "deb [trusted=yes] https://tale92266-hash.github.io/nobiterminal-repo-2 ./",
        "deb [trusted=yes] https://tale92266-hash.github.io/nobiterminal-repo-3 ./"
    };

    /** Host/path fragments that identify an official Termux apt source. */
    private static final String[] OFFICIAL_TERMUX_FRAGMENTS = {
        "termux.dev",
        "dl.bintray.com/termux",
        "termux.net"
    };

    private static final String BACKUP_FILE_NAME = "sources.list.nobi-backup";

    private NobiRepoSetup() {
    }

    /**
     * Starts the check on a background thread. Safe to call on every app
     * open: concurrent calls collapse into the one in-flight run, and a
     * run that finds everything already correct does no writes and runs
     * no command. Must only be called after bootstrap is complete, so
     * that $PREFIX already exists (see TermuxActivity).
     */
    public static void ensureAsync(Context context) {
        if (context == null) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        Context rawAppContext = context.getApplicationContext();
        final Context appContext = rawAppContext != null ? rawAppContext : context;
        new Thread() {
            @Override
            public void run() {
                try {
                    boolean changed = ensureRepoFiles();
                    if (changed) runAptUpdate(appContext);
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Auto repo setup failed: " + e.getMessage());
                } finally {
                    RUNNING.set(false);
                }
            }
        }.start();
    }

    /**
     * Writes only the nobi files that are missing/wrong and comments out
     * only official Termux lines in sources.list. Returns true if any
     * file was actually changed.
     */
    private static boolean ensureRepoFiles() {
        boolean changed = false;

        File aptDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/apt");
        File sourcesListDir = new File(aptDir, "sources.list.d");
        if (!sourcesListDir.isDirectory() && !sourcesListDir.mkdirs()) {
            Logger.logError(LOG_TAG, "Cannot create apt sources directory");
            return false;
        }

        for (int i = 0; i < NOBI_FILE_NAMES.length; i++) {
            File file = new File(sourcesListDir, NOBI_FILE_NAMES[i]);
            String expected = NOBI_FILE_LINES[i];
            String existing = readFileTrimmed(file);
            if (expected.equals(existing)) continue;
            try {
                writeFile(file, expected + "\n");
                changed = true;
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Cannot write " + file.getName() + ": " + e.getMessage());
            }
        }

        if (commentOutOfficialTermuxLines(new File(aptDir, "sources.list"))) changed = true;
        return changed;
    }

    /**
     * Comments out uncommented official Termux lines in sources.list.
     * Returns true only if the file was actually rewritten. The original
     * is backed up to sources.list.nobi-backup first, but only when no
     * backup exists yet and only when a rewrite is really needed; if the
     * backup cannot be made, the original is left untouched rather than
     * rewritten without a way back. Other lines and every other file in
     * sources.list.d are left exactly as they are.
     */
    private static boolean commentOutOfficialTermuxLines(File sourcesList) {
        if (sourcesList == null || !sourcesList.isFile()) return false;
        String content = readFile(sourcesList);
        if (content == null) return false;
        String[] lines = content.split("\n", -1);
        boolean needsChange = false;
        for (String line : lines) {
            if (isOfficialTermuxLine(line)) {
                needsChange = true;
                break;
            }
        }
        if (!needsChange) return false;

        File backup = new File(sourcesList.getParentFile(), BACKUP_FILE_NAME);
        if (!backup.exists()) {
            try {
                copyFile(sourcesList, backup);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Cannot back up sources.list, leaving it unchanged: " + e.getMessage());
                return false;
            }
        }

        List<String> rewritten = new ArrayList<>();
        for (String line : lines) {
            rewritten.add(isOfficialTermuxLine(line) ? "#" + line : line);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rewritten.size(); i++) {
            if (i > 0) out.append("\n");
            out.append(rewritten.get(i));
        }
        if (!content.endsWith("\n") && out.length() > 0) {
            // Keep the file newline-terminated like a normal sources file.
            out.append("\n");
        }
        try {
            writeFile(sourcesList, out.toString());
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Cannot rewrite sources.list: " + e.getMessage());
            return false;
        }
        return true;
    }

    /** True for an uncommented deb/deb-src line pointing at official Termux. */
    private static boolean isOfficialTermuxLine(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false;
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length < 2) return false;
        if (!"deb".equals(tokens[0]) && !"deb-src".equals(tokens[0])) return false;
        for (String token : tokens) {
            for (String fragment : OFFICIAL_TERMUX_FRAGMENTS) {
                if (token.contains(fragment)) return true;
            }
        }
        return false;
    }

    /**
     * Runs `apt update` on the calling (background) thread through the
     * codebase's AppShell pattern, so the correct PREFIX environment is
     * supplied by TermuxShellEnvironment. Any failure — including
     * offline — is only logged; the next app open retries.
     */
    private static void runAptUpdate(Context appContext) {
        try {
            String aptPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/apt";
            if (!new File(aptPath).isFile()) {
                Logger.logError(LOG_TAG, "apt binary not found, skipping apt update");
                return;
            }
            ExecutionCommand executionCommand = new ExecutionCommand(
                TermuxShellManager.getNextShellId(), aptPath,
                new String[]{"update"}, null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                Runner.APP_SHELL.getName(), false);
            executionCommand.commandLabel = "Nobi Repo Setup apt update";
            executionCommand.setShellCommandShellEnvironment = true;
            executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF;
            // Synchronous on this background thread (the TermuxUtils
            // apt-info pattern): the UI thread is never involved, and
            // the RUNNING guard is released in ensureAsync's finally
            // block as soon as apt finishes, whatever its exit code.
            AppShell appShell = AppShell.execute(appContext, executionCommand,
                null, new TermuxShellEnvironment(), null, true);
            if (appShell == null) {
                Logger.logError(LOG_TAG, "apt update could not be started, will retry on next app open");
            } else {
                Logger.logDebug(LOG_TAG, "apt update finished with exit code "
                    + executionCommand.resultData.exitCode);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "apt update failed, will retry on next app open: " + e.getMessage());
        }
    }

    private static String readFile(File file) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Cannot read " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static String readFileTrimmed(File file) {
        String content = readFile(file);
        return content == null ? null : content.trim();
    }

    private static void writeFile(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
