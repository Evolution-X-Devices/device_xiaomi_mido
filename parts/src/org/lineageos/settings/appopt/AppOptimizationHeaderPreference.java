/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.appopt;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.widget.Button;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.lineageos.settings.R;

public class AppOptimizationHeaderPreference extends Preference {

    private int mPercent = -1;
    private CharSequence mState;
    private boolean mRunning;
    private Runnable mOnStart;

    public AppOptimizationHeaderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.app_optimization_header);
        setSelectable(false);
    }

    public void setOnStartListener(Runnable onStart) {
        mOnStart = onStart;
    }

    /** @param percent 0 to 100, or -1 while unknown */
    public void update(int percent, CharSequence state, boolean running) {
        mPercent = percent;
        mState = state;
        mRunning = running;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        ArcProgressView arc = (ArcProgressView) holder.findViewById(R.id.app_opt_arc);
        arc.setProgress(mPercent < 0 ? 0 : mPercent / 100f);

        String number = mPercent < 0 ? "--" : String.valueOf(mPercent);
        SpannableString percent = new SpannableString(number + "%");
        percent.setSpan(new RelativeSizeSpan(0.4f), number.length(), percent.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((TextView) holder.findViewById(R.id.app_opt_percent)).setText(percent);

        ((TextView) holder.findViewById(R.id.app_opt_state)).setText(mState);

        Button start = (Button) holder.findViewById(R.id.app_opt_start);
        start.setEnabled(!mRunning);
        start.setAlpha(mRunning ? 0.5f : 1f);
        start.setText(mRunning ? R.string.app_opt_button_running : R.string.app_opt_button_start);
        start.setOnClickListener(v -> {
            if (mOnStart != null) mOnStart.run();
        });
    }
}
