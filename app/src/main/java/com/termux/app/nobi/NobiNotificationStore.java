package com.termux.app.nobi;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory, read-only notification history (v10).
 *
 * Performance + privacy, both by design: records live only in memory
 * (nothing is written to disk, nothing leaves the device), the list is
 * bounded, and the Notifications screen shows only the newest slice, so
 * opening it never inflates hundreds of rows.
 */
public final class NobiNotificationStore {

    /** Hard cap on stored records; older ones are dropped. */
    public static final int MAX_RECORDS = 100;

    /** How many records the Notifications screen inflates at most. */
    public static final int MAX_SHOWN = 30;

    public static final class Record {
        public final String appName;
        public final String packageName;
        public final String title;
        public final String text;
        public final long timeMillis;

        public Record(String appName, String packageName, String title,
                      String text, long timeMillis) {
            this.appName = appName;
            this.packageName = packageName;
            this.title = title;
            this.text = text;
            this.timeMillis = timeMillis;
        }
    }

    private static final List<Record> RECORDS = new ArrayList<>();

    private NobiNotificationStore() {
    }

    public static synchronized void add(Record record) {
        RECORDS.add(0, record);
        while (RECORDS.size() > MAX_RECORDS) {
            RECORDS.remove(RECORDS.size() - 1);
        }
    }

    public static synchronized List<Record> snapshot() {
        return new ArrayList<>(RECORDS);
    }

    public static synchronized void clear() {
        RECORDS.clear();
    }
}
