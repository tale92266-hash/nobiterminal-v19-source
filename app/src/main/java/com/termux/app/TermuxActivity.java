package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.nobi.NobiTerminalSearch;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.NobiTabsController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.KeyboardUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    NobiTabsController mNobiTabsController;

    // v6 batch 1-16: app lock state, pending shared/run input, background time.
    /** True once the app-lock PIN has been entered for this foreground stay. */
    private boolean mNobiAppUnlocked = false;
    /** When the activity last went to background (0 = never / just unlocked). */
    private long mNobiBackgroundSince = 0;
    /** Shared file path waiting to be typed into the current session. */
    private String mPendingTerminalText = null;
    /** Command waiting to be run in the current session (home shortcut). */
    private String mPendingTerminalCommand = null;

    // v7: pending terminal↔editor extras (same fresh-launch guard as v6).
    /** Folder to focus-or-create a session in (editor "Open terminal here"). */
    private String mPendingTerminalCwd = null;
    /** One-shot run-and-back command (editor Run in Terminal). */
    private String mPendingRunAndReturn = null;
    private String mPendingRunCwd = null;
    private String mPendingReturnFile = null;
    private boolean mPendingReturnOutput = false;
    /** Session name to focus (editor "Back to terminal"). */
    private String mPendingFocusSession = null;
    /** Run-and-back tracking: handle of the one-shot session + return info. */
    String mNobiRunSessionHandle = null;
    String mNobiRunReturnFile = null;
    boolean mNobiRunReturnOutput = false;
    /** Startup picker shown once per process (v7 65). */
    private boolean mNobiStartupPickerShown = false;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;

    /**
     * Unique identifier for this activity instance. Used for session attachment tracking.
     */
    private final int mActivityId = System.identityHashCode(this);


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        // v7 (70/81/82/83): animated intro, bottom bar, FAB, tab gestures.
        setupNobiV7Chrome(savedInstanceState);

        setNewWindowButtonView();

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        refreshNobiTabs();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;

        // v6 (13): app lock — re-locks only after 60s in background, so
        // quick trips to Settings / Nobi Code do not re-prompt.
        checkNobiAppLock();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // v6 (7/15): a home shortcut or shared file arriving while the app
        // is already alive lands here (singleTask) instead of onCreate.
        if (intent != null) {
            String command = intent.getStringExtra("nobi_run_command");
            String text = intent.getStringExtra("nobi_share_path");
            if (command != null) mPendingTerminalCommand = command;
            if (text != null) mPendingTerminalText = text;
            deliverPendingTerminalInput();
            // v7: editor→terminal extras, acted on immediately (service is
            // connected while this activity is alive) or stashed.
            captureNobiExtras(intent);
            deliverNobiExtras();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateExtraKeysVisibility();
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();

        // v6 (1/13): remember live sessions for restore; note background
        // time for the app-lock grace window.
        saveNobiRestoreSessions();
        mNobiBackgroundSince = System.currentTimeMillis();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        // Detach all sessions owned by this activity and reset their clients
        if (mTermuxService != null) {
            TerminalSession currentSession = getCurrentSession();
            if (currentSession != null) {
                // Reset this session's client to the service client
                mTermuxService.resetSessionClient(currentSession);
            }
            if (mTerminalView != null) {
                mTerminalView.detachSession();
            }

            // Detach all sessions owned by this activity (single source of truth)
            mTermuxService.detachAllSessionsForActivity(mActivityId);

            // Remove this activity's client from the service's set
            mTermuxService.removeTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        Logger.logDebug(LOG_TAG, "onMultiWindowModeChanged: " + isInMultiWindowMode);
    }

    /** Add a new window */
    public void addNewWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent intent = new Intent(this, TermuxActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT |
                           Intent.FLAG_ACTIVITY_NEW_TASK |
                           Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(intent);
        }
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();
        setNobiDrawerMenuView();
        // v7 (49): install the `nobi` shell bridge once PREFIX exists.
        com.termux.app.nobi.NobiScriptInstaller.installIfNeeded(this);

        final Intent intent = getIntent();
        // v6 (7/15): home-shortcut command / shared file path. Only fresh
        // launches take them from the launch intent; a recreated activity
        // must not re-run them (rotation would double-run a command).
        if (!mIsActivityRecreated && intent != null) {
            mPendingTerminalCommand = intent.getStringExtra("nobi_run_command");
            mPendingTerminalText = intent.getStringExtra("nobi_share_path");
            // v7: editor→terminal extras follow the same fresh-launch guard.
            captureNobiExtras(intent);
        }
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        // v6 (1): restore saved sessions instead of one fresh one.
                        if (!restoreNobiSessions()) {
                            mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                        }
                        deliverPendingTerminalInput();
                        // v12: auto repo setup, only after bootstrap is complete.
                        com.termux.app.nobi.NobiRepoSetup.ensureAsync(TermuxActivity.this);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                if (intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_NEW_WINDOW, false)) {
                    // Android 7.1 app shortcut: open a new window
                    addNewWindow();
                } else {
                    // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                    boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                    mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
                }
            }

            // Ensure this activity is attached to a session
            if (getCurrentSession() == null) {
                TerminalSession sessionToAttach = mTermuxService.claimFirstUnattachedSession(mActivityId);
                if (sessionToAttach != null) {
                    mTermuxTerminalSessionActivityClient.setCurrentSession(sessionToAttach);
                } else {
                    // All sessions are attached, create a new one or use stored session
                    TerminalSession storedSession = mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast();
                    if (storedSession != null && !mTermuxService.isSessionAttached(storedSession)) {
                        mTermuxTerminalSessionActivityClient.setCurrentSession(storedSession);
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
                        // In multi-window mode with all sessions attached, create a new session
                        mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                    } else {
                        mTermuxTerminalSessionActivityClient.setCurrentSession(storedSession);
                    }
                }
            }
            // v12: sessions already exist, so bootstrap completed on an
            // earlier launch — auto repo setup is safe on this path too.
            com.termux.app.nobi.NobiRepoSetup.ensureAsync(TermuxActivity.this);
        }
        // v6 (7/15): deliver a pending home-shortcut command / shared path
        // once a current session exists. Placed outside the branches (not
        // inside the else) so the multi-window base patch's
        // onServiceConnected hunk still applies cleanly on top.
        deliverPendingTerminalInput();
        // v7: editor→terminal extras, startup picker, time-aware theme.
        deliverNobiExtras();
        showNobiStartupPickerIfNeeded();
        applyNobiTimeAwareTheme();

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        // w18: drawer kisi bhi raaste se khule (edge swipe, tabs-bar fling, hardware
        // shortcut) to keyboard neeche ho, sidebar par keyboard na rahe. Button path
        // par hide pehle hi ho jata hai (ExtraKeys DRAWER); ye safety net hai.
        getDrawer().addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(TermuxActivity.this, mTerminalView);
            }
        });

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        // v5: the drawer shows the app-style section menu instead of the
        // sessions list (session switching lives in the tabs bar). The list
        // and its controller stay wired but hidden.
        termuxSessionsListView.setVisibility(View.GONE);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);

        mNobiTabsController = new NobiTabsController(this);
        mNobiTabsController.refreshTabs();
    }

    /**
     * Wire the app-style navigation menu shown in the drawer (v5, v6: the
     * redundant "Terminal" row is gone — tapping the drawer already returns
     * to the terminal — and "New Window" tops the menu). Session switching
     * lives in the tabs bar, so the drawer offers sections instead:
     * New Window, Nobi Code, New Session, Settings and Exit.
     */
    private void setNobiDrawerMenuView() {
        View newWindowItem = findViewById(R.id.nobi_menu_new_window);
        if (newWindowItem != null) {
            newWindowItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                // v6: a second terminal window = the same activity in its own
                // task, via the .app.TermuxWindowActivity alias (own
                // taskAffinity). TermuxActivity itself is singleTask, so a
                // plain intent could never spawn a second instance.
                Intent windowIntent = new Intent();
                windowIntent.setComponent(new ComponentName(getPackageName(),
                    getPackageName() + ".app.TermuxWindowActivity"));
                windowIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(windowIntent);
            });
        }

        View nobiCodeItem = findViewById(R.id.nobi_menu_nobicode);
        if (nobiCodeItem != null) {
            nobiCodeItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                ActivityUtils.startActivity(this, new Intent(this, com.nobidev.nobicode.MainActivity.class));
            });
        }

        View newSessionItem = findViewById(R.id.nobi_menu_new_session);
        if (newSessionItem != null) {
            newSessionItem.setOnClickListener(v -> {
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                getDrawer().closeDrawers();
                if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
            });
        }

        // v6 batch 1-16: terminal tools.
        View quickCommandsItem = findViewById(R.id.nobi_menu_quick_commands);
        if (quickCommandsItem != null) {
            quickCommandsItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                showQuickCommandsDialog();
            });
        }

        View findItem = findViewById(R.id.nobi_menu_find);
        if (findItem != null) {
            findItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                NobiTerminalSearch.show(this, getCurrentSession(), getTerminalView());
            });
        }

        View serverItem = findViewById(R.id.nobi_menu_server);
        if (serverItem != null) {
            serverItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                showServerDialog();
            });
        }

        View sessionToolsItem = findViewById(R.id.nobi_menu_session_tools);
        if (sessionToolsItem != null) {
            sessionToolsItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                showSessionToolsDialog();
            });
        }

        View settingsItem = findViewById(R.id.nobi_menu_settings);
        if (settingsItem != null) {
            settingsItem.setOnClickListener(v ->
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class)));
        }

        View exitItem = findViewById(R.id.nobi_menu_exit);
        if (exitItem != null) {
            exitItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                // v7 (66/67/68): quick exit with save option + server warning.
                showNobiExitDialog();
            });
        }

        // v7 (58): terminal output → editor.
        View outputEditorItem = findViewById(R.id.nobi_menu_output_editor);
        if (outputEditorItem != null) {
            outputEditorItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                sendTerminalOutputToEditor();
            });
        }

        // v10: Permissions hub drawer entry.
        View permissionsItem = findViewById(R.id.nobi_menu_permissions);
        if (permissionsItem != null) {
            permissionsItem.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                ActivityUtils.startActivity(this,
                    new Intent(this, com.termux.app.nobi.NobiPermissionsActivity.class));
            });
        }

        // v7 (88): drawer customization — tags, order, visibility.
        tagNobiDrawerRows();
        applyNobiDrawerCustomization();
        applyNobiDrawerDensityAndGlass();
    }



    // region v7: terminal↔editor switching + terminal tools

    /** v7 prefs file (UI toggles, groups, fonts, servers, drawer layout). */
    private android.content.SharedPreferences nobiV7Prefs() {
        return getSharedPreferences("nobi_v7", MODE_PRIVATE);
    }

    /** v7 accent colour (hex), default neon cyan. */
    int nobiAccentColor() {
        String hex = nobiV7Prefs().getString("accent", "#22D3EE");
        try {
            return android.graphics.Color.parseColor(hex);
        } catch (Exception e) {
            return android.graphics.Color.parseColor("#22D3EE");
        }
    }

    /** v7 tab style: "rounded" (default), "underline", or "dots". */
    String nobiTabStyle() {
        return nobiV7Prefs().getString("tab_style", "rounded");
    }

    /** v7 density: "comfortable" (default) or "compact". */
    String nobiDensity() {
        return nobiV7Prefs().getString("density", "comfortable");
    }

    /** v7 glass look on/off. */
    boolean nobiGlassLook() {
        return nobiV7Prefs().getBoolean("glass", false);
    }

    // ---- v7 (49/50/52/55/56): editor→terminal extras ----

    private void captureNobiExtras(Intent intent) {
        String cwd = intent.getStringExtra("nobi_terminal_cwd");
        if (cwd != null) mPendingTerminalCwd = cwd;
        String run = intent.getStringExtra("nobi_run_and_return");
        if (run != null) {
            mPendingRunAndReturn = run;
            mPendingRunCwd = intent.getStringExtra("nobi_run_cwd");
            mPendingReturnFile = intent.getStringExtra("nobi_return_file");
            mPendingReturnOutput = intent.getBooleanExtra("nobi_return_output", false);
        }
        String focus = intent.getStringExtra("nobi_focus_session");
        if (focus != null) mPendingFocusSession = focus;
        // v7 (25): launcher shortcut straight to the editor.
        if ("nobicode".equals(intent.getStringExtra("nobi_shortcut_target"))) {
            ActivityUtils.startActivity(this,
                new Intent(this, com.nobidev.nobicode.MainActivity.class));
        }
    }

    private void deliverNobiExtras() {
        if (mTermuxService == null) return;
        if (mPendingFocusSession != null) {
            String name = mPendingFocusSession;
            mPendingFocusSession = null;
            try {
                com.termux.shared.termux.shell.command.runner.terminal.TermuxSession found =
                    mTermuxService.getTermuxSessionForShellName(name);
                if (found != null && found.getTerminalSession() != null) {
                    mTermuxTerminalSessionActivityClient.setCurrentSession(found.getTerminalSession());
                }
            } catch (Exception ignored) {
            }
        }
        if (mPendingTerminalCwd != null) {
            String cwd = mPendingTerminalCwd;
            mPendingTerminalCwd = null;
            focusOrCreateSessionInCwd(cwd);
        }
        if (mPendingRunAndReturn != null) {
            String command = mPendingRunAndReturn;
            String cwd = mPendingRunCwd;
            mPendingRunAndReturn = null;
            mPendingRunCwd = null;
            startNobiRunSession(command, cwd, mPendingReturnFile, mPendingReturnOutput);
            mPendingReturnFile = null;
            mPendingReturnOutput = false;
        }
    }

    /** Focus the session already sitting in [cwd], else open one there. */
    private void focusOrCreateSessionInCwd(String cwd) {
        if (cwd == null || cwd.isEmpty()) return;
        try {
            for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts :
                mTermuxService.getTermuxSessions()) {
                if (ts == null) continue;
                TerminalSession session = ts.getTerminalSession();
                if (session != null && cwd.equals(session.getCwd())) {
                    mTermuxTerminalSessionActivityClient.setCurrentSession(session);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
        mTermuxTerminalSessionActivityClient.addNewSessionWithCwd(cwd, null);
    }

    /** v7 (52): one-shot session that runs a command, exits, and returns. */
    private void startNobiRunSession(String command, String cwd, String returnFile, boolean returnOutput) {
        try {
            String name = "Run";
            if (returnFile != null) {
                int slash = returnFile.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < returnFile.length()) name = "Run: " + returnFile.substring(slash + 1);
            }
            if (cwd != null && !cwd.isEmpty()) {
                mTermuxTerminalSessionActivityClient.addNewSessionWithCwd(cwd, name);
            } else {
                mTermuxTerminalSessionActivityClient.addNewSession(false, name);
            }
            TerminalSession current = getCurrentSession();
            if (current != null) {
                mNobiRunSessionHandle = current.mHandle;
                mNobiRunReturnFile = returnFile;
                mNobiRunReturnOutput = returnOutput;
            }
            // The shell exits after the command, so onSessionFinished fires.
            writeToCurrentSession(command + "; exit", true);
        } catch (Exception ignored) {
        }
    }

    /** Called from the session client when any session finishes. */
    public void onNobiRunSessionFinished(TerminalSession finishedSession) {
        if (mNobiRunSessionHandle == null || finishedSession == null
            || !mNobiRunSessionHandle.equals(finishedSession.mHandle)) return;
        String returnFile = mNobiRunReturnFile;
        boolean showOutput = mNobiRunReturnOutput;
        mNobiRunSessionHandle = null;
        mNobiRunReturnFile = null;
        mNobiRunReturnOutput = false;
        try {
            String transcript = finishedSession.getEmulator().getScreen().getTranscriptText();
            String[] lines = transcript.split("\n");
            int start = Math.max(0, lines.length - 200);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.length; i++) sb.append(lines[i]).append('\n');
            java.io.File out = new java.io.File(getFilesDir(), "nobi_last_output.txt");
            java.io.FileWriter writer = new java.io.FileWriter(out, false);
            writer.write(sb.toString());
            writer.close();
        } catch (Exception ignored) {
        }
        Intent intent = new Intent();
        intent.setClassName(this, "com.nobidev.nobicode.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (returnFile != null) intent.putExtra("nobi_open_file_path", returnFile);
        if (showOutput) intent.putExtra("nobi_show_output", true);
        startActivity(intent);
    }

    // ---- v7 (65): startup picker ----

    private void showNobiStartupPickerIfNeeded() {
        if (mNobiStartupPickerShown) return;
        mNobiStartupPickerShown = true;
        android.content.SharedPreferences prefs = nobiV7Prefs();
        if (!prefs.getBoolean("startup_picker", true)) {
            applyNobiStartupChoice(prefs.getString("startup_choice", "last"));
            return;
        }
        final android.widget.CheckBox rememberBox = new android.widget.CheckBox(this);
        rememberBox.setText(R.string.nobi_startup_remember);
        // Custom title view: setView would hide the item list, so the
        // checkbox rides in the title area instead.
        android.widget.LinearLayout titleLayout = new android.widget.LinearLayout(this);
        titleLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        titleLayout.setPadding(pad, pad, pad, 0);
        android.widget.TextView titleView = new android.widget.TextView(this);
        titleView.setText(R.string.nobi_startup_title);
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleLayout.addView(titleView);
        titleLayout.addView(rememberBox);
        new AlertDialog.Builder(this)
            .setCustomTitle(titleLayout)
            .setItems(new String[]{
                getString(R.string.nobi_startup_last),
                getString(R.string.nobi_startup_new),
                getString(R.string.nobi_startup_code)}, (dialog, which) -> {
                String choice = which == 0 ? "last" : (which == 1 ? "new" : "code");
                if (rememberBox.isChecked()) {
                    prefs.edit().putString("startup_choice", choice)
                        .putBoolean("startup_picker", false).apply();
                }
                applyNobiStartupChoice(choice);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void applyNobiStartupChoice(String choice) {
        if ("new".equals(choice)) {
            mTermuxTerminalSessionActivityClient.addNewSession(false, null);
        } else if ("code".equals(choice)) {
            ActivityUtils.startActivity(this,
                new Intent(this, com.nobidev.nobicode.MainActivity.class));
        }
        // "last": do nothing — the restore flow already ran.
    }

    // ---- v7 (66/67/68): quick exit ----

    private void showNobiExitDialog() {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        boolean serverRunning = false;
        try {
            for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts :
                mTermuxService.getTermuxSessions()) {
                if (ts == null) continue;
                TerminalSession session = ts.getTerminalSession();
                if (session != null && session.isRunning()
                    && ("Web Server".equals(session.mSessionName)
                        || "SSH Server".equals(session.mSessionName))) {
                    serverRunning = true;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        final android.widget.CheckBox saveBox = new android.widget.CheckBox(this);
        saveBox.setChecked(true);
        saveBox.setText(R.string.nobi_exit_save_sessions);
        String message = serverRunning
            ? getString(R.string.nobi_exit_server_warning)
            : getString(R.string.nobi_exit_message);
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_exit_title)
            .setMessage(message)
            .setView(saveBox)
            .setPositiveButton(R.string.nobi_exit_button, (dialog, which) -> {
                if (saveBox.isChecked()) {
                    saveNobiRestoreSessions();
                } else {
                    getSharedPreferences("nobi_restore", MODE_PRIVATE).edit().clear().apply();
                }
                // v7 (68): auto-lock — the next resume asks for the PIN
                // regardless of the 60s grace.
                mNobiAppUnlocked = false;
                Intent exitIntent = new Intent(this, TermuxService.class)
                    .setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE);
                startService(exitIntent);
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- v7 (31): time-aware theme ----

    private void applyNobiTimeAwareTheme() {
        android.content.SharedPreferences prefs = nobiV7Prefs();
        if (!prefs.getBoolean("time_theme", false)) return;
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String wanted = (hour >= 7 && hour < 19) ? "daylight" : "dark";
        if (wanted.equals(prefs.getString("time_theme_applied", ""))) return;
        if (com.termux.app.nobi.NobiTerminalUtils.applyTheme(this, wanted)) {
            prefs.edit().putString("time_theme_applied", wanted).apply();
        }
    }

    private void toggleNobiTimeAwareTheme() {
        android.content.SharedPreferences prefs = nobiV7Prefs();
        boolean on = !prefs.getBoolean("time_theme", false);
        prefs.edit().putBoolean("time_theme", on).apply();
        if (on) {
            prefs.edit().remove("time_theme_applied").apply();
            applyNobiTimeAwareTheme();
        }
        Toast.makeText(this, getString(R.string.nobi_tool_time_theme) + ": "
            + (on ? getString(R.string.nobi_on) : getString(R.string.nobi_off)),
            Toast.LENGTH_SHORT).show();
    }

    // ---- v7 (26): tab search ----

    private void showNobiTabSearch() {
        final java.util.List<TerminalSession> sessions = getNobiToolSessions();
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) labels[i] = getNobiToolLabel(sessions.get(i), i);
        if (labels.length == 0) {
            Toast.makeText(this, R.string.nobi_no_sessions, Toast.LENGTH_SHORT).show();
            return;
        }
        com.termux.app.nobi.NobiListDialog.show(this, getString(R.string.nobi_tool_tab_search),
            labels, index -> mTermuxTerminalSessionActivityClient.setCurrentSession(sessions.get(index)));
    }

    // ---- v7 (27): tab groups ----

    private java.util.Map<String, String> loadNobiTabGroups() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String raw = nobiV7Prefs().getString("tab_groups", "");
        for (String entry : raw.split("\n")) {
            int tab = entry.indexOf('\t');
            if (tab > 0) out.put(entry.substring(0, tab), entry.substring(tab + 1));
        }
        return out;
    }

    private void saveNobiTabGroups(java.util.Map<String, String> groups) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : groups.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        nobiV7Prefs().edit().putString("tab_groups", sb.toString()).apply();
    }

    private void showNobiTabGroups() {
        final java.util.List<TerminalSession> sessions = getNobiToolSessions();
        java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add(getString(R.string.nobi_group_jump_work));
        labels.add(getString(R.string.nobi_group_jump_server));
        labels.add(getString(R.string.nobi_group_jump_personal));
        for (int i = 0; i < sessions.size(); i++) {
            labels.add(getString(R.string.nobi_group_set_prefix) + getNobiToolLabel(sessions.get(i), i));
        }
        com.termux.app.nobi.NobiListDialog.show(this, getString(R.string.nobi_tool_tab_groups),
            labels.toArray(new String[0]), index -> {
                if (index < 3) {
                    String group = index == 0 ? "Work" : (index == 1 ? "Server" : "Personal");
                    jumpToNobiGroup(group);
                } else {
                    assignNobiGroup(sessions.get(index - 3));
                }
            });
    }

    private void jumpToNobiGroup(String group) {
        java.util.Map<String, String> groups = loadNobiTabGroups();
        final java.util.List<TerminalSession> inGroup = new java.util.ArrayList<>();
        for (TerminalSession session : getNobiToolSessions()) {
            if (group.equals(groups.get(session.mHandle))) inGroup.add(session);
        }
        if (inGroup.isEmpty()) {
            Toast.makeText(this, getString(R.string.nobi_group_empty, group), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[inGroup.size()];
        for (int i = 0; i < inGroup.size(); i++) labels[i] = getNobiToolLabel(inGroup.get(i), i);
        com.termux.app.nobi.NobiListDialog.show(this, group,
            labels, index -> mTermuxTerminalSessionActivityClient.setCurrentSession(inGroup.get(index)));
    }

    private void assignNobiGroup(TerminalSession session) {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        final String[] groups = new String[]{"Work", "Server", "Personal",
            getString(R.string.nobi_group_clear)};
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.nobi_group_set_prefix) + getNobiToolLabel(session, 0))
            .setItems(groups, (dialog, which) -> {
                java.util.Map<String, String> map = loadNobiTabGroups();
                if (which == 3) map.remove(session.mHandle);
                else map.put(session.mHandle, groups[which]);
                saveNobiTabGroups(map);
                if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- v7 (28): clipboard history (shared with the editor) ----

    /** Called from the session client on every terminal copy. */
    public void recordNobiClip(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            android.content.SharedPreferences prefs =
                getSharedPreferences("nobi_clip_history", MODE_PRIVATE);
            String raw = prefs.getString("items", "");
            java.util.List<String> items = new java.util.ArrayList<>();
            items.add(text);
            for (String old : raw.split("\u0001")) {
                if (!old.isEmpty() && !old.equals(text) && items.size() < 30) items.add(old);
            }
            StringBuilder sb = new StringBuilder();
            for (String item : items) {
                if (sb.length() > 0) sb.append("\u0001");
                sb.append(item);
            }
            prefs.edit().putString("items", sb.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void showNobiClipboardHistory() {
        String raw = getSharedPreferences("nobi_clip_history", MODE_PRIVATE).getString("items", "");
        java.util.List<String> items = new java.util.ArrayList<>();
        for (String item : raw.split("\u0001")) {
            if (!item.isEmpty()) items.add(item);
        }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.nobi_clip_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).replace("\n", " ");
        com.termux.app.nobi.NobiListDialog.show(this, getString(R.string.nobi_tool_clipboard_history),
            labels, index -> writeToCurrentSession(items.get(index), false));
    }

    // ---- v7 (58/29): terminal output → editor / share ----

    private String getCurrentTranscript() {
        try {
            TerminalSession session = getCurrentSession();
            if (session == null) return "";
            return session.getEmulator().getScreen().getTranscriptText();
        } catch (Exception e) {
            return "";
        }
    }

    private java.io.File saveTranscriptToFile(String prefix) {
        try {
            String transcript = getCurrentTranscript();
            if (transcript.isEmpty()) return null;
            java.io.File out = new java.io.File(getFilesDir(),
                prefix + System.currentTimeMillis() + ".txt");
            java.io.FileWriter writer = new java.io.FileWriter(out, false);
            writer.write(transcript);
            writer.close();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void sendTerminalOutputToEditor() {
        java.io.File out = saveTranscriptToFile("nobi_output_");
        if (out == null) {
            Toast.makeText(this, R.string.nobi_no_output, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent();
        intent.setClassName(this, "com.nobidev.nobicode.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("nobi_open_file_path", out.getAbsolutePath());
        startActivity(intent);
    }

    private void shareTerminalOutput() {
        java.io.File out = saveTranscriptToFile("nobi_output_");
        String transcript = getCurrentTranscript();
        if (out == null || transcript.isEmpty()) {
            Toast.makeText(this, R.string.nobi_no_output, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Terminal output");
        share.putExtra(Intent.EXTRA_TEXT, transcript.length() > 50000
            ? transcript.substring(0, 50000) + "\n…(truncated)" : transcript);
        Toast.makeText(this, getString(R.string.nobi_output_saved, out.getAbsolutePath()),
            Toast.LENGTH_LONG).show();
        startActivity(Intent.createChooser(share, getString(R.string.nobi_tool_share_output)));
    }

    // ---- v7 (37): session recording ----

    private void toggleNobiRecording() {
        if (com.termux.app.nobi.NobiRecordingManager.isRecording()) {
            com.termux.app.nobi.NobiRecordingManager.stop();
            Toast.makeText(this, R.string.nobi_record_stopped, Toast.LENGTH_SHORT).show();
            return;
        }
        TerminalSession session = getCurrentSession();
        if (session == null) {
            Toast.makeText(this, R.string.nobi_no_sessions, Toast.LENGTH_SHORT).show();
            return;
        }
        final android.widget.EditText nameInput = new android.widget.EditText(this);
        nameInput.setHint(R.string.nobi_record_name_hint);
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_tool_record_session)
            .setView(nameInput)
            .setPositiveButton(R.string.nobi_record_start, (dialog, which) -> {
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) name = "session-" + System.currentTimeMillis();
                com.termux.app.nobi.NobiRecordingManager.start(this, session, name);
                Toast.makeText(this, getString(R.string.nobi_record_started, name),
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.nobi_record_play, (dialog, which) -> showNobiRecordings())
            .show();
    }

    private void showNobiRecordings() {
        java.io.File[] files = com.termux.app.nobi.NobiRecordingManager.recordingsDir(this)
            .listFiles((dir, name) -> name.endsWith(".cast.json"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, R.string.nobi_record_none, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            labels[i] = files[i].getName().replace(".cast.json", "");
        }
        com.termux.app.nobi.NobiListDialog.show(this, getString(R.string.nobi_record_play),
            labels, index -> {
                Intent intent = new Intent(this,
                    com.termux.app.nobi.NobiPlaybackActivity.class);
                intent.putExtra("recording_name", labels[index]);
                startActivity(intent);
            });
    }

    // ---- v7 (39): SSH quick connect (no password ever stored) ----

    private java.util.List<String[]> loadNobiSshServers() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String raw = nobiV7Prefs().getString("ssh_servers", "");
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length >= 3) out.add(parts);
        }
        return out;
    }

    private void saveNobiSshServers(java.util.List<String[]> servers) {
        StringBuilder sb = new StringBuilder();
        for (String[] s : servers) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s[0]).append('\t').append(s[1]).append('\t').append(s[2]);
            if (s.length > 3) sb.append('\t').append(s[3]);
        }
        nobiV7Prefs().edit().putString("ssh_servers", sb.toString()).apply();
    }

    private void showNobiSshDialog() {
        final java.util.List<String[]> servers = loadNobiSshServers();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String[] s : servers) labels.add(s[0] + " (" + s[1] + ")");
        labels.add(getString(R.string.nobi_ssh_add));
        com.termux.app.nobi.NobiListDialog.show(this, getString(R.string.nobi_tool_ssh_connect),
            labels.toArray(new String[0]), index -> {
                if (index >= servers.size()) {
                    showNobiSshAddDialog();
                    return;
                }
                String[] s = servers.get(index);
                String host = s[1];
                String port = s.length > 2 ? s[2] : "22";
                String user = s.length > 3 ? s[3] : "";
                String target = user.isEmpty() ? host : user + "@" + host;
                mTermuxTerminalSessionActivityClient.addNewSession(false, "SSH: " + s[0]);
                writeToCurrentSession("ssh -p " + port + " " + target, true);
            });
    }

    private void showNobiSshAddDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, 0, pad, 0);
        final android.widget.EditText nameInput = new android.widget.EditText(this);
        nameInput.setHint(R.string.nobi_ssh_name_hint);
        final android.widget.EditText hostInput = new android.widget.EditText(this);
        hostInput.setHint(R.string.nobi_ssh_host_hint);
        final android.widget.EditText portInput = new android.widget.EditText(this);
        portInput.setHint("22");
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final android.widget.EditText userInput = new android.widget.EditText(this);
        userInput.setHint(R.string.nobi_ssh_user_hint);
        layout.addView(nameInput);
        layout.addView(hostInput);
        layout.addView(portInput);
        layout.addView(userInput);
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_ssh_add)
            .setView(layout)
            .setPositiveButton(R.string.nobi_save, (dialog, which) -> {
                String name = nameInput.getText().toString().trim();
                String host = hostInput.getText().toString().trim();
                String port = portInput.getText().toString().trim();
                String user = userInput.getText().toString().trim();
                if (name.isEmpty() || host.isEmpty()) return;
                if (port.isEmpty()) port = "22";
                java.util.List<String[]> servers = loadNobiSshServers();
                servers.add(new String[]{name, host, port, user});
                saveNobiSshServers(servers);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- v7 (38): command palette ----

    private void showNobiCommandPalette() {
        final String[] labels = new String[]{
            getString(R.string.nobi_palette_new_session),
            getString(R.string.nobi_palette_new_window),
            getString(R.string.nobi_palette_editor),
            getString(R.string.nobi_menu_quick_commands),
            getString(R.string.nobi_menu_find),
            getString(R.string.nobi_menu_server),
            getString(R.string.nobi_session_tools_title),
            getString(R.string.nobi_menu_settings),
            getString(R.string.nobi_menu_exit),
            getString(R.string.nobi_menu_output_editor),
            getString(R.string.nobi_tool_tab_search),
            getString(R.string.nobi_tool_clipboard_history),
            getString(R.string.nobi_tool_share_output),
            getString(R.string.nobi_tool_record_session),
            getString(R.string.nobi_tool_ssh_connect),
            getString(R.string.nobi_tool_search_everything)};
        com.termux.app.nobi.NobiListDialog.show(this, getString(R.string.nobi_tool_command_palette),
            labels, index -> {
                switch (index) {
                    case 0:
                        mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                        break;
                    case 1:
                        findViewById(R.id.nobi_menu_new_window).performClick();
                        break;
                    case 2:
                        ActivityUtils.startActivity(this,
                            new Intent(this, com.nobidev.nobicode.MainActivity.class));
                        break;
                    case 3:
                        showQuickCommandsDialog();
                        break;
                    case 4:
                        NobiTerminalSearch.show(this, getCurrentSession(), getTerminalView());
                        break;
                    case 5:
                        showServerDialog();
                        break;
                    case 6:
                        showSessionToolsDialog();
                        break;
                    case 7:
                        ActivityUtils.startActivity(this,
                            new Intent(this, SettingsActivity.class));
                        break;
                    case 8:
                        showNobiExitDialog();
                        break;
                    case 9:
                        sendTerminalOutputToEditor();
                        break;
                    case 10:
                        showNobiTabSearch();
                        break;
                    case 11:
                        showNobiClipboardHistory();
                        break;
                    case 12:
                        shareTerminalOutput();
                        break;
                    case 13:
                        toggleNobiRecording();
                        break;
                    case 14:
                        showNobiSshDialog();
                        break;
                    default:
                        showNobiAppSearch();
                        break;
                }
            });
    }

    // ---- v7 (40): app-wide search ----

    private void showNobiAppSearch() {
        final java.util.List<Runnable> actions = new java.util.ArrayList<>();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        for (TerminalSession session : getNobiToolSessions()) {
            final TerminalSession target = session;
            labels.add(getString(R.string.nobi_search_session_prefix) + getNobiToolLabel(session, 0));
            actions.add(() -> mTermuxTerminalSessionActivityClient.setCurrentSession(target));
        }
        String[] settingsEntries = new String[]{
            getString(R.string.nobi_search_settings_theme),
            getString(R.string.nobi_search_settings_backup),
            getString(R.string.nobi_search_settings_applock)};
        for (String entry : settingsEntries) {
            labels.add(getString(R.string.nobi_search_settings_prefix) + entry);
            actions.add(() -> ActivityUtils.startActivity(this,
                new Intent(this, SettingsActivity.class)));
        }
        for (String command : loadQuickCommands()) {
            final String cmd = command;
            labels.add(getString(R.string.nobi_search_command_prefix) + cmd);
            actions.add(() -> writeToCurrentSession(cmd, true));
        }
        // Editor files, when the editor folder is a plain filesystem path.
        try {
            String folderUri = getSharedPreferences("nobi_ide_prefs", MODE_PRIVATE)
                .getString("root_folder_uri", null);
            if (folderUri != null && folderUri.startsWith("file://")) {
                java.io.File dir = new java.io.File(android.net.Uri.parse(folderUri).getPath());
                if (dir.isDirectory()) {
                    java.io.File[] files = dir.listFiles();
                    if (files != null) {
                        for (java.io.File file : files) {
                            if (file.isFile()) {
                                final String path = file.getAbsolutePath();
                                labels.add(getString(R.string.nobi_search_file_prefix) + file.getName());
                                actions.add(() -> {
                                    Intent intent = new Intent();
                                    intent.setClassName(this, "com.nobidev.nobicode.MainActivity");
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    intent.putExtra("nobi_open_file_path", path);
                                    startActivity(intent);
                                });
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, R.string.nobi_search_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        com.termux.app.nobi.NobiListDialog.show(this, getString(R.string.nobi_tool_search_everything),
            labels.toArray(new String[0]), index -> actions.get(index).run());
    }

    // ---- v7 (30): per-tab font size ----

    private java.util.Map<String, Integer> loadNobiTabFonts() {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        String raw = nobiV7Prefs().getString("tab_fonts", "");
        for (String entry : raw.split("\n")) {
            int tab = entry.indexOf('\t');
            if (tab > 0) {
                try {
                    out.put(entry.substring(0, tab), Integer.parseInt(entry.substring(tab + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private void saveNobiTabFonts(java.util.Map<String, Integer> fonts) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : fonts.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        nobiV7Prefs().edit().putString("tab_fonts", sb.toString()).apply();
    }

    /** Apply the saved font size for a session (called on every switch). */
    public void applyNobiTabFont(TerminalSession session) {
        if (session == null) return;
        Integer size = loadNobiTabFonts().get(session.mHandle);
        if (size == null) return;
        try {
            getPreferences().setFontSize(size);
            getTerminalView().setTextSize(size);
        } catch (Exception ignored) {
        }
    }

    private void showNobiTabFontSizeDialog() {
        final TerminalSession session = getCurrentSession();
        if (session == null) {
            Toast.makeText(this, R.string.nobi_no_sessions, Toast.LENGTH_SHORT).show();
            return;
        }
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        final String[] labels = new String[]{
            getString(R.string.nobi_font_follow_global), "12", "14", "16", "18", "20", "22", "24"};
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_tool_tab_font_size)
            .setItems(labels, (dialog, which) -> {
                java.util.Map<String, Integer> fonts = loadNobiTabFonts();
                if (which == 0) {
                    fonts.remove(session.mHandle);
                    saveNobiTabFonts(fonts);
                    int global = getPreferences().getFontSize();
                    getTerminalView().setTextSize(global);
                } else {
                    int size = Integer.parseInt(labels[which]);
                    fonts.put(session.mHandle, size);
                    saveNobiTabFonts(fonts);
                    getPreferences().setFontSize(size);
                    getTerminalView().setTextSize(size);
                }
                if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- v7 (86): terminal font (monospace list + .ttf import) ----

    private final androidx.activity.result.ActivityResultLauncher<String[]> nobiFontPickerLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) return;
                try {
                    java.io.File dir = new java.io.File(
                        com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux");
                    dir.mkdirs();
                    java.io.File out = new java.io.File(dir, "font.ttf");
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    java.io.FileOutputStream fileOut = new java.io.FileOutputStream(out);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) >= 0) fileOut.write(buffer, 0, read);
                    in.close();
                    fileOut.close();
                    Toast.makeText(this, R.string.nobi_font_installed_restart,
                        Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, R.string.nobi_font_import_failed,
                        Toast.LENGTH_SHORT).show();
                }
            });

    private void showNobiTerminalFontDialog() {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        final java.io.File fontFile = new java.io.File(
            com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux/font.ttf");
        final String[] labels = new String[]{
            fontFile.isFile()
                ? getString(R.string.nobi_font_current_custom)
                : getString(R.string.nobi_font_default),
            getString(R.string.nobi_font_import),
            getString(R.string.nobi_font_remove)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_tool_terminal_font)
            .setItems(labels, (dialog, which) -> {
                if (which == 1) {
                    nobiFontPickerLauncher.launch(new String[]{
                        "font/ttf", "application/octet-stream", "*/*"});
                } else if (which == 2) {
                    if (fontFile.delete()) {
                        Toast.makeText(this, R.string.nobi_font_installed_restart,
                            Toast.LENGTH_LONG).show();
                    }
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- v7 (71/84/87/85/81/83/88): terminal chrome prefs ----

    private void showNobiTabStyleDialog() {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        final String[] keys = new String[]{"rounded", "underline", "dots"};
        final String[] labels = new String[]{
            getString(R.string.nobi_tab_style_rounded),
            getString(R.string.nobi_tab_style_underline),
            getString(R.string.nobi_tab_style_dots)};
        int checked = 0;
        String current = nobiTabStyle();
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(current)) checked = i;
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_tool_tab_style)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                nobiV7Prefs().edit().putString("tab_style", keys[which]).apply();
                if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNobiAccentDialog() {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        final String[] hexes = new String[]{"#22D3EE", "#8B5CF6", "#34D399", "#FBBF24",
            "#F87171", "#F472B6", "#60A5FA"};
        final String[] labels = new String[]{
            getString(R.string.nobi_accent_cyan), getString(R.string.nobi_accent_violet),
            getString(R.string.nobi_accent_green), getString(R.string.nobi_accent_amber),
            getString(R.string.nobi_accent_red), getString(R.string.nobi_accent_pink),
            getString(R.string.nobi_accent_blue)};
        int checked = 0;
        String current = nobiV7Prefs().getString("accent", "#22D3EE");
        for (int i = 0; i < hexes.length; i++) if (hexes[i].equalsIgnoreCase(current)) checked = i;
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_tool_accent)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                nobiV7Prefs().edit().putString("accent", hexes[which]).apply();
                if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
                applyNobiFabTint();
                applyNobiBottomBarTint();
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNobiDensityDialog() {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        final String[] keys = new String[]{"comfortable", "compact"};
        final String[] labels = new String[]{
            getString(R.string.nobi_density_comfortable),
            getString(R.string.nobi_density_compact)};
        int checked = "compact".equals(nobiDensity()) ? 1 : 0;
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_tool_density)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                nobiV7Prefs().edit().putString("density", keys[which]).apply();
                if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
                applyNobiDrawerDensityAndGlass();
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void toggleNobiGlassLook() {
        boolean on = !nobiGlassLook();
        nobiV7Prefs().edit().putBoolean("glass", on).apply();
        applyNobiDrawerDensityAndGlass();
        applyNobiBottomBarTint();
        Toast.makeText(this, getString(R.string.nobi_tool_glass) + ": "
            + (on ? getString(R.string.nobi_on) : getString(R.string.nobi_off)),
            Toast.LENGTH_SHORT).show();
    }

    private void toggleNobiBottomBar() {
        boolean on = !nobiV7Prefs().getBoolean("bottom_bar", false);
        nobiV7Prefs().edit().putBoolean("bottom_bar", on).apply();
        applyNobiBottomBarVisibility();
        Toast.makeText(this, getString(R.string.nobi_tool_bottom_bar) + ": "
            + (on ? getString(R.string.nobi_on) : getString(R.string.nobi_off)),
            Toast.LENGTH_SHORT).show();
    }

    private void toggleNobiFab() {
        boolean on = !nobiV7Prefs().getBoolean("fab", true);
        nobiV7Prefs().edit().putBoolean("fab", on).apply();
        applyNobiFabVisibility();
        Toast.makeText(this, getString(R.string.nobi_tool_fab) + ": "
            + (on ? getString(R.string.nobi_on) : getString(R.string.nobi_off)),
            Toast.LENGTH_SHORT).show();
    }

    private void applyNobiBottomBarVisibility() {
        View bar = findViewById(R.id.nobi_bottom_bar);
        if (bar != null) {
            bar.setVisibility(nobiV7Prefs().getBoolean("bottom_bar", false)
                ? View.VISIBLE : View.GONE);
        }
    }

    private void applyNobiBottomBarTint() {
        View bar = findViewById(R.id.nobi_bottom_bar);
        if (bar == null) return;
        if (nobiGlassLook()) {
            android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x991A1A2E);
            bar.setBackground(bg);
        } else {
            bar.setBackgroundColor(0xFF0A0F24);
        }
        int accent = nobiAccentColor();
        int[] ids = new int[]{R.id.nobi_bottom_tabs, R.id.nobi_bottom_editor, R.id.nobi_bottom_settings};
        for (int id : ids) {
            View v = bar.findViewById(id);
            if (v instanceof android.widget.TextView) ((android.widget.TextView) v).setTextColor(accent);
        }
    }

    private void applyNobiFabVisibility() {
        View fab = findViewById(R.id.nobi_fab);
        if (fab != null) {
            fab.setVisibility(nobiV7Prefs().getBoolean("fab", true) ? View.VISIBLE : View.GONE);
        }
    }

    private void applyNobiFabTint() {
        View fab = findViewById(R.id.nobi_fab);
        if (fab == null) return;
        android.graphics.drawable.Drawable bg = fab.getBackground();
        if (bg != null) {
            bg = bg.mutate();
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) bg).setColor(nobiAccentColor());
            }
        }
    }

    // ---- v7 (88): drawer customization ----

    private void tagNobiDrawerRows() {
        setDrawerRowTag(R.id.nobi_menu_new_window, "new_window");
        setDrawerRowTag(R.id.nobi_menu_nobicode, "nobicode");
        setDrawerRowTag(R.id.nobi_menu_new_session, "new_session");
        setDrawerRowTag(R.id.nobi_menu_quick_commands, "quick_commands");
        setDrawerRowTag(R.id.nobi_menu_find, "find");
        setDrawerRowTag(R.id.nobi_menu_server, "server");
        setDrawerRowTag(R.id.nobi_menu_session_tools, "session_tools");
        setDrawerRowTag(R.id.nobi_menu_output_editor, "output_editor");
        setDrawerRowTag(R.id.nobi_menu_permissions, "permissions");
        setDrawerRowTag(R.id.nobi_menu_settings, "settings");
        setDrawerRowTag(R.id.nobi_menu_exit, "exit");
    }

    private void setDrawerRowTag(int id, String key) {
        View row = findViewById(id);
        if (row != null) row.setTag(key);
    }

    private java.util.List<String> nobiDrawerOrder() {
        java.util.List<String> order = new java.util.ArrayList<>();
        String raw = nobiV7Prefs().getString("drawer_order", "");
        for (String key : raw.split(",")) {
            if (!key.isEmpty()) order.add(key);
        }
        String[] defaults = new String[]{"new_window", "nobicode", "new_session",
            "quick_commands", "find", "server", "session_tools", "output_editor",
            "permissions", "settings", "exit"};
        for (String key : defaults) if (!order.contains(key)) order.add(key);
        return order;
    }

    private java.util.Set<String> nobiDrawerHidden() {
        java.util.Set<String> out = new java.util.HashSet<>();
        String raw = nobiV7Prefs().getString("drawer_hidden", "");
        for (String key : raw.split(",")) {
            if (!key.isEmpty()) out.add(key);
        }
        return out;
    }

    private void applyNobiDrawerCustomization() {
        android.widget.LinearLayout menu = findViewById(R.id.nobi_drawer_menu);
        if (menu == null) return;
        java.util.List<String> order = nobiDrawerOrder();
        java.util.Set<String> hidden = nobiDrawerHidden();
        java.util.Map<String, View> byKey = new java.util.HashMap<>();
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            if (child.getTag() instanceof String) byKey.put((String) child.getTag(), child);
        }
        menu.removeAllViews();
        for (String key : order) {
            View row = byKey.remove(key);
            if (row == null) continue;
            // v7 (88): New Session stays pinned + visible — Sam's
            // remove-vs-explain decision on it is still pending.
            row.setVisibility("new_session".equals(key) ? View.VISIBLE
                : (hidden.contains(key) ? View.GONE : View.VISIBLE));
            menu.addView(row);
        }
        for (View leftover : byKey.values()) menu.addView(leftover);

        // v9 (drawer footer): upstream Termux added a drawer footer bar
        // (drawer_buttons) holding KEYBOARD / NEW SESSION / NEW WINDOW
        // buttons, and setNewWindowButtonView() forces that bar VERTICAL
        // and its third button VISIBLE on SDK >= 24 — that is exactly the
        // all-caps stack Sam saw under Exit in v8. It is NOT the extra
        // keys bar: the v8 sanitizer already filters the property, and
        // the bar on screen shows only its 2 real rows. Hiding the whole
        // bar wins over any child visibility the upstream code sets, the
        // null check covers layouts without the bar, and the drawer menu
        // rows (New Window / New Session) are separate views above this
        // bar and are left untouched.
        View drawerButtons = findViewById(R.id.drawer_buttons);
        if (drawerButtons != null) drawerButtons.setVisibility(View.GONE);
    }

    private android.graphics.drawable.Drawable mNobiDrawerOriginalBg = null;

    private void applyNobiDrawerDensityAndGlass() {
        android.widget.LinearLayout menu = findViewById(R.id.nobi_drawer_menu);
        if (menu == null) return;
        boolean compact = "compact".equals(nobiDensity());
        int vertical = (int) ((compact ? 8 : 13) * getResources().getDisplayMetrics().density);
        for (int i = 0; i < menu.getChildCount(); i++) {
            View row = menu.getChildAt(i);
            row.setPadding(row.getPaddingLeft(), vertical, row.getPaddingRight(), vertical);
        }
        View drawer = findViewById(R.id.left_drawer);
        if (drawer != null) {
            if (mNobiDrawerOriginalBg == null) mNobiDrawerOriginalBg = drawer.getBackground();
            if (nobiGlassLook()) {
                android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xCC0A0F24);
                bg.setCornerRadius(24 * getResources().getDisplayMetrics().density);
                drawer.setBackground(bg);
            } else if (mNobiDrawerOriginalBg != null) {
                drawer.setBackground(mNobiDrawerOriginalBg);
            }
        }
    }

    private void showNobiDrawerCustomizeDialog() {
        final java.util.List<String> order = nobiDrawerOrder();
        final java.util.Set<String> hidden = nobiDrawerHidden();
        final android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        for (int i = 0; i < order.size(); i++) {
            final int position = i;
            final String key = order.get(i);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.TextView label = new android.widget.TextView(this);
            label.setText(key);
            label.setTextSize(15);
            android.widget.LinearLayout.LayoutParams labelParams =
                new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            row.addView(label, labelParams);
            android.widget.Button up = new android.widget.Button(this);
            up.setText("↑");
            up.setEnabled(!"new_session".equals(key));
            up.setOnClickListener(v -> {
                if (position == 0) return;
                // New Session is pinned: never swap past it.
                if ("new_session".equals(order.get(position - 1))) return;
                java.util.Collections.swap(order, position, position - 1);
                persistNobiDrawerLayout(order, hidden);
                applyNobiDrawerCustomization();
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                showNobiDrawerCustomizeDialog();
            });
            android.widget.Button down = new android.widget.Button(this);
            down.setText("↓");
            down.setEnabled(!"new_session".equals(key));
            down.setOnClickListener(v -> {
                if (position >= order.size() - 1) return;
                // New Session is pinned: never swap past it.
                if ("new_session".equals(order.get(position + 1))) return;
                java.util.Collections.swap(order, position, position + 1);
                persistNobiDrawerLayout(order, hidden);
                applyNobiDrawerCustomization();
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                showNobiDrawerCustomizeDialog();
            });
            final android.widget.Button toggle = new android.widget.Button(this);
            toggle.setText(hidden.contains(key) ? "✕" : "✓");
            toggle.setEnabled(!"new_session".equals(key));
            toggle.setOnClickListener(v -> {
                if (hidden.contains(key)) hidden.remove(key);
                else hidden.add(key);
                toggle.setText(hidden.contains(key) ? "✕" : "✓");
                persistNobiDrawerLayout(order, hidden);
                applyNobiDrawerCustomization();
            });
            row.addView(up);
            row.addView(down);
            row.addView(toggle);
            container.addView(row);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(container);
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_tool_drawer_customize)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .create();
        dialogHolder[0] = dialog;
        dialog.show();
    }

    private void persistNobiDrawerLayout(java.util.List<String> order, java.util.Set<String> hidden) {
        StringBuilder orderSb = new StringBuilder();
        for (String key : order) {
            if (orderSb.length() > 0) orderSb.append(',');
            orderSb.append(key);
        }
        StringBuilder hiddenSb = new StringBuilder();
        for (String key : hidden) {
            if (hiddenSb.length() > 0) hiddenSb.append(',');
            hiddenSb.append(key);
        }
        nobiV7Prefs().edit().putString("drawer_order", orderSb.toString())
            .putString("drawer_hidden", hiddenSb.toString()).apply();
    }

    // ---- v7 (70/81/82/83): terminal chrome setup ----

    private void setupNobiV7Chrome(Bundle savedInstanceState) {
        // Bottom bar (81): tabs cycle, editor, settings in one tap.
        View bottomBar = findViewById(R.id.nobi_bottom_bar);
        if (bottomBar != null) {
            View tabsBtn = bottomBar.findViewById(R.id.nobi_bottom_tabs);
            if (tabsBtn != null) tabsBtn.setOnClickListener(v ->
                mTermuxTerminalSessionActivityClient.switchToSession(true));
            View editorBtn = bottomBar.findViewById(R.id.nobi_bottom_editor);
            if (editorBtn != null) editorBtn.setOnClickListener(v ->
                ActivityUtils.startActivity(this,
                    new Intent(this, com.nobidev.nobicode.MainActivity.class)));
            View settingsBtn = bottomBar.findViewById(R.id.nobi_bottom_settings);
            if (settingsBtn != null) settingsBtn.setOnClickListener(v ->
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class)));
            applyNobiBottomBarVisibility();
            applyNobiBottomBarTint();
        }
        // FAB (83): quick actions popup.
        View fab = findViewById(R.id.nobi_fab);
        if (fab != null) {
            fab.setOnClickListener(v -> {
                android.widget.PopupMenu menu = new android.widget.PopupMenu(this, fab);
                menu.getMenu().add(getString(R.string.nobi_palette_new_session));
                menu.getMenu().add(getString(R.string.nobi_palette_new_window));
                menu.getMenu().add(getString(R.string.nobi_palette_editor));
                menu.getMenu().add(getString(R.string.nobi_menu_settings));
                menu.setOnMenuItemClickListener(item -> {
                    String title = item.getTitle().toString();
                    if (title.equals(getString(R.string.nobi_palette_new_session))) {
                        mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                    } else if (title.equals(getString(R.string.nobi_palette_new_window))) {
                        View row = findViewById(R.id.nobi_menu_new_window);
                        if (row != null) row.performClick();
                    } else if (title.equals(getString(R.string.nobi_palette_editor))) {
                        ActivityUtils.startActivity(this,
                            new Intent(this, com.nobidev.nobicode.MainActivity.class));
                    } else {
                        ActivityUtils.startActivity(this,
                            new Intent(this, SettingsActivity.class));
                    }
                    return true;
                });
                menu.show();
            });
            applyNobiFabVisibility();
            applyNobiFabTint();
        }
        // Gestures (82) on the tabs bar: fling left/right switches sessions,
        // fling down opens the drawer, double-tap on EMPTY bar space opens
        // the editor (double-tap on a tab itself stays tab rename).
        View tabsScroll = findViewById(R.id.nobi_tabs_scroll_view);
        final ViewGroup tabsContainer = findViewById(R.id.nobi_tabs_container);
        if (tabsScroll != null) {
            final android.view.GestureDetector detector =
                new android.view.GestureDetector(this,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onFling(android.view.MotionEvent e1,
                            android.view.MotionEvent e2, float velocityX, float velocityY) {
                            if (e1 == null || e2 == null) return false;
                            float dx = e2.getX() - e1.getX();
                            float dy = e2.getY() - e1.getY();
                            if (Math.abs(dx) > Math.abs(dy) && Math.abs(velocityX) > 1000) {
                                mTermuxTerminalSessionActivityClient.switchToSession(dx < 0);
                                return true;
                            }
                            if (dy > 100 && Math.abs(velocityY) > 1000
                                && Math.abs(dy) > Math.abs(dx)) {
                                getDrawer().openDrawer(android.view.Gravity.LEFT);
                                return true;
                            }
                            return false;
                        }

                        @Override
                        public boolean onDoubleTap(android.view.MotionEvent e) {
                            if (tabsContainer != null) {
                                int[] loc = new int[2];
                                for (int i = 0; i < tabsContainer.getChildCount(); i++) {
                                    View child = tabsContainer.getChildAt(i);
                                    child.getLocationOnScreen(loc);
                                    if (e.getRawX() >= loc[0]
                                        && e.getRawX() <= loc[0] + child.getWidth()
                                        && e.getRawY() >= loc[1]
                                        && e.getRawY() <= loc[1] + child.getHeight()) {
                                        return false;
                                    }
                                }
                            }
                            ActivityUtils.startActivity(TermuxActivity.this,
                                new Intent(TermuxActivity.this,
                                    com.nobidev.nobicode.MainActivity.class));
                            return true;
                        }
                    });
            tabsScroll.setOnTouchListener((v, ev) -> {
                detector.onTouchEvent(ev);
                return false;
            });
        }
        // Animated splash (70): cold start only, non-blocking, taps pass through.
        if (savedInstanceState == null && nobiV7Prefs().getBoolean("animated_intro", true)) {
            showNobiAnimatedIntro();
        }
    }

    private void showNobiAnimatedIntro() {
        try {
            final android.view.ViewGroup decor =
                (android.view.ViewGroup) getWindow().getDecorView();
            final android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
            overlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            overlay.setBackgroundColor(0xFF0A0F24);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            int iconSize = (int) (132 * getResources().getDisplayMetrics().density);
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(R.drawable.nobi_app_icon);
            android.widget.FrameLayout.LayoutParams iconParams =
                new android.widget.FrameLayout.LayoutParams(iconSize, iconSize,
                    android.view.Gravity.CENTER);
            overlay.addView(icon, iconParams);
            android.widget.TextView name = new android.widget.TextView(this);
            name.setText("NOBITA");
            name.setTextColor(0xFF22D3EE);
            name.setTextSize(22);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setLetterSpacing(0.3f);
            android.widget.FrameLayout.LayoutParams nameParams =
                new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.CENTER_VERTICAL);
            nameParams.topMargin = iconSize;
            overlay.addView(name, nameParams);
            decor.addView(overlay);
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            android.animation.ObjectAnimator scaleX =
                android.animation.ObjectAnimator.ofFloat(icon, "scaleX", 0.8f, 1.0f);
            android.animation.ObjectAnimator scaleY =
                android.animation.ObjectAnimator.ofFloat(icon, "scaleY", 0.8f, 1.0f);
            android.animation.ObjectAnimator fadeIn =
                android.animation.ObjectAnimator.ofFloat(overlay, "alpha", 0f, 1f);
            set.playTogether(scaleX, scaleY, fadeIn);
            set.setDuration(300);
            set.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    android.animation.ObjectAnimator fadeOut =
                        android.animation.ObjectAnimator.ofFloat(overlay, "alpha", 1f, 0f);
                    fadeOut.setDuration(600);
                    fadeOut.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            decor.removeView(overlay);
                        }
                    });
                    fadeOut.start();
                }
            });
            set.start();
        } catch (Exception ignored) {
        }
    }

    // region v6 batch 1-16: terminal tools

    /** Write text to the current session; with run=true append Enter. */
    private void writeToCurrentSession(String text, boolean run) {
        TerminalSession session = getCurrentSession();
        if (session == null || !session.isRunning()) {
            Toast.makeText(this, R.string.nobi_no_running_session, Toast.LENGTH_SHORT).show();
            return;
        }
        String toWrite = run ? text + "\n" : text;
        byte[] bytes = toWrite.getBytes(StandardCharsets.UTF_8);
        session.write(bytes, 0, bytes.length);
    }

    /** Deliver a pending home-shortcut command / shared path, if possible. */
    private void deliverPendingTerminalInput() {
        if (mTermuxService == null) return;
        TerminalSession session = getCurrentSession();
        if (session == null || !session.isRunning()) return;
        if (mPendingTerminalCommand != null) {
            String command = mPendingTerminalCommand;
            mPendingTerminalCommand = null;
            writeToCurrentSession(command, true);
        }
        if (mPendingTerminalText != null) {
            String text = mPendingTerminalText;
            mPendingTerminalText = null;
            writeToCurrentSession(text, false);
        }
    }

    // region v6 (1): session restore

    /** Save running sessions (cwd + name, in order) for restore on restart. */
    private void saveNobiRestoreSessions() {
        if (mTermuxService == null) return;
        StringBuilder sb = new StringBuilder();
        for (TermuxSession termuxSession : mTermuxService.getTermuxSessions()) {
            if (termuxSession == null) continue;
            TerminalSession session = termuxSession.getTerminalSession();
            if (session == null || !session.isRunning()) continue;
            String cwd = session.getCwd();
            if (cwd == null || cwd.isEmpty()) continue;
            String name = session.mSessionName != null ? session.mSessionName : "";
            name = name.replace('\t', ' ').replace('\n', ' ');
            if (sb.length() > 0) sb.append('\n');
            sb.append(cwd).append('\t').append(name);
        }
        getSharedPreferences("nobi_restore", MODE_PRIVATE).edit()
            .putString("sessions", sb.toString()).apply();
    }

    /** Recreate saved sessions. Returns false when nothing was saved. */
    private boolean restoreNobiSessions() {
        String saved = getSharedPreferences("nobi_restore", MODE_PRIVATE)
            .getString("sessions", null);
        if (saved == null || saved.isEmpty()) return false;
        boolean restored = false;
        for (String entry : saved.split("\n")) {
            int sep = entry.indexOf('\t');
            if (sep <= 0) continue;
            String cwd = entry.substring(0, sep);
            String name = entry.substring(sep + 1);
            if (mTermuxTerminalSessionActivityClient.addNewSessionWithCwd(
                cwd, name.isEmpty() ? null : name))
                restored = true;
        }
        return restored;
    }

    // endregion

    // region v6 (2): quick commands

    private Set<String> loadQuickCommands() {
        return new TreeSet<>(getSharedPreferences("nobi_quick_cmds", MODE_PRIVATE)
            .getStringSet("commands", new HashSet<>()));
    }

    private void saveQuickCommands(Set<String> commands) {
        getSharedPreferences("nobi_quick_cmds", MODE_PRIVATE).edit()
            .putStringSet("commands", commands).apply();
    }

    private void showQuickCommandsDialog() {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        final List<String> commands = new ArrayList<>(loadQuickCommands());
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_quick_commands_title)
            .setItems(commands.toArray(new String[0]),
                (dialog, which) -> writeToCurrentSession(commands.get(which), true))
            .setPositiveButton(R.string.nobi_add, (dialog, which) ->
                TextInputDialogUtils.textInput(TermuxActivity.this,
                    R.string.nobi_quick_command_new_title, null,
                    R.string.nobi_save, text -> {
                        if (text == null || text.trim().isEmpty()) return;
                        Set<String> updated = loadQuickCommands();
                        updated.add(text.trim());
                        saveQuickCommands(updated);
                        Toast.makeText(this, R.string.nobi_quick_command_saved,
                            Toast.LENGTH_SHORT).show();
                    }, -1, null, -1, null, null))
            .setNeutralButton(R.string.nobi_delete, (dialog, which) ->
                showQuickCommandsDeleteDialog())
            .setNegativeButton(android.R.string.cancel, null);
        if (commands.isEmpty())
            builder.setMessage(R.string.nobi_quick_commands_empty);
        builder.show();
    }

    private void showQuickCommandsDeleteDialog() {
        final List<String> commands = new ArrayList<>(loadQuickCommands());
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_quick_command_delete_title)
            .setItems(commands.toArray(new String[0]), (dialog, which) -> {
                Set<String> updated = loadQuickCommands();
                updated.remove(commands.get(which));
                saveQuickCommands(updated);
                Toast.makeText(this, R.string.nobi_quick_command_deleted,
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // endregion

    // region v6: session tools (notes, colours, startup commands, shortcut)

    private void showSessionToolsDialog() {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        String[] items = new String[]{
            getString(R.string.nobi_session_notes_title),
            getString(R.string.nobi_tab_colours_title),
            getString(R.string.nobi_startup_commands_title),
            getString(R.string.nobi_home_shortcut_title),
            // v7 tools.
            getString(R.string.nobi_tool_tab_search),
            getString(R.string.nobi_tool_tab_groups),
            getString(R.string.nobi_tool_clipboard_history),
            getString(R.string.nobi_tool_share_output),
            getString(R.string.nobi_tool_record_session),
            getString(R.string.nobi_tool_ssh_connect),
            getString(R.string.nobi_tool_search_everything),
            getString(R.string.nobi_tool_command_palette),
            getString(R.string.nobi_tool_tab_font_size),
            getString(R.string.nobi_tool_time_theme),
            getString(R.string.nobi_tool_tab_style),
            getString(R.string.nobi_tool_accent),
            getString(R.string.nobi_tool_density),
            getString(R.string.nobi_tool_glass),
            getString(R.string.nobi_tool_bottom_bar),
            getString(R.string.nobi_tool_fab),
            getString(R.string.nobi_tool_drawer_customize),
            getString(R.string.nobi_tool_terminal_font)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_session_tools_title)
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0:
                        showSessionNotesDialog();
                        break;
                    case 1:
                        showTabColoursDialog();
                        break;
                    case 2:
                        showStartupCommandsDialog();
                        break;
                    case 3:
                        showHomeShortcutDialog();
                        break;
                    case 4:
                        showNobiTabSearch();
                        break;
                    case 5:
                        showNobiTabGroups();
                        break;
                    case 6:
                        showNobiClipboardHistory();
                        break;
                    case 7:
                        shareTerminalOutput();
                        break;
                    case 8:
                        toggleNobiRecording();
                        break;
                    case 9:
                        showNobiSshDialog();
                        break;
                    case 10:
                        showNobiAppSearch();
                        break;
                    case 11:
                        showNobiCommandPalette();
                        break;
                    case 12:
                        showNobiTabFontSizeDialog();
                        break;
                    case 13:
                        toggleNobiTimeAwareTheme();
                        break;
                    case 14:
                        showNobiTabStyleDialog();
                        break;
                    case 15:
                        showNobiAccentDialog();
                        break;
                    case 16:
                        showNobiDensityDialog();
                        break;
                    case 17:
                        toggleNobiGlassLook();
                        break;
                    case 18:
                        toggleNobiBottomBar();
                        break;
                    case 19:
                        toggleNobiFab();
                        break;
                    case 20:
                        showNobiDrawerCustomizeDialog();
                        break;
                    default:
                        showNobiTerminalFontDialog();
                        break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Live sessions for the tools dialogs, in tab order when available. */
    private List<TerminalSession> getNobiToolSessions() {
        if (mNobiTabsController != null)
            return mNobiTabsController.getOrderedSessions();
        List<TerminalSession> sessions = new ArrayList<>();
        if (mTermuxService != null) {
            for (TermuxSession termuxSession : mTermuxService.getTermuxSessions()) {
                if (termuxSession == null) continue;
                TerminalSession session = termuxSession.getTerminalSession();
                if (session != null) sessions.add(session);
            }
        }
        return sessions;
    }

    private String getNobiToolLabel(TerminalSession session, int index) {
        if (mNobiTabsController != null)
            return mNobiTabsController.getTabLabel(session);
        if (session.mSessionName != null && !session.mSessionName.isEmpty())
            return session.mSessionName;
        String title = session.getTitle();
        if (title != null && !title.isEmpty()) return title;
        return String.valueOf(index + 1);
    }

    // v6 (9): one note per tab, shown under the tabs bar.
    private void showSessionNotesDialog() {
        final List<TerminalSession> sessions = getNobiToolSessions();
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++)
            labels[i] = getNobiToolLabel(sessions.get(i), i);
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_session_notes_title)
            .setItems(labels, (dialog, which) -> {
                final TerminalSession session = sessions.get(which);
                String current = NobiTabsController.loadTabNotes(this).get(session.mHandle);
                TextInputDialogUtils.textInput(TermuxActivity.this,
                    R.string.nobi_session_note_title, current,
                    R.string.nobi_save, text -> {
                        NobiTabsController.saveTabNote(this, session.mHandle,
                            text == null ? "" : text.trim());
                        if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
                    },
                    R.string.nobi_clear, text -> {
                        NobiTabsController.saveTabNote(this, session.mHandle, "");
                        if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
                    }, -1, null, null);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // v6 (10): per-tab colour dot.
    private void showTabColoursDialog() {
        final List<TerminalSession> sessions = getNobiToolSessions();
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++)
            labels[i] = getNobiToolLabel(sessions.get(i), i);
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_tab_colours_title)
            .setItems(labels, (dialog, which) -> {
                final TerminalSession session = sessions.get(which);
                final String[] names = new String[]{
                    getString(R.string.nobi_colour_none),
                    getString(R.string.nobi_colour_cyan),
                    getString(R.string.nobi_colour_violet),
                    getString(R.string.nobi_colour_green),
                    getString(R.string.nobi_colour_amber),
                    getString(R.string.nobi_colour_red),
                    getString(R.string.nobi_colour_pink),
                    getString(R.string.nobi_colour_blue),
                    getString(R.string.nobi_colour_grey)};
                final Integer[] values = new Integer[]{
                    null, 0xFF22D3EE, 0xFF8B5CF6, 0xFF34D399, 0xFFFBBF24,
                    0xFFFB7185, 0xFFF472B6, 0xFF60A5FA, 0xFF9AA5D9};
                Integer current = NobiTabsController.loadTabColors(this).get(session.mHandle);
                int checked = 0;
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == null ? current == null : values[i].equals(current)) {
                        checked = i;
                        break;
                    }
                }
                new AlertDialog.Builder(this)
                    .setTitle(R.string.nobi_tab_colour_pick_title)
                    .setSingleChoiceItems(names, checked, (d, c) -> {
                        NobiTabsController.saveTabColor(this, session.mHandle, values[c]);
                        if (mNobiTabsController != null) mNobiTabsController.refreshTabs();
                        d.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // v6 (6): startup command per session name.
    private Set<String> loadStartupCommands() {
        return new HashSet<>(getSharedPreferences("nobi_startup_cmds", MODE_PRIVATE)
            .getStringSet("cmds", new HashSet<>()));
    }

    private void saveStartupCommands(Set<String> commands) {
        getSharedPreferences("nobi_startup_cmds", MODE_PRIVATE).edit()
            .putStringSet("cmds", commands).apply();
    }

    /** Command for a session name, or null. Shared with the session client. */
    public static String findStartupCommand(Context context, String sessionName) {
        if (sessionName == null) return null;
        Set<String> saved = context.getSharedPreferences("nobi_startup_cmds", MODE_PRIVATE)
            .getStringSet("cmds", null);
        if (saved == null) return null;
        for (String entry : saved) {
            int sep = entry.indexOf('\t');
            if (sep > 0 && sessionName.equals(entry.substring(0, sep)))
                return entry.substring(sep + 1);
        }
        return null;
    }

    private void showStartupCommandsDialog() {
        final List<String> entries = new ArrayList<>(loadStartupCommands());
        java.util.Collections.sort(entries);
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            int sep = entries.get(i).indexOf('\t');
            labels[i] = sep > 0 ? entries.get(i).substring(0, sep) + " → "
                + entries.get(i).substring(sep + 1) : entries.get(i);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_startup_commands_title)
            .setItems(labels, (dialog, which) -> {
                // Tapping an entry removes it; re-add to change it.
                Set<String> updated = loadStartupCommands();
                updated.remove(entries.get(which));
                saveStartupCommands(updated);
                Toast.makeText(this, R.string.nobi_startup_command_deleted,
                    Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton(R.string.nobi_add, (dialog, which) -> {
                final EditText nameInput = new EditText(this);
                nameInput.setSingleLine();
                nameInput.setHint(R.string.nobi_startup_name_hint);
                final EditText commandInput = new EditText(this);
                commandInput.setSingleLine();
                commandInput.setHint(R.string.nobi_startup_command_hint);
                int pad = (int) (16 * getResources().getDisplayMetrics().density);
                android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
                layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                layout.setPadding(pad, pad, pad, pad);
                layout.addView(nameInput);
                layout.addView(commandInput);
                new AlertDialog.Builder(this)
                    .setTitle(R.string.nobi_startup_add_title)
                    .setView(layout)
                    .setPositiveButton(R.string.nobi_save, (d, w) -> {
                        String name = nameInput.getText().toString().trim();
                        String command = commandInput.getText().toString().trim();
                        if (name.isEmpty() || command.isEmpty()) return;
                        Set<String> updated = loadStartupCommands();
                        java.util.Iterator<String> it = updated.iterator();
                        while (it.hasNext()) {
                            if (it.next().startsWith(name + "\t")) it.remove();
                        }
                        updated.add(name + "\t" + command);
                        saveStartupCommands(updated);
                        Toast.makeText(this, R.string.nobi_startup_command_saved,
                            Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // v6 (7): pinned home-screen shortcut for a session or a command.
    private void showHomeShortcutDialog() {
        String[] items = new String[]{
            getString(R.string.nobi_shortcut_new_session),
            getString(R.string.nobi_shortcut_command)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_home_shortcut_title)
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(this, TermuxActivity.class);
                    intent.setAction(Intent.ACTION_RUN);
                    requestNobiShortcut("nobi_shortcut_session",
                        getString(R.string.nobi_shortcut_new_session),
                        R.drawable.ic_new_session, intent);
                } else {
                    TextInputDialogUtils.textInput(TermuxActivity.this,
                        R.string.nobi_shortcut_command_title, null,
                        R.string.nobi_save, text -> {
                            if (text == null || text.trim().isEmpty()) return;
                            String command = text.trim();
                            Intent intent = new Intent(this, TermuxActivity.class);
                            intent.setAction(Intent.ACTION_RUN);
                            intent.putExtra("nobi_run_command", command);
                            String label = command.length() > 25
                                ? command.substring(0, 25) : command;
                            requestNobiShortcut("nobi_shortcut_cmd_" + label.hashCode(),
                                label, R.drawable.ic_nobi_terminal, intent);
                        }, -1, null, -1, null, null);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void requestNobiShortcut(String id, String label, int iconRes, Intent intent) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            Toast.makeText(this, R.string.nobi_shortcut_unsupported,
                Toast.LENGTH_SHORT).show();
            return;
        }
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, R.string.nobi_shortcut_unsupported,
                Toast.LENGTH_SHORT).show();
            return;
        }
        ShortcutInfo info = new ShortcutInfo.Builder(this, id)
            .setShortLabel(label)
            .setIcon(Icon.createWithResource(this, iconRes))
            .setIntent(intent)
            .build();
        manager.requestPinShortcut(info, null);
        Toast.makeText(this, R.string.nobi_shortcut_added, Toast.LENGTH_SHORT).show();
    }

    // v6 (14): one tap to start a web or SSH server in its own session.
    private void showServerDialog() {
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
        String[] items = new String[]{
            getString(R.string.nobi_server_web),
            getString(R.string.nobi_server_ssh)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_server_title)
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    mTermuxTerminalSessionActivityClient.addNewSession(false, "Web Server");
                    writeToCurrentSession(
                        "command -v python3 >/dev/null || pkg install -y python; "
                            + "python3 -m http.server 8080", true);
                    Toast.makeText(this, R.string.nobi_server_web_started,
                        Toast.LENGTH_LONG).show();
                } else {
                    mTermuxTerminalSessionActivityClient.addNewSession(false, "SSH Server");
                    writeToCurrentSession(
                        "command -v sshd >/dev/null || pkg install -y openssh; "
                            + "sshd -p 8022 && echo SSH server running on port 8022", true);
                    Toast.makeText(this, R.string.nobi_server_ssh_started,
                        Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // endregion

    // region v6 (13): app lock (PIN; biometric stays pending, needs a new dep)

    /** SHA-256 hex of a PIN, for storage comparison (never store the PIN). */
    public static String nobiSha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void checkNobiAppLock() {
        if (isFinishing()) return;
        String hash = getSharedPreferences("nobi_applock", MODE_PRIVATE)
            .getString("pin_hash", null);
        if (hash == null) return;
        if (mNobiAppUnlocked && (mNobiBackgroundSince == 0
            || System.currentTimeMillis() - mNobiBackgroundSince < 60000)) return;
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
            .setTitle(R.string.nobi_app_locked_title)
            .setMessage(R.string.nobi_app_locked_message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.nobi_unlock, (dialog, which) -> {
                if (hash.equals(nobiSha256(input.getText().toString()))) {
                    mNobiAppUnlocked = true;
                    mNobiBackgroundSince = 0;
                } else {
                    Toast.makeText(this, R.string.nobi_wrong_pin,
                        Toast.LENGTH_SHORT).show();
                    checkNobiAppLock();
                }
            })
            .setNeutralButton(R.string.nobi_exit, (dialog, which) -> finish())
            .show();
    }

    // endregion

    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (shouldExtraKeysBeVisible()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        boolean visible = showNow && !(mPreferences.isExtraKeysOnlyIfNoHardware() && KeyboardUtils.isHardKeyboardConnected(this));
        terminalToolbarViewPager.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    /**
     * Whether the extra keys toolbar should be visible, considering both the user toolbar toggle
     * and the "extra keys only if no hardware" setting.
     */
    public boolean shouldExtraKeysBeVisible() {
        return mPreferences.shouldShowTerminalToolbar()
            && !(mPreferences.isExtraKeysOnlyIfNoHardware() && KeyboardUtils.isHardKeyboardConnected(this));
    }

    /**
     * Update extra keys toolbar visibility based on current hardware keyboard state and preferences.
     */
    public void updateExtraKeysVisibility() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;
        terminalToolbarViewPager.setVisibility(shouldExtraKeysBeVisible() ? View.VISIBLE : View.GONE);
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    private void setNewWindowButtonView() {
        View newWindowButton = findViewById(R.id.new_window_button);
        if (newWindowButton != null) {
            newWindowButton.setOnClickListener(v -> addNewWindow());
            // Show if multi-window API is available (N+)
            newWindowButton.setVisibility(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? View.VISIBLE : View.GONE);
        }

        // Use vertical orientation for drawer buttons if multi-window is available
        LinearLayout drawerButtons = findViewById(R.id.drawer_buttons);
        if (drawerButtons != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            drawerButtons.setOrientation(LinearLayout.VERTICAL);
        }
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;
        // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
        com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            // w19: dialog khulte hi keyboard neeche karo, is dialog me typing nahi hai.
            com.termux.shared.view.KeyboardUtils.hideSoftKeyboard(this, mTerminalView);
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
        refreshNobiTabs();
    }

    public void refreshNobiTabs() {
        if (mNobiTabsController != null)
            mNobiTabsController.refreshTabs();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public int getActivityId() {
        return mActivityId;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
