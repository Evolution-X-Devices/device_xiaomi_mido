/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.appopt;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragment;
import androidx.preference.SwitchPreference;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.lineageos.settings.R;

public class AppOptimizationSettings extends PreferenceFragment implements
        AppOptimizationUtils.ProgressListener {

    private static final String KEY_OPTIMIZE_ALL = "optimize_all";
    private static final String KEY_FULL_OPTIMIZATION = "full_optimization";

    private Preference mOptimizeAll;
    private PreferenceCategory mFullOptimization;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.app_optimization_settings, rootKey);

        mOptimizeAll = findPreference(KEY_OPTIMIZE_ALL);
        mOptimizeAll.setOnPreferenceClickListener(preference -> {
            AppOptimizationUtils.optimizeAll(getContext());
            updateOptimizeAll();
            return true;
        });

        mFullOptimization = (PreferenceCategory) findPreference(KEY_FULL_OPTIMIZATION);
        addApps();
    }

    @Override
    public void onResume() {
        super.onResume();
        AppOptimizationUtils.setProgressListener(this);
        updateOptimizeAll();
    }

    @Override
    public void onPause() {
        super.onPause();
        AppOptimizationUtils.setProgressListener(null);
    }

    @Override
    public void onProgress(int done, int total) {
        updateOptimizeAll();
    }

    @Override
    public void onFinished() {
        updateOptimizeAll();
    }

    private void updateOptimizeAll() {
        if (AppOptimizationUtils.isOptimizingAll()) {
            mOptimizeAll.setEnabled(false);
            mOptimizeAll.setSummary(getString(R.string.app_opt_all_progress,
                    AppOptimizationUtils.getProgress(), AppOptimizationUtils.getTotal()));
        } else {
            mOptimizeAll.setEnabled(true);
            mOptimizeAll.setSummary(R.string.app_opt_all_summary);
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
                return true;
            });
            mFullOptimization.addPreference(pref);
        }
    }
}
