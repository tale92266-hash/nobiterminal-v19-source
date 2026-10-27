package com.termux.app.nobi;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * v7: a filterable list dialog shared by the terminal tools (tab search,
 * tab groups, clipboard history, command palette, SSH servers, app-wide
 * search). One search box on top, a filtered list below.
 */
public final class NobiListDialog {

    public interface OnPick {
        void onPick(int index);
    }

    private NobiListDialog() {
    }

    public static void show(Context context, String title, String[] labels, OnPick onPick) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * context.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        EditText filter = new EditText(context);
        filter.setHint("Search…");
        filter.setSingleLine(true);
        root.addView(filter);

        ListView list = new ListView(context);
        final List<String> all = new ArrayList<>();
        for (String label : labels) all.add(label);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_list_item_1, new ArrayList<>(all));
        list.setAdapter(adapter);
        root.addView(list);

        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase();
                adapter.clear();
                for (String label : all) {
                    if (label.toLowerCase().contains(query)) adapter.add(label);
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String picked = adapter.getItem(position);
            dialog.dismiss();
            if (picked != null) onPick.onPick(all.indexOf(picked));
        });
        dialog.show();
    }
}
