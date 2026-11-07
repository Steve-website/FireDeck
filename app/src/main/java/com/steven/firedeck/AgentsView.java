package com.steven.firedeck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.json.JSONObject;

/** Live agent telemetry: state, model, TPS, context, tokens, requests. */
public class AgentsView extends View {

    private static final int C_BG = 0xFF0B0D11;
    private static final int C_CARD = 0xFF141821;
    private static final int C_BORDER = 0xFF232A36;
    private static final int C_TRACK = 0xFF232A36;
    private static final int C_LABEL = 0xFF8B93A7;
    private static final int C_SUB = 0xFF9AA4B2;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_ACCENT = 0xFF4FC3F7;
    private static final int C_GREEN = 0xFF66BB6A;
    private static final int C_AMBER = 0xFFFFB74D;
    private static final int C_RED = 0xFFEF5350;
    private static final int C_GREY = 0xFF8B93A7;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private final RectF chip = new RectF();
    private final RectF bar = new RectF();
    private final Path spark = new Path();

    private boolean online = false;
    private String error = "";
    private String state = "idle";
    private String model = "";
    private double tps = 0;
    private double tpsAvg = 0;
    private int processing = 0;
    private int deferred = 0;
    private int busySlots = 0;
    private int slots = 0;
    private double ctxUsed = 0;
    private double ctxTotal = 0;
    private double ctxPct = 0;
    private double tokOut = 0;
    private double tokIn = 0;
    private double tokCached = 0;
    private Integer compactions = null;

    private static final int HIST = 60;
    private final double[] hist = new double[HIST];
    private int histIdx = 0;
    private int histCount = 0;

    public AgentsView(Context c) {
        super(c);
        setBackgroundColor(C_BG);
        labelPaint.setColor(C_LABEL);
        valuePaint.setColor(C_TEXT);
        valuePaint.setFakeBoldText(true);
        subPaint.setColor(C_SUB);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(C_BORDER);
        sparkPaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStrokeWidth(dp(2));
        sparkPaint.setStrokeCap(Paint.Cap.ROUND);
        barBg.setColor(C_TRACK);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setAgent(JSONObject o) {
        if (o == null) {
            return;
        }
        try {
            online = o.optBoolean("online", false);
            error = o.optString("error", "");
            state = o.optString("state", "idle");
            model = o.optString("model", "");
            tps = o.optDouble("tps", 0);
            tpsAvg = o.optDouble("tps_avg", 0);
            JSONObject req = o.optJSONObject("requests");
            if (req != null) {
                processing = req.optInt("processing", 0);
                deferred = req.optInt("deferred", 0);
                busySlots = req.optInt("busy_slots", 0);
                slots = req.optInt("slots", 0);
            }
            JSONObject ctx = o.optJSONObject("context");
            if (ctx != null) {
                ctxUsed = ctx.optDouble("used", 0);
                ctxTotal = ctx.optDouble("total", 0);
                ctxPct = ctx.optDouble("pct", 0);
            }
            JSONObject tok = o.optJSONObject("tokens");
            if (tok != null) {
                tokOut = tok.optDouble("generated", 0);
                tokIn = tok.optDouble("prompt", 0);
                tokCached = tok.optDouble("cached", 0);
            }
            compactions = o.isNull("compactions") ? null : o.optInt("compactions", 0);
            hist[histIdx] = online ? tps : 0;
            histIdx = (histIdx + 1) % HIST;
            if (histCount < HIST) {
                histCount++;
            }
        } catch (Exception ignored) {
        }
        invalidate();
    }

    private static String kfmt(double v) {
        if (v >= 1e9) {
            return String.format("%.1fB", v / 1e9);
        }
        if (v >= 1e6) {
            return String.format("%.1fM", v / 1e6);
        }
        if (v >= 1e3) {
            return String.format("%.1fk", v / 1e3);
        }
        return String.valueOf(Math.round(v));
    }

    private int stateColor() {
        if (!online) {
            return C_RED;
        }
        if ("streaming".equals(state)) {
            return C_GREEN;
        }
        if ("prompt".equals(state) || "working".equals(state)) {
            return C_AMBER;
        }
        return C_GREY;
    }

    private String stateText() {
        if (!online) {
            return "OFFLINE";
        }
        if ("streaming".equals(state)) {
            return "STREAMING";
        }
        if ("prompt".equals(state)) {
            return "PROMPT";
        }
        if ("working".equals(state)) {
            return "WORKING";
        }
        return "IDLE";
    }

    private void drawCard(Canvas c, float l, float t, float r, float b) {
        card.set(l, t, r, b);
        fill.setColor(C_CARD);
        c.drawRoundRect(card, dp(14), dp(14), fill);
        border.setStrokeWidth(dp(1.2f));
        c.drawRoundRect(card, dp(14), dp(14), border);
    }

    private void fit(Paint p, String s, float maxW) {
        while (p.measureText(s) > maxW && p.getTextSize() > dp(9)) {
            p.setTextSize(p.getTextSize() - dp(1));
        }
    }

    private void drawSpark(Canvas c, float l, float t, float r, float b, int color) {
        if (histCount < 2) {
            return;
        }
        double max = 1.0;
        for (int i = 0; i < histCount; i++) {
            max = Math.max(max, hist[(histIdx - histCount + i + HIST) % HIST]);
        }
        spark.reset();
        for (int i = 0; i < histCount; i++) {
            double v = hist[(histIdx - histCount + i + HIST) % HIST];
            float x = l + (r - l) * (histCount == 1 ? 0 : (float) i / (histCount - 1));
            float y = b - (b - t) * (float) (v / max);
            if (i == 0) {
                spark.moveTo(x, y);
            } else {
                spark.lineTo(x, y);
            }
        }
        sparkPaint.setColor(color);
        sparkPaint.setAlpha(220);
        c.drawPath(spark, sparkPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float pad = dp(12);
        float gap = dp(12);

        // header
        labelPaint.setColor(C_ACCENT);
        labelPaint.setTextSize(dp(15));
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("AGENT", dp(18), dp(28), labelPaint);
        labelPaint.setFakeBoldText(false);

        // status chip
        int sc = stateColor();
        String st = stateText();
        labelPaint.setTextSize(dp(12));
        float tw = labelPaint.measureText(st);
        chip.set(dp(92), dp(12), dp(92) + tw + dp(22), dp(36));
        chipPaint.setColor((sc & 0x00FFFFFF) | 0x33000000);
        canvas.drawRoundRect(chip, dp(12), dp(12), chipPaint);
        labelPaint.setColor(sc);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(st, chip.left + dp(11), chip.top + dp(17), labelPaint);

        // model right aligned
        labelPaint.setTextSize(dp(12));
        labelPaint.setColor(C_LABEL);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        String m = model.length() > 46 ? model.substring(model.length() - 46) : model;
        fit(labelPaint, m, w - dp(260));
        canvas.drawText(m, w - dp(18), dp(28), labelPaint);

        // main TPS card
        float top = dp(50);
        float mainH = (h - top - pad - gap) * 0.52f;
        drawCard(canvas, pad, top, w - pad, top + mainH);

        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(11));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("THROUGHPUT", pad + dp(20), top + dp(24), labelPaint);

        valuePaint.setColor(online ? C_TEXT : C_GREY);
        valuePaint.setTextSize(Math.min(mainH * 0.42f, w * 0.16f));
        valuePaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.format("%.1f", tps), pad + dp(20), top + mainH * 0.62f, valuePaint);
        float tpsW = valuePaint.measureText(String.format("%.1f", tps));
        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(18));
        canvas.drawText("tok/s", pad + dp(28) + tpsW, top + mainH * 0.62f, subPaint);

