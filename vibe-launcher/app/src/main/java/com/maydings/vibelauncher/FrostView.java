package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Frosted backdrop for the top bar: a blurred copy of whatever part of the grid has scrolled under it.
 * Android 9 has no live blur, so on each frame the covered strip of the grid is drawn into a bitmap at
 * 1/8 size, box-blurred and stretched back up. The real grid is clipped off under the bar, and the
 * dark tint fades in while tiles are underneath, and also while a backdrop is showing, so the clock
 * and buttons stay legible over bright artwork.
 */
public class FrostView extends View {

    private static final int SCALE = 8;           // the strip is drawn at 1/8 size
    private static final int BLUR_RADIUS = 2;     // in downscaled pixels, three passes ~ a gaussian
    private static final int TINT = 0x8C000000;   // darkens the frost so the clock and buttons stay legible
    /** tvOS's shelf has no bottom edge: the tint fades out over the last part of the bar instead. */
    private static final float FADE_FRACTION = 0.45f;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint();
    private final Rect dst = new Rect();
    private final int[] sourceLoc = new int[2];
    private final int[] myLoc = new int[2];
    private final float fadeDistance;

    private ViewGroup source;
    /** The backdrop picture; the bar darkens it as far as it is faded in. */
    private View backdrop;
    /** How much of a backdrop is under the bar right now (its alpha), 0..1. */
    private float under;
    private Bitmap small;   // work bitmap the tiles are drawn into
    private Bitmap frost;   // finished, blurred result that onDraw shows
    private Canvas smallCanvas;
    private int[] pixels, scratch, shown;
    /** The bar's fade, kept between frames: making one per draw allocated on every frame. */
    private LinearGradient fadeShader;
    private int fadeHeight, fadeColor;
    /** 0 = nothing under the bar (invisible), 1 = fully frosted. */
    private float amount;

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        update();
        return true;
    };

    /** Views that were waiting to redraw when the snapshot ran (reused to avoid allocating per frame). */
    private final List<View> dirty = new ArrayList<>();

    public FrostView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fadeDistance = 24f * getResources().getDisplayMetrics().density;
    }

    /** No height of its own: it fills whatever its parent sizes to (the top bar). */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0;
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    /** The view to frost (the app grid). */
    void setSource(ViewGroup grid) {
        source = grid;
    }

    /** The backdrop behind everything: tinted under the bar the same way tiles are. */
    void setBackdrop(View view) {
        backdrop = view;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Invalidating from a pre-draw callback joins the frame being drawn; it doesn't schedule another.
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    /**
     * Runs before every frame. Only invalidates when the frost actually changed: invalidating
     * unconditionally kept the launcher drawing 60 frames a second while idle.
     */
    private void update() {
        if (source == null || getWidth() == 0 || getHeight() == 0) return;
        clipGridUnderBar();
        float b = backdrop != null && backdrop.getVisibility() == VISIBLE ? backdrop.getAlpha() : 0f;
        if (b != under) {
            under = b;
            invalidate();
        }
        float a = coverage();
        if (a <= 0f) {
            if (amount != 0f || frost != null) {
                amount = 0f;
                frost = null;
                shown = null;
                invalidate();
            }
            return;
        }
        boolean amountChanged = a != amount;
        amount = a;

        int w = Math.max(1, getWidth() / SCALE), h = Math.max(1, getHeight() / SCALE);
        if (small == null || small.getWidth() != w || small.getHeight() != h) {
            small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            smallCanvas = new Canvas(small);
            pixels = new int[w * h];
            scratch = new int[w * h];
            shown = null;
        }
        source.getLocationInWindow(sourceLoc);
        getLocationInWindow(myLoc);
        small.eraseColor(Color.TRANSPARENT);
        smallCanvas.save();
        smallCanvas.scale(1f / SCALE, 1f / SCALE);
        smallCanvas.translate(sourceLoc[0] - myLoc[0], sourceLoc[1] - myLoc[1]);
        drawTilesUnderBar();
        smallCanvas.restore();

        small.getPixels(pixels, 0, w, 0, 0, w, h);
        premultiply(pixels);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(pixels, scratch, w, h, BLUR_RADIUS, true);
            boxBlur(scratch, pixels, w, h, BLUR_RADIUS, false);
        }
        unpremultiply(pixels);
        // The bar's bottom edge must not be a hard line across the artwork. The tint already fades
        // there; the frost has to fade with it, or the blurred copy ends mid-tile in a straight cut.
        fadeBottom(pixels, w, h);
        if (!amountChanged && shown != null && Arrays.equals(pixels, shown)) return;
        shown = pixels.clone();
        // A fresh bitmap per change, never touched again: on Android 10+ the render thread can still be
        // reading the last one while the next frame is drawn into the work bitmap, which flashed
        // half-finished (unblurred, blocky) frost.
        frost = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
        invalidate();
    }

    /**
     * Draws just the tiles reaching into the bar, each with its own position, scale and alpha. Not
     * source.draw(): on Android 10+ a software draw of the whole grid cleared the "needs redraw" marks
     * the real hardware draw relies on, so freshly scrolled-in rows never appeared. The few tiles drawn
     * here get the same treatment, so any that were waiting to redraw are marked again afterwards.
     */
    private void drawTilesUnderBar() {
        int barBottom = myLoc[1] + getHeight();
        dirty.clear();
        for (int i = 0; i < source.getChildCount(); i++) {
            View child = source.getChildAt(i);
            if (child.getVisibility() != VISIBLE || child.getAlpha() <= 0f) continue;
            child.getLocationInWindow(sourceLoc);
            if (sourceLoc[1] >= barBottom) continue;     // getLocationInWindow includes translation and scale
            collectDirty(child);
            int save = smallCanvas.save();
            smallCanvas.translate(child.getLeft(), child.getTop());
            smallCanvas.concat(child.getMatrix());
            if (child.getAlpha() < 1f) {
                smallCanvas.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), (int) (255 * child.getAlpha()));
            }
            try {
                child.draw(smallCanvas);
            } catch (RuntimeException e) {
                // e.g. a hardware-only bitmap that a software canvas can't draw: frost what we have.
            }
            smallCanvas.restoreToCount(save);
        }
        for (int i = 0; i < dirty.size(); i++) dirty.get(i).invalidate();
        dirty.clear();
    }

    private void collectDirty(View v) {
        if (v.isDirty()) dirty.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectDirty(g.getChildAt(i));
        }
    }

    /**
     * The real grid isn't drawn under the bar at all; only the frosted copy is. Fading the frost in
     * over sharp tiles let a white tile (Netflix, YouTube) flash through for a frame or two.
     */
    private void clipGridUnderBar() {
        View wrapper = (View) source.getParent();
        wrapper.getLocationInWindow(sourceLoc);
        getLocationInWindow(myLoc);
        int top = Math.max(0, myLoc[1] + getHeight() - sourceLoc[1]);
        Rect clip = wrapper.getClipBounds();
        if (clip != null && clip.top == top && clip.right == wrapper.getWidth()
                && clip.bottom == wrapper.getHeight()) return;
        wrapper.setClipBounds(new Rect(0, top, wrapper.getWidth(), wrapper.getHeight()));
    }

    /** How far tiles reach up into the bar, eased over {@link #fadeDistance}. */
    private float coverage() {
        getLocationInWindow(myLoc);
        int barTop = myLoc[1], barBottom = barTop + getHeight();
        int reach = 0;
        for (int i = 0; i < source.getChildCount(); i++) {
            View child = source.getChildAt(i);
            child.getLocationInWindow(sourceLoc);
            int top = sourceLoc[1] + (int) child.getTranslationY();
            int bottom = top + child.getHeight();
            if (bottom > barTop && top < barBottom) reach = Math.max(reach, barBottom - top);
        }
        return Math.min(1f, reach / fadeDistance);
    }

    /** One horizontal or vertical box-blur pass over premultiplied ARGB pixels. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[base + clamp(k, len) * step];
                sa += p >>> 24;
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = (sa / div) << 24 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = in[base + clamp(i + r + 1, len) * step];
                int sub = in[base + clamp(i - r, len) * step];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    /**
     * Fades the frost out over the last {@link #FADE_FRACTION} of the bar, on the same curve the tint
     * uses, so the two end together instead of the blurred copy cutting off in a straight line.
     */
    private void fadeBottom(int[] px, int w, int h) {
        int start = Math.round(h * (1f - FADE_FRACTION));
        for (int y = start; y < h; y++) {
            int a = fadeAlpha(y, h);
            if (a == 255) continue;
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int p = px[base + x];
                px[base + x] = ((p >>> 24) * a / 255) << 24 | (p & 0x00FFFFFF);
            }
        }
    }

    /**
     * The bar's alpha at row y, 0..255. A straight two-segment ramp (flat, then linear) has a kink where
     * the segments meet, and the eye reads that kink as a line across the bar. Smoothstep has no kink:
     * it leaves the flat top and reaches zero with the same slope at both ends.
     */
    private static int fadeAlpha(int y, int h) {
        float start = h * (1f - FADE_FRACTION);
        if (y <= start) return 255;
        float t = Math.min(1f, (y - start) / Math.max(1f, h - start));
        float f = 1f - t * t * (3f - 2f * t);   // 1 - smoothstep(0,1,t)
        return Math.round(255 * f);
    }

    /** getPixels gives straight alpha; blurring needs premultiplied or edges darken into halos. */
    private static void premultiply(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i], a = p >>> 24;
            if (a == 255) continue;
            px[i] = a << 24 | (((p >> 16) & 0xFF) * a / 255) << 16 | (((p >> 8) & 0xFF) * a / 255) << 8 | (p & 0xFF) * a / 255;
        }
    }

    private static void unpremultiply(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i], a = p >>> 24;
            if (a == 255) continue;
            if (a == 0) { px[i] = 0; continue; }
            px[i] = a << 24 | Math.min(255, ((p >> 16) & 0xFF) * 255 / a) << 16
                    | Math.min(255, ((p >> 8) & 0xFF) * 255 / a) << 8 | Math.min(255, (p & 0xFF) * 255 / a);
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float tint = Math.max(amount, under);
        if (tint <= 0f) return;
        dst.set(0, 0, getWidth(), getHeight());
        // Full strength: the frost is transparent where nothing is underneath, and it stands in for
        // the clipped-away tiles, so it must not fade (that let the sharp tiles show through).
        if (amount > 0f && frost != null) canvas.drawBitmap(frost, null, dst, bitmapPaint);

        // The frost's own bottom edge is a hard cut: it ends where the bar ends, mid-tile, and that
        // straight line across the artwork is the seam you see. It has to fade out with the tint, on
        // the same smoothstep curve, so the two don't disagree about where the bar stops.
        int solid = scaleAlpha(TINT, tint);
        if (fadeShader == null || fadeHeight != getHeight() || fadeColor != solid) {
            // Rebuilt only when the bar or the tint changes, not per frame.
            fadeHeight = getHeight();
            fadeColor = solid;
            int steps = 16;
            int[] colors = new int[steps + 1];
            float[] stops = new float[steps + 1];
            for (int i = 0; i <= steps; i++) {
                stops[i] = i / (float) steps;
                colors[i] = scaleAlpha(solid, fadeAlpha(Math.round(stops[i] * (fadeHeight - 1)), fadeHeight) / 255f);
            }
            fadeShader = new LinearGradient(0, 0, 0, fadeHeight, colors, stops, Shader.TileMode.CLAMP);
        }
        fillPaint.setShader(fadeShader);
        canvas.drawRect(dst, fillPaint);
        fillPaint.setShader(null);
    }

    private static int scaleAlpha(int color, float f) {
        return ((int) ((color >>> 24) * f) << 24) | (color & 0x00FFFFFF);
    }
}
