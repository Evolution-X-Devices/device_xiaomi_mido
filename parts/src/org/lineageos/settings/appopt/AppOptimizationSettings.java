/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.appopt;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragment;
import androidx.preference.SwitchPreference;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lineageos.settings.R;

public class AppOptimizationSettings extends PreferenceFragment implements
        AppOptimizationUtils.ProgressListener {

    private static final String KEY_HEADER = "app_opt_header";
    private static final String KEY_PRECOMPILE = "full_optimization";
    private static final int MENU_REFRESH = Menu.FIRST;

    // Status reads touch every app's oat files, keep them off the main thread
    private final ExecutorService mStatusExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private AppOptimizationHeaderPreference mHeader;
    private PreferenceCategory mPrecompile;
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

        mPrecompile = (PreferenceCategory) findPreference(KEY_PRECOMPILE);
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
            for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.MATCH_ALL)) {
                if (!info.enabled || (info.flags & ApplicationInfo.FLAG_HAS_CODE) == 0) {
                    continue;
                }
                int status = AppOptimizationUtils.getStatus(info);
                statuses.put(info.packageName, status);
                if (status == AppOptimizationUtils.STATUS_UNKNOWN) {
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
                for (int i = 0; i < mPrecompile.getPreferenceCount(); i++) {
                    Preference pref = mPrecompile.getPreference(i);
                    Integer status = statuses.get(pref.getKey());
                    if (pref instanceof SwitchPreference && status != null) {
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
            default:
                return R.string.app_opt_status_unknown;
        }
    }

    private void addApps() {
        final Context context = getContext();
        final PackageManager pm = context.getPackageManager();
        final Set<String> full = AppOptimizationUtils.getFullPackages(context);

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        apps.sort(new ResolveInfo.DisplayNameComparator(pm));

        Set<String> added = new HashSet<>();
        for (ResolveInfo app : apps) {
            final String packageName = app.activityInfo.packageName;
            if (!added.add(packageName) || packageName.equals(context.getPackageName())) {
                continue;
            }

            SwitchPreference pref = new SwitchPreference(context);
            pref.setKey(packageName);
            pref.setTitle(app.loadLabel(pm));
            pref.setIcon(app.loadIcon(pm));
            pref.setPersistent(false);
            pref.setChecked(full.contains(packageName));
            pref.setOnPreferenceChangeListener((preference, value) -> {
                AppOptimizationUtils.setFullyOptimized(context, packageName, (Boolean) value);
                preference.setSummary(R.string.app_opt_status_compiling);
                return true;
            });
            mPrecompile.addPreference(pref);
        }
    }
}
