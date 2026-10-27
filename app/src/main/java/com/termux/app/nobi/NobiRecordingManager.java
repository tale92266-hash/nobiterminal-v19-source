package com.termux.app.nobi;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * v7 (37): session recording. Every second the terminal transcript is
 * snapshotted; new text is appended to a .cast.json file as
 * {"t": elapsedMs, "text": "..."} lines. Playback replays the snapshots
 * with their original timing. This is snapshot playback (1s granularity),
 * not byte-exact terminal recording — the emulator exposes no output hook.
 */
public final class NobiRecordingManager {

    private static boolean recording = false;
    private static String recordingName = null;
    private static FileWriter writer = null;
    private static Handler handler = null;
    private static Runnable poller = null;
    private static String lastText = "";
    private static long startMs = 0;

    private NobiRecordingManager() {
    }

    public static boolean isRecording() {
        return recording;
    }

    public static String getRecordingName() {
        return recordingName;
    }

    public static File recordingsDir(Context context) {
        File dir = new File(context.getFilesDir(), "recordings");
        dir.mkdirs();
        return dir;
    }

    public static void start(Context context, TerminalSession session, String name) {
        if (recording) return;
        try {
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
            File file = new File(recordingsDir(context), safe + ".cast.json");
            writer = new FileWriter(file, false);
            lastText = currentTranscript(session);
            startMs = System.currentTimeMillis();
            recording = true;
            recordingName = safe;
            handler = new Handler(Looper.getMainLooper());
            poller = new Runnable() {
                @Override
                public void run() {
                    if (!recording) return;
                    try {
                        String now = currentTranscript(session);
                        if (now.length() > lastText.length()
                            && now.startsWith(lastText)) {
                            String delta = now.substring(lastText.length());
                            writer.write("{\"t\":"
                                + (System.currentTimeMillis() - startMs)
                                + ",\"text\":\"" + escape(delta) + "\"}\n");
                            writer.flush();
                            lastText = now;
                        } else if (!now.equals(lastText)) {
                            // Transcript scrolled/trimmed: anchor to the new text.
                            lastText = now;
                        }
                    } catch (Exception ignored) {
                    }
                    if (recording && handler != null) handler.postDelayed(this, 1000);
                }
            };
            handler.postDelayed(poller, 1000);
        } catch (Exception e) {
            recording = false;
        }
    }

    public static void stop() {
        recording = false;
        if (handler != null && poller != null) handler.removeCallbacks(poller);
        handler = null;
        poller = null;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
        recordingName = null;
        lastText = "";
    }

    private static String currentTranscript(TerminalSession session) {
        try {
            return session.getEmulator().getScreen().getTranscriptText();
        } catch (Exception e) {
            return "";
        }
    }

    static String escape(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
