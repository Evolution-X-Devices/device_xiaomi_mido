/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.appopt;

import android.app.AppGlobals;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppOptimizationUtils {

    private static final String TAG = "AppOptimization";

    // Ahead-of-time compile everything, ignoring usage profiles
    public static final String FILTER_FULL = "speed";
    // What background dexopt uses: compile the hot code from usage profiles
    public static final String FILTER_DEFAULT = "speed-profile";

    private static final String PREF_FULL_PACKAGES = "app_opt_full_packages";

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static volatile boolean sOptimizingAll;
    private static volatile int sProgress;
    private static volatile int sTotal;
    private static ProgressListener sListener;

    public interface ProgressListener {
        void onProgress(int done, int total);
        void onFinished();
    }

    private AppOptimizationUtils() {}

    public static boolean isOptimizingAll() {
        return sOptimizingAll;
    }

    public static int getProgress() {
        return sProgress;
    }

    public static int getTotal() {
        return sTotal;
    }

    public static void setProgressListener(ProgressListener listener) {
        sListener = listener;
    }

    public static Set<String> getFullPackages(Context context) {
        return new HashSet<>(PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(PREF_FULL_PACKAGES, new HashSet<>()));
    }

    public static boolean isFullyOptimized(Context context, String packageName) {
        return getFullPackages(context).contains(packageName);
    }

    public static void setFullyOptimized(Context context, String packageName, boolean full) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> packages = getFullPackages(context);
        if (full) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
        }
        prefs.edit().putStringSet(PREF_FULL_PACKAGES, packages).apply();

        sExecutor.execute(() -> compile(packageName, full ? FILTER_FULL : FILTER_DEFAULT, true));
    }

    public static void optimizeAll(Context context) {
        if (sOptimizingAll) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final Set<String> full = getFullPackages(appContext);
        final List<String> packages = new ArrayList<>();
        for (ApplicationInfo info : appContext.getPackageManager()
                .getInstalledApplications(PackageManager.MATCH_ALL)) {
            if (info.enabled && (info.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
                packages.add(info.packageName);
            }
        }

        sOptimizingAll = true;
        sProgress = 0;
        sTotal = packages.size();
        sExecutor.execute(() -> {
            for (String packageName : packages) {
                // Keep fully optimized apps on their filter
                compile(packageName, full.contains(packageName) ? FILTER_FULL : FILTER_DEFAULT,
                        false);
                sProgress++;
                sMainHandler.post(() -> {
                    if (sListener != null) sListener.onProgress(sProgress, sTotal);
                });
            }
            sOptimizingAll = false;
            sMainHandler.post(() -> {
                if (sListener != null) sListener.onFinished();
            });
        });
    }

    private static boolean compile(String packageName, String filter, boolean force) {
        try {
            boolean ok = AppGlobals.getPackageManager().performDexOptMode(packageName,
                    false /* checkProfiles */, filter, force, true /* bootComplete */,
                    null /* splitName */);
            if (!ok) Log.w(TAG, "Compiling " + packageName + " with " + filter + " failed");
            return ok;
        } catch (RemoteException | SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "Compiling " + packageName + " failed", e);
            return false;
        }
    }

    /**
     * App updates are compiled with the install filter, so restore full
     * optimization for the apps that asked for it.
     */
    public static void registerUpdateReceiver(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        context.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String packageName = intent.getData() != null
                        ? intent.getData().getSchemeSpecificPart() : null;
                if (packageName != null && isFullyOptimized(context, packageName)) {
                    sExecutor.execute(() -> compile(packageName, FILTER_FULL, true));
                }
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED);
    }
}
