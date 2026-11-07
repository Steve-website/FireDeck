package com.steven.firedeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Draws the FireDeck button grid (labels, text icons, PNG icons, hold). */
public class DeckView extends View {

    public interface Listener {
        void onPress(int index);
        void onHold(int index);
    }

    public interface ImageProvider {
        Bitmap get(String url);
    }

    private static class Btn {
        String label = "";
        String icon = "";
        String image = "";
        int color = 0xFF37474F;
    }

    private static final long HOLD_MS = 550;

    private final ArrayList<Btn> buttons = new ArrayList<Btn>();
    private int cols = 4;
    private int rows = 3;
    private int pressed = -1;
    private boolean holdFired = false;
    private Listener listener;
    private ImageProvider images;

    private final Handler handler = new Handler();
    private Runnable holdRunnable;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint();
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    public DeckView(Context c) {
        super(c);
        setBackgroundColor(0xFF0F1115);
        overlay.setColor(0x40FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        iconPaint.setTextAlign(Paint.Align.CENTER);
        iconPaint.setFakeBoldText(true);
        bmpPaint.setFilterBitmap(true);
        bmpPaint.setDither(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setImageProvider(ImageProvider p) {
        images = p;
    }

    public void setLayout(int c, int r, JSONArray arr) {
        cols = Math.max(1, c);
        rows = Math.max(1, r);
        buttons.clear();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    Btn b = new Btn();
                    b.label = o.optString("label", "");
                    b.icon = o.optString("icon", "");
                    b.image = o.optString("image", "");
                    try {
                        b.color = Color.parseColor(o.optString("color", "#37474F"));
                    } catch (Exception e) {
                        b.color = 0xFF37474F;
                    }
                    buttons.add(b);
                } catch (Exception ignored) {
                }
            }
        }
        while (buttons.size() < cols * rows) {
            buttons.add(new Btn());
        }
        pressed = -1;
        holdFired = false;
        cancelHold();
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void cellRect(int index, RectF out) {
        float gap = dp(8);
        float cw = (getWidth() - gap * (cols + 1)) / cols;
        float ch = (getHeight() - gap * (rows + 1)) / rows;
        int cx = index % cols;
        int cy = index / cols;
        float left = gap + cx * (cw + gap);
        float top = gap + cy * (ch + gap);
        out.set(left, top, left + cw, top + ch);
    }

    private int indexAt(float x, float y) {
        float gap = dp(8);
        float cw = (getWidth() - gap * (cols + 1)) / cols;
        float ch = (getHeight() - gap * (rows + 1)) / rows;
        if (cw <= 0 || ch <= 0) {
            return -1;
        }
        int cx = (int) ((x - gap) / (cw + gap));
        int cy = (int) ((y - gap) / (ch + gap));
        if (cx < 0 || cx >= cols || cy < 0 || cy >= rows) {
            return -1;
        }
        int idx = cy * cols + cx;
        RectF r = new RectF();
        cellRect(idx, r);
        return r.contains(x, y) ? idx : -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < cols * rows; i++) {
            Btn b = i < buttons.size() ? buttons.get(i) : new Btn();
            cellRect(i, rect);

            fill.setColor(b.color);
            canvas.drawRoundRect(rect, dp(12), dp(12), fill);
            if (i == pressed) {
                canvas.drawRoundRect(rect, dp(12), dp(12), overlay);
            }

            int lum = (int) (0.299 * Color.red(b.color) + 0.587 * Color.green(b.color) + 0.114 * Color.blue(b.color));
            int fg = lum > 150 ? 0xFF111111 : 0xFFFFFFFF;
            text.setColor(fg);
            iconPaint.setColor(fg);

            float cw = rect.width();
            float ch = rect.height();
            float pad = dp(8);

            Bitmap img = (b.image != null && b.image.length() > 0 && images != null)
                    ? images.get(b.image) : null;

            String label = b.label == null ? "" : b.label;
            boolean hasImage = img != null && !img.isRecycled();
            boolean hasTextIcon = false;

            if (hasImage) {
                // PNG fills the whole button: center-crop, rounded corners.
                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(rect, dp(12), dp(12), Path.Direction.CW);
                canvas.clipPath(clipPath);

                float sc = Math.max(cw / img.getWidth(), ch / img.getHeight());
                float dw = img.getWidth() * sc;
                float dh = img.getHeight() * sc;
                dstRect.set(rect.centerX() - dw / 2, rect.centerY() - dh / 2,
                        rect.centerX() + dw / 2, rect.centerY() + dh / 2);
                srcRect.set(0, 0, img.getWidth(), img.getHeight());
                canvas.drawBitmap(img, srcRect, dstRect, bmpPaint);

                if (label.length() > 0) {
                    scrimPaint.setColor(0x99000000);
                    canvas.drawRect(rect.left, rect.bottom - ch * 0.30f, rect.right, rect.bottom, scrimPaint);
                }
                canvas.restore();
            } else if (b.icon != null && b.icon.length() > 0) {
                float is = Math.min(ch * 0.28f, cw * 0.28f);
                iconPaint.setTextSize(is);
                canvas.drawText(b.icon, rect.centerX(), rect.top + ch * 0.36f + is * 0.35f, iconPaint);
                hasTextIcon = true;
            }

            float ls = Math.min(ch * 0.20f, cw * 0.18f);
            if (ls < dp(11)) {
                ls = dp(11);
            }
            text.setTextSize(ls);
            while (text.measureText(label) > cw - pad * 2 && ls > dp(9)) {
                ls -= dp(1);
                text.setTextSize(ls);
            }
            float ty;
            if (hasImage) {
                text.setColor(0xFFFFFFFF);
                ty = rect.bottom - dp(10);
            } else if (hasTextIcon) {
                ty = rect.top + ch * 0.82f;
            } else {
                ty = rect.centerY() + ls * 0.35f;
            }
            canvas.drawText(label, rect.centerX(), ty, text);
        }
    }

    private void cancelHold() {
        if (holdRunnable != null) {
            handler.removeCallbacks(holdRunnable);
            holdRunnable = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = indexAt(e.getX(), e.getY());
                holdFired = false;
                cancelHold();
                if (pressed >= 0) {
                    holdRunnable = new Runnable() {
                        public void run() {
                            holdFired = true;
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            if (listener != null && pressed >= 0) {
                                listener.onHold(pressed);
                            }
                        }
                    };
                    handler.postDelayed(holdRunnable, HOLD_MS);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                int p = indexAt(e.getX(), e.getY());
                if (p != pressed) {
                    pressed = p;
                    cancelHold();
                    holdFired = false;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                cancelHold();
                int up = indexAt(e.getX(), e.getY());
                if (!holdFired && up >= 0 && up == pressed && listener != null) {
                    listener.onPress(up);
                }
                pressed = -1;
                holdFired = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelHold();
                pressed = -1;
                holdFired = false;
                invalidate();
                return true;
        }
        return true;
    }
}
