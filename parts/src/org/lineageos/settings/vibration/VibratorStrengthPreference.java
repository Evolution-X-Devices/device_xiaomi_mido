/*
 * Copyright (C) 2017-2022 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.vibration;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.os.Vibrator;
import android.provider.Settings;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

import org.lineageos.settings.R;
import org.lineageos.settings.preferences.CustomSeekBarPreference;
import org.lineageos.settings.preferences.FileUtils;

public class VibratorStrengthPreference extends CustomSeekBarPreference {

    private static int mMinVal = 116;
    private static int mMaxVal = 3596;
    private static int mDefVal = mMaxVal - (mMaxVal - mMinVal) / 4;
    private Vibrator mVibrator;

    private static final String FILE_LEVEL = "/sys/module/leds_qpnp_haptics/parameters/vmax_mv_override";
    private static final long[] testVibrationPattern = {0,250};

    public VibratorStrengthPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        mInterval = 10;
        mShowSign = false;
        mUnits = "";
        mContinuousUpdates = false;
        mMinValue = mMinVal;
        mMaxValue = mMaxVal;
        mDefaultValueExists = true;
        mDefaultValue = mDefVal;
        mValue = Integer.parseInt(loadValue(context));

        setPersistent(false);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public static boolean isSupported() {
        return FileUtils.fileWritable(FILE_LEVEL);
    }

    // The kernel node reads 0 while the override is off, so keep the level in prefs
    public static String loadValue(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString("vib_strength", String.valueOf(mDefVal));
    }

    public static boolean isOverrideEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                VibratorSettings.PREF_VMAX_OVERRIDE_SWITCH, 0) == 1;
    }

    // 0 hands VMAX back to the driver default
    public static void apply(Context context, boolean enabled) {
        if (!isSupported()) {
            return;
        }

        FileUtils.writeValue(FILE_LEVEL, enabled ? loadValue(context) : "0");
    }

    private void setValue(String newValue) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(getContext()).edit();
        editor.putString("vib_strength", newValue);
        editor.commit();
        if (isOverrideEnabled(getContext())) {
            FileUtils.writeValue(FILE_LEVEL, newValue);
        }
        mVibrator.vibrate(testVibrationPattern, -1);
    }

    public static void restore(Context context) {
        apply(context, isOverrideEnabled(context));
    }

    @Override
    protected void changeValue(int newValue) {
        setValue(String.valueOf(newValue));
    }
}
