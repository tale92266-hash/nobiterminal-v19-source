package com.termux.app.nobi;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v6 (5): terminal search — find text in the scrollback.
 *
 * The dialog stays open while navigating: typing a query collects all
 * case-insensitive matches from the transcript and jumps to the first one;
 * Previous/Next cycle through them. The terminal view is scrolled so the
 * match line lands mid-screen (there is no match-highlight API in
 * TerminalView, so scrolling + the "n of m" counter is the signal).
 */
public final class NobiTerminalSearch {

    private NobiTerminalSearch() {
    }

    public static void show(final Activity activity, final TerminalSession session,
                            final TerminalView terminalView) {
        if (activity == null || session == null || terminalView == null) return;
        final TerminalEmulator emulator = session.getEmulator();
        if (emulator == null || emulator.getScreen() == null) return;

        final String transcript = emulator.getScreen().getTranscriptText();
        if (transcript == null || transcript.isEmpty()) return;

        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        final EditText queryInput = new EditText(activity);
        queryInput.setSingleLine();
        queryInput.setHint(R.string.nobi_find_hint);
        layout.addView(queryInput);

        final TextView counterView = new TextView(activity);
        counterView.setPadding(0, pad / 2, 0, pad / 2);
        layout.addView(counterView);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final Button prevButton = new Button(activity);
        prevButton.setText(R.string.nobi_find_previous);
        final Button nextButton = new Button(activity);
        nextButton.setText(R.string.nobi_find_next);
        final Button closeButton = new Button(activity);
        closeButton.setText(android.R.string.cancel);
        buttons.addView(prevButton);
        buttons.addView(nextButton);
        buttons.addView(closeButton);
        layout.addView(buttons);

        final List<Integer> matches = new ArrayList<>();
        final int[] currentIndex = {-1};

        final Runnable jumpTo = new Runnable() {
            @Override
            public void run() {
                if (currentIndex[0] < 0 || currentIndex[0] >= matches.size()) return;
                int offset = matches.get(currentIndex[0]);
                int line = 0;
                for (int i = 0; i < offset && i < transcript.length(); i++) {
                    if (transcript.charAt(i) == '\n') line++;
                }
                int historyRows = emulator.getScreen().getActiveTranscriptRows();
                int matchRow = line - historyRows;
                int visibleRows = emulator.mRows;
                int topRow = matchRow - visibleRows / 2;
                if (topRow < -historyRows) topRow = -historyRows;
                if (topRow > 0) topRow = 0;
                terminalView.setTopRow(topRow);
                counterView.setText((currentIndex[0] + 1) + " / " + matches.size());
            }
        };

        final Runnable recompute = new Runnable() {
            @Override
            public void run() {
                String query = queryInput.getText().toString();
                matches.clear();
                currentIndex[0] = -1;
                if (query.length() >= 2) {
                    String haystack = transcript.toLowerCase(Locale.ROOT);
                    String needle = query.toLowerCase(Locale.ROOT);
                    int from = 0;
                    while (true) {
                        int found = haystack.indexOf(needle, from);
                        if (found < 0) break;
                        matches.add(found);
                        from = found + needle.length();
                        if (matches.size() > 5000) break;
                    }
                }
                if (matches.isEmpty()) {
                    counterView.setText(R.string.nobi_find_no_match);
                } else {
                    currentIndex[0] = 0;
                    jumpTo.run();
                }
            }
        };

        queryInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                recompute.run();
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.nobi_find_title)
            .setView(layout)
            .setCancelable(true)
            .create();

        prevButton.setOnClickListener(v -> {
            if (matches.isEmpty()) return;
            currentIndex[0] = (currentIndex[0] - 1 + matches.size()) % matches.size();
            jumpTo.run();
        });
        nextButton.setOnClickListener(v -> {
            if (matches.isEmpty()) return;
            currentIndex[0] = (currentIndex[0] + 1) % matches.size();
            jumpTo.run();
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }
}
