/*
* Copyright (C) 2013-2015 SlimRoms Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.util.slim;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.VectorDrawable;
import android.graphics.drawable.Drawable;

public class ColorHelper {

    public static Bitmap getColoredBitmap(Drawable d, int color) {
        if (d instanceof VectorDrawable) {
            return null; // just in case a vector somehow gets passed here
        }
        Bitmap colorBitmap = ((BitmapDrawable) d).getBitmap();
        Bitmap grayscaleBitmap = toGrayscale(colorBitmap);
        Paint pp = new Paint();
        PorterDuffColorFilter frontFilter =
            new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY);
        pp.setColorFilter(frontFilter);
        Canvas cc = new Canvas(grayscaleBitmap);
        cc.drawBitmap(grayscaleBitmap, 0, 0, pp);
        return grayscaleBitmap;
    }

    public static Drawable getColoredVector(Drawable d, int color) {
        if (d == null) {
            return null;
        }
        if (d instanceof BitmapDrawable) {
            return null; // just in case a bitmap somehow gets passed here
        }

        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0); // apply grayscale

        // convert color int to values that can be used in matrix
        float red = (float) (Color.red(color) / 255);
        float green = (float) (Color.green(color) / 255);
        float blue = (float) (Color.blue(color) / 255);

        // now, apply the color tint
        ColorMatrix tint = new ColorMatrix();
        tint.setScale(red, green, blue, 1);
        m.postConcat(tint);

        // apply color filter to vector
        d.setColorFilter(new ColorMatrixColorFilter(m));

        //return the tinted vector
        return d;
    }

    private static Bitmap toGrayscale(Bitmap bmpOriginal) {
        int width, height;
        height = bmpOriginal.getHeight();
        width = bmpOriginal.getWidth();

        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);

        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(bmpOriginal, 0, 0, paint);
        return bmpGrayscale;
    }

}