        subPaint.setTextSize(dp(12));
        canvas.drawText("avg " + String.format("%.1f", tpsAvg) + " tok/s  ·  "
                + (processing > 0 ? "1 request active" : "no active request"),
                pad + dp(20), top + mainH * 0.80f, subPaint);

        // sparkline on the right half
        drawSpark(canvas,
                w * 0.48f, top + dp(30),
                w - pad - dp(20), top + mainH - dp(18),
                C_ACCENT);

        // bottom row
        float by = top + mainH + gap;
        float bh = h - by - pad;

        // context card (50%)
        float cw1 = (w - pad * 2 - gap * 2) * 0.50f;
        drawCard(canvas, pad, by, pad + cw1, by + bh);
        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(11));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("CONTEXT", pad + dp(18), by + dp(24), labelPaint);

        valuePaint.setColor(C_TEXT);
        valuePaint.setTextSize(Math.min(bh * 0.30f, cw1 * 0.20f));
        canvas.drawText(ctxPct > 0 ? Math.round(ctxPct) + "%" : "--",
                pad + dp(18), by + bh * 0.52f, valuePaint);

        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(12));
        canvas.drawText(kfmt(ctxUsed) + " / " + kfmt(ctxTotal),
                pad + dp(18), by + bh * 0.68f, subPaint);

        float barTop = by + bh - dp(22);
        bar.set(pad + dp(18), barTop, pad + cw1 - dp(18), barTop + dp(10));
        barBg.setColor(C_TRACK);
        canvas.drawRoundRect(bar, dp(5), dp(5), barBg);
        int bc = ctxPct >= 85 ? C_RED : (ctxPct >= 60 ? C_AMBER : C_GREEN);
        bar.set(pad + dp(18), barTop,
                pad + dp(18) + (cw1 - dp(36)) * (float) Math.min(100, Math.max(0, ctxPct)) / 100f,
                barTop + dp(10));
        barFg.setColor(bc);
        canvas.drawRoundRect(bar, dp(5), dp(5), barFg);

        // tokens card (25%)
        float x2 = pad + cw1 + gap;
        float cw2 = (w - pad * 2 - gap * 2) * 0.25f;
        drawCard(canvas, x2, by, x2 + cw2, by + bh);
        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(11));
        canvas.drawText("TOKENS", x2 + dp(16), by + dp(24), labelPaint);
        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(11));
        canvas.drawText("out " + kfmt(tokOut), x2 + dp(16), by + dp(50), subPaint);
        canvas.drawText("in " + kfmt(tokIn), x2 + dp(16), by + dp(68), subPaint);
        canvas.drawText("cached " + kfmt(tokCached), x2 + dp(16), by + dp(86), subPaint);

        // requests card (25%)
        float x3 = x2 + cw2 + gap;
        drawCard(canvas, x3, by, w - pad, by + bh);
        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(11));
        canvas.drawText("REQUESTS", x3 + dp(16), by + dp(24), labelPaint);
        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(11));
        canvas.drawText("processing " + processing, x3 + dp(16), by + dp(50), subPaint);
        canvas.drawText("busy slots " + busySlots + "/" + slots, x3 + dp(16), by + dp(68), subPaint);
        canvas.drawText("queue " + deferred, x3 + dp(16), by + dp(86), subPaint);
        canvas.drawText("compactions " + (compactions == null ? "--" : compactions),
                x3 + dp(16), by + dp(104), subPaint);

        if (!online && error.length() > 0) {
            subPaint.setColor(C_RED);
            subPaint.setTextSize(dp(11));
            String e = error.length() > 80 ? error.substring(0, 80) : error;
            canvas.drawText(e, pad + dp(20), by - dp(6), subPaint);
        }
    }
}
