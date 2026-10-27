package com.termux.app.nobi;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** v7 (37): replays a .cast.json recording with its original timing. */
public class NobiPlaybackActivity extends AppCompatActivity {

    private TextView textView;
    private ScrollView scrollView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static class Frame {
        long t;
        String text;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        scrollView = new ScrollView(this);
        textView = new TextView(this);
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        textView.setTextSize(13);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        textView.setPadding(pad, pad, pad, pad);
        scrollView.addView(textView);
        setContentView(scrollView);

        String name = getIntent().getStringExtra("recording_name");
        if (name == null) {
            finish();
            return;
        }
        List<Frame> frames = loadFrames(new File(NobiRecordingManager.recordingsDir(this),
            name + ".cast.json"));
        if (frames.isEmpty()) {
            Toast.makeText(this, "Recording is empty", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setTitle("Playback: " + name);
        for (Frame frame : frames) {
            handler.postDelayed(() -> {
                textView.append(frame.text);
                scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }, Math.max(0, frame.t));
        }
    }

    private List<Frame> loadFrames(File file) {
        List<Frame> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Frame frame = parseFrame(line);
                if (frame != null) out.add(frame);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private Frame parseFrame(String line) {
        try {
            int tIndex = line.indexOf("\"t\":");
            if (tIndex < 0) return null;
            int tEnd = line.indexOf(',', tIndex);
            Frame frame = new Frame();
            frame.t = Long.parseLong(line.substring(tIndex + 4, tEnd).trim());
            int textIndex = line.indexOf("\"text\":\"", tEnd);
            if (textIndex < 0) return null;
            int textStart = textIndex + 8;
            StringBuilder sb = new StringBuilder();
            for (int i = textStart; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\' && i + 1 < line.length()) {
                    char next = line.charAt(++i);
                    switch (next) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        default:
                            sb.append(next);
                            break;
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            frame.text = sb.toString();
            return frame;
        } catch (Exception e) {
            return null;
        }
    }
}
