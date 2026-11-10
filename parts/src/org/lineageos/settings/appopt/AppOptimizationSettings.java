/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.appopt;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.icu.text.Collator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lineageos.settings.R;

public class AppOptimizationSettings extends PreferenceFragmentCompat implements
        AppOptimizationUtils.ProgressListener {

    private static final String KEY_HEADER = "app_opt_header";
    private static final String KEY_USER_APPS = "app_opt_user_apps";
    private static final String KEY_SYSTEM_APPS = "app_opt_system_apps";
    private static final int MENU_REFRESH = Menu.FIRST;

    // Status reads touch every app's oat files, keep them off the main thread
    private final ExecutorService mStatusExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private AppOptimizationHeaderPreference mHeader;
    private PreferenceCategory mUserApps;
    private PreferenceCategory mSystemApps;
    private int mOptimizedPercent = -1;
    private int mOptimized;
    private int mTotal;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.app_optimization_settings, rootKey);
        setHasOptionsMenu(true);

        mHeader = (AppOptimizationHeaderPreference) findPreference(KEY_HEADER);
        mHeader.setOnStartListener(() -> {
            AppOptimizationUtils.optimizeAll(getContext());
            updateHeader();
        });

        findPreference(AppOptimizationUtils.PREF_COMPILE_GMS).setOnPreferenceChangeListener(
                (preference, value) -> {
                    AppOptimizationUtils.onCompileGmsChanged(getContext(), (Boolean) value);
                    // Listener runs before the value is saved, so recount afterwards
                    mHandler.post(this::refreshStatus);
                    return true;
                });

        mUserApps = (PreferenceCategory) findPreference(KEY_USER_APPS);
        mSystemApps = (PreferenceCategory) findPreference(KEY_SYSTEM_APPS);
        addApps();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, R.string.app_opt_refresh);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_REFRESH) {
            refreshStatus();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();
        AppOptimizationUtils.setProgressListener(this);
        refreshStatus();
    }

    @Override
    public void onPause() {
        super.onPause();
        AppOptimizationUtils.setProgressListener(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStatusExecutor.shutdownNow();
    }

    @Override
    public void onProgress(int done, int total) {
        updateHeader();
    }

    @Override
    public void onFinished() {
        refreshStatus();
    }

    private void refreshStatus() {
        final Context context = getContext().getApplicationContext();
        mStatusExecutor.execute(() -> {
            final PackageManager pm = context.getPackageManager();
            final Map<String, Integer> statuses = new HashMap<>();
            int optimized = 0;
            int total = 0;
            for (ApplicationInfo info : AppOptimizationUtils.getOptimizableApps(pm)) {
                int status = AppOptimizationUtils.getStatus(info);
                // GMS with its switch off is skipped on purpose, so don't count
                // it as an app that still needs optimizing
                if (status == AppOptimizationUtils.STATUS_NOT_OPTIMIZED
                        && AppOptimizationUtils.isLeftAsIs(context, info.packageName)) {
                    status = AppOptimizationUtils.STATUS_LEFT_AS_IS;
                }
                statuses.put(info.packageName, status);
                if (status == AppOptimizationUtils.STATUS_UNKNOWN
                        || status == AppOptimizationUtils.STATUS_LEFT_AS_IS) {
                    continue;
                }
                total++;
                if (status != AppOptimizationUtils.STATUS_NOT_OPTIMIZED) {
                    optimized++;
                }
            }

            final int finalOptimized = optimized;
            final int finalTotal = total;
            mHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                mOptimized = finalOptimized;
                mTotal = finalTotal;
                mOptimizedPercent = finalTotal == 0 ? -1 : finalOptimized * 100 / finalTotal;
                for (PreferenceCategory category : new PreferenceCategory[] {
                        mUserApps, mSystemApps }) {
                    for (int i = 0; i < category.getPreferenceCount(); i++) {
                        Preference pref = category.getPreference(i);
                        Integer status = statuses.get(pref.getKey());
                        if (!(pref instanceof SwitchPreference) || status == null) {
                            continue;
                        }
                        // Start optimization also compiles apps without a usage
                        // profile with speed; only call it ODEX precompile when
                        // the user picked it for that app.
                        if (status == AppOptimizationUtils.STATUS_PRECOMPILED
                                && !((SwitchPreference) pref).isChecked()) {
                            status = AppOptimizationUtils.STATUS_OPTIMIZED;
                        }
                        pref.setSummary(statusSummary(status));
                    }
                }
                updateHeader();
            });
        });
    }

    private void updateHeader() {
        if (AppOptimizationUtils.isOptimizingAll()) {
            int total = AppOptimizationUtils.getTotal();
            int done = AppOptimizationUtils.getProgress();
            mHeader.update(total == 0 ? 0 : done * 100 / total,
                    getString(R.string.app_opt_state_running, done, total), true);
            return;
        }

        long lastRun = AppOptimizationUtils.getLastRun(getContext());
        CharSequence state;
        if (mTotal == 0) {
            state = getString(R.string.app_opt_state_checking);
        } else if (lastRun != 0 && mOptimized == mTotal) {
            state = getString(R.string.app_opt_state_complete,
                    DateUtils.getRelativeTimeSpanString(lastRun, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS));
        } else {
            state = getString(R.string.app_opt_state_idle, mOptimized, mTotal);
        }
        mHeader.update(mOptimizedPercent, state, false);
    }

    private int statusSummary(int status) {
        switch (status) {
            case AppOptimizationUtils.STATUS_PRECOMPILED:
                return R.string.app_opt_status_precompiled;
            case AppOptimizationUtils.STATUS_OPTIMIZED:
                return R.string.app_opt_status_optimized;
            case AppOptimizationUtils.STATUS_NOT_OPTIMIZED:
                return R.string.app_opt_status_not_optimized;
            case AppOptimizationUtils.STATUS_LEFT_AS_IS:
                return R.string.app_opt_status_left_as_is;
            default:
                return R.string.app_opt_status_unknown;
        }
    }

    private static final class AppEntry {
        final String packageName;
        final CharSequence label;
        final Drawable icon;
        final boolean system;

        AppEntry(String packageName, CharSequence label, Drawable icon, boolean system) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.system = system;
        }
    }

    /**
     * Lists every app on the device, not just the ones with a launcher icon,
     * so system apps and services can be precompiled too. Labels and icons
     * are loaded off the main thread since there can be hundreds of apps.
     * Runs on mStatusExecutor before the first refreshStatus(), so the
     * switches exist by the time their status is filled in.
     */
    private void addApps() {
        final Context context = getContext();
        final String ownPackage = context.getPackageName();
        mStatusExecutor.execute(() -> {
            final PackageManager pm = context.getPackageManager();
            final List<AppEntry> entries = new ArrayList<>();
            for (ApplicationInfo info : AppOptimizationUtils.getOptimizableApps(pm)) {
                if (info.packageName.equals(ownPackage)) {
                    continue;
                }
                entries.add(new AppEntry(info.packageName, info.loadLabel(pm),
                        info.loadIcon(pm), AppOptimizationUtils.isSystemApp(info)));
            }
            final Collator collator = Collator.getInstance();
            entries.sort((a, b) -> {
                int result = collator.compare(a.label.toString(), b.label.toString());
                return result != 0 ? result : a.packageName.compareTo(b.packageName);
            });

            mHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                final Set<String> full = AppOptimizationUtils.getFullPackages(context);
                for (AppEntry entry : entries) {
                    SwitchPreference pref = new SwitchPreference(context);
                    pref.setKey(entry.packageName);
                    pref.setTitle(entry.label);
                    pref.setIcon(entry.icon);
                    pref.setPersistent(false);
                    pref.setChecked(full.contains(entry.packageName));
                    pref.setOnPreferenceChangeListener((preference, value) -> {
                        AppOptimizationUtils.setFullyOptimized(context, entry.packageName,
                                (Boolean) value);
                        preference.setSummary(R.string.app_opt_status_compiling);
                        return true;
                    });
                    (entry.system ? mSystemApps : mUserApps).addPreference(pref);
                }
                mUserApps.setVisible(mUserApps.getPreferenceCount() > 0);
                mSystemApps.setVisible(mSystemApps.getPreferenceCount() > 0);
            });
        });
    }
}
