/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.appopt;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class ArcProgressView extends View {

    private static final float START_ANGLE = 150f;
    private static final float SWEEP_ANGLE = 240f;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private float mProgress;

    public ArcProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);

        float stroke = 14 * getResources().getDisplayMetrics().density;
        TypedArray a = context.obtainStyledAttributes(new int[] {
                android.R.attr.colorAccent, android.R.attr.textColorPrimary });
        int accent = a.getColor(0, 0xff4285f4);
        int track = a.getColor(1, 0xff808080);
        a.recycle();

        for (Paint paint : new Paint[] { mTrackPaint, mProgressPaint }) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(stroke);
        }
        mTrackPaint.setColor(track);
        mTrackPaint.setAlpha(0x1f);
        mProgressPaint.setColor(accent);
    }

    /** @param progress 0 to 1 */
    public void setProgress(float progress) {
        mProgress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = mTrackPaint.getStrokeWidth() / 2;
        float size = Math.min(getWidth(), getHeight() * 1.2f) - 2 * inset;
        float left = (getWidth() - size) / 2;
        mBounds.set(left, inset, left + size, inset + size);

        canvas.drawArc(mBounds, START_ANGLE, SWEEP_ANGLE, false, mTrackPaint);
        if (mProgress > 0) {
            canvas.drawArc(mBounds, START_ANGLE, SWEEP_ANGLE * mProgress, false, mProgressPaint);
        }
    }
}
