/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.appopt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppOptimizationUtils {

    private static final String TAG = "AppOptimization";

    // ODEX precompile: ahead-of-time compile everything, ignoring usage profiles
    public static final String FILTER_FULL = "speed";
    // What background dexopt uses: compile the hot code from usage profiles
    public static final String FILTER_DEFAULT = "speed-profile";

    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_NOT_OPTIMIZED = 1;
    public static final int STATUS_OPTIMIZED = 2;
    public static final int STATUS_PRECOMPILED = 3;

    private static final String PREF_FULL_PACKAGES = "app_opt_full_packages";
    private static final String PREF_LAST_RUN = "app_opt_last_run";

    // Handled by the xiaomiparts_appopt init service, which runs as shell because
    // ART only takes compile requests from root or shell
    private static final String PROP = "sys.xiaomiparts.appopt";
    private static final String PROP_REQUEST = PROP + ".request";
    private static final long COMPILE_TIMEOUT_MS = 10 * 60 * 1000;

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

    public static long getLastRun(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getLong(PREF_LAST_RUN, 0);
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

        sExecutor.execute(() -> {
            compile(packageName, full ? FILTER_FULL : FILTER_DEFAULT, true);
            notifyFinished();
        });
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
                // Keep precompiled apps on their filter
                compile(packageName, full.contains(packageName) ? FILTER_FULL : FILTER_DEFAULT,
                        false);
                sProgress++;
                sMainHandler.post(() -> {
                    if (sListener != null) sListener.onProgress(sProgress, sTotal);
                });
            }
            PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                    .putLong(PREF_LAST_RUN, System.currentTimeMillis()).apply();
            sOptimizingAll = false;
            notifyFinished();
        });
    }

    private static void notifyFinished() {
        sMainHandler.post(() -> {
            if (sListener != null) sListener.onFinished();
        });
    }

    // Runs on sExecutor only, so requests never overlap
    private static boolean compile(String packageName, String filter, boolean force) {
        SystemProperties.set(PROP + ".pkg", packageName);
        SystemProperties.set(PROP + ".filter", filter);
        SystemProperties.set(PROP + ".force", force ? "1" : "0");
        SystemProperties.set(PROP_REQUEST, "start");

        final long deadline = SystemClock.elapsedRealtime() + COMPILE_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            String result = SystemProperties.get(PROP_REQUEST);
            if ("done".equals(result)) {
                return true;
            }
            if ("failed".equals(result)) {
                Log.w(TAG, "Compiling " + packageName + " with " + filter + " failed");
                return false;
            }
            SystemClock.sleep(100);
        }

        Log.e(TAG, "Timed out compiling " + packageName);
        SystemProperties.set(PROP_REQUEST, "failed");
        return false;
    }

    /**
     * Reads the compiler filter of the app's primary dex code, the same way
     * ART reports it in dumpsys package dexopt.
     */
    public static int getStatus(ApplicationInfo info) {
        String abi = info.primaryCpuAbi != null ? info.primaryCpuAbi : Build.SUPPORTED_ABIS[0];
        String filter;
        try {
            // Module-lib API, so not in the platform stubs Parts builds against
            Method method = Class.forName("dalvik.system.DexFile").getMethod(
                    "getDexFileOptimizationInfo", String.class, String.class);
            Object optInfo = method.invoke(null, info.sourceDir, instructionSet(abi));
            filter = (String) optInfo.getClass().getMethod("getStatus").invoke(optInfo);
        } catch (Exception e) {
            Log.d(TAG, "Unable to read the optimization status of " + info.packageName, e);
            return STATUS_UNKNOWN;
        }

        switch (filter) {
            case "speed":
            case "everything":
                return STATUS_PRECOMPILED;
            case "speed-profile":
                return STATUS_OPTIMIZED;
            case "verify":
            case "quicken":
            case "space":
            case "space-profile":
            case "extract":
            case "assume-verified":
            case "run-from-apk":
            case "run-from-apk-fallback":
                return STATUS_NOT_OPTIMIZED;
            default:
                return STATUS_UNKNOWN;
        }
    }

    private static String instructionSet(String abi) {
        if (abi.startsWith("arm64")) return "arm64";
        if (abi.startsWith("armeabi")) return "arm";
        if (abi.startsWith("x86_64")) return "x86_64";
        if (abi.startsWith("x86")) return "x86";
        return abi;
    }

    /**
     * App updates are compiled with the install filter, so restore ODEX
     * precompile for the apps that asked for it.
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
