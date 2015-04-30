/*
 * Copyright (C) 2014 The TeamEos Project
 * Author: Randall Rushing aka Bigrushdog
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
 * 
 * Get renderers
 *
 */

package com.android.systemui.nx.eyecandy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

public class RenderFactory {
    enum RenderType {
        Line, Bar
    }

    public static Renderer get(Context context, RenderType type) {
        switch (type) {
            case Line:
                return getLineRenderer(context);
            case Bar:
                return getBarRenderer(context);
            default:
                return getBarRenderer(context);
        }
    }

    private static LineRenderer getLineRenderer(Context context) {
        Paint linePaint = new Paint();
        linePaint.setStrokeWidth(1f);
        linePaint.setAntiAlias(true);
        linePaint.setColor(Color.argb(88, 0, 128, 255));

        Paint lineFlashPaint = new Paint();
        lineFlashPaint.setStrokeWidth(5f);
        lineFlashPaint.setAntiAlias(true);
        lineFlashPaint.setColor(Color.argb(188, 255, 255, 255));
        return new LineRenderer(linePaint, lineFlashPaint, true);
    }

    private static BarGraphRenderer getBarRenderer(Context context) {
        Paint paint = new Paint();
        paint.setStrokeWidth(50f);
        paint.setAntiAlias(true);
        // paint.setColor(Color.argb(200, 56, 138, 252));
        paint.setColor(Color.argb(188, 255, 255, 255));
        //paint.setColor(Color.argb(255, 255, 255, 255));
        return new BarGraphRenderer(16, paint, false);
    }

    /**
     * Copyright 2011, Felix Palmer Licensed under the MIT license:
     * http://creativecommons.org/licenses/MIT/
     */

    private static class LineRenderer extends Renderer
    {
        private Paint mPaint;
        private Paint mFlashPaint;
        private boolean mCycleColor;
        private float amplitude = 0;

        /**
         * Renders the audio data onto a line. The line flashes on prominent
         * beats
         * 
         * @param canvas
         * @param paint - Paint to draw lines with
         * @param paint - Paint to draw flash with
         */
        public LineRenderer(Paint paint, Paint flashPaint)
        {
            this(paint, flashPaint, false);
        }

        /**
         * Renders the audio data onto a line. The line flashes on prominent
         * beats
         * 
         * @param canvas
         * @param paint - Paint to draw lines with
         * @param paint - Paint to draw flash with
         * @param cycleColor - If true the color will change on each frame
         */
        public LineRenderer(Paint paint,
                Paint flashPaint,
                boolean cycleColor)
        {
            super();
            mPaint = paint;
            mFlashPaint = flashPaint;
            mCycleColor = cycleColor;
        }

        @Override
        public void onRender(Canvas canvas, AudioData data, Rect rect)
        {
            if (mCycleColor)
            {
                cycleColor();
            }

            // Calculate points for line
            for (int i = 0; i < data.bytes.length - 1; i++) {
                mPoints[i * 4] = rect.width() * i / (data.bytes.length - 1);
                mPoints[i * 4 + 1] = rect.height() / 2
                        + ((byte) (data.bytes[i] + 128)) * (rect.height() / 3) / 128;
                mPoints[i * 4 + 2] = rect.width() * (i + 1) / (data.bytes.length - 1);
                mPoints[i * 4 + 3] = rect.height() / 2
                        + ((byte) (data.bytes[i + 1] + 128)) * (rect.height() / 3) / 128;
            }

            // Calc amplitude for this waveform
            float accumulator = 0;
            for (int i = 0; i < data.bytes.length - 1; i++) {
                accumulator += Math.abs(data.bytes[i]);
            }

            float amp = accumulator / (128 * data.bytes.length);
            if (amp > amplitude)
            {
                // Amplitude is bigger than normal, make a prominent line
                amplitude = amp;
                canvas.drawLines(mPoints, mFlashPaint);
            }
            else
            {
                // Amplitude is nothing special, reduce the amplitude
                amplitude *= 0.99;
                canvas.drawLines(mPoints, mPaint);
            }
        }

        @Override
        public void onRender(Canvas canvas, FFTData data, Rect rect)
        {
            // Do nothing, we only display audio data
        }

        private float colorCounter = 0;

        private void cycleColor()
        {
            int r = (int) Math.floor(128 * (Math.sin(colorCounter) + 3));
            int g = (int) Math.floor(128 * (Math.sin(colorCounter + 1) + 1));
            int b = (int) Math.floor(128 * (Math.sin(colorCounter + 7) + 1));
            mPaint.setColor(Color.argb(128, r, g, b));
            colorCounter += 0.03;
        }
    }

    /**
     * Copyright 2011, Felix Palmer Licensed under the MIT license:
     * http://creativecommons.org/licenses/MIT/
     */

    private static class BarGraphRenderer extends Renderer
    {
        private int mDivisions;
        private Paint mPaint;
        //private boolean mTop;

        /**
         * Renders the FFT data as a series of lines, in histogram form
         * 
         * @param divisions - must be a power of 2. Controls how many lines to
         *            draw
         * @param paint - Paint to draw lines with
         * @param top - whether to draw the lines at the top of the canvas, or
         *            the bottom
         */
        public BarGraphRenderer(int divisions,
                Paint paint,
                boolean top)
        {
            super();
            mDivisions = divisions;
            mPaint = paint;
            //mTop = top;
        }

        @Override
        public void onRender(Canvas canvas, AudioData data, Rect rect)
        {
            // Do nothing, we only display FFT data
        }

        @Override
        public void onRender(Canvas canvas, FFTData data, Rect rect)
        {
            for (int i = 0; i < data.bytes.length / mDivisions; i++) {
                mFFTPoints[i * 4] = i * 4 * mDivisions;
                mFFTPoints[i * 4 + 2] = i * 4 * mDivisions;
                byte rfk = data.bytes[mDivisions * i];
                byte ifk = data.bytes[mDivisions * i + 1];
                float magnitude = (rfk * rfk + ifk * ifk);
                int dbValue = (int) (10 * Math.log10(magnitude));

                mFFTPoints[i * 4 + 1] = rect.height();
                mFFTPoints[i * 4 + 3] = rect.height() - (dbValue * 2 - 10);
            }

            canvas.drawLines(mFFTPoints, mPaint);
        }
    }
}
