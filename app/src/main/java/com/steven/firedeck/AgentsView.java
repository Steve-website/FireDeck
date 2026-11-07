package com.steven.firedeck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Live multi-agent telemetry: one card per configured LLM endpoint. */
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

    private static class Agent {
        String name = "";
        String model = "";
        String state = "idle";
        String error = "";
        boolean online = false;
        double tps, tpsAvg;
        double ctxUsed, ctxTotal, ctxPct;
        double tokOut, tokIn, tokCached;
        int processing, deferred, busySlots, slots;
        Integer compactions = null;
        double[] hist = new double[60];
        int histIdx = 0;
        int histCount = 0;
    }

    private final ArrayList<Agent> agents = new ArrayList<Agent>();
    private final java.util.HashMap<String, double[]> histData = new java.util.HashMap<String, double[]>();
    private final java.util.HashMap<String, int[]> histMeta = new java.util.HashMap<String, int[]>();

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

    public void setAgents(JSONArray arr) {
        agents.clear();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    Agent a = new Agent();
                    a.name = o.optString("name", "Agent");
                    a.model = o.optString("model", "");
                    a.state = o.optString("state", "idle");
                    a.error = o.optString("error", "");
                    a.online = o.optBoolean("online", false);
                    a.tps = o.optDouble("tps", 0);
                    a.tpsAvg = o.optDouble("tps_avg", 0);
                    JSONObject req = o.optJSONObject("requests");
                    if (req != null) {
                        a.processing = req.optInt("processing", 0);
                        a.deferred = req.optInt("deferred", 0);
                        a.busySlots = req.optInt("busy_slots", 0);
                        a.slots = req.optInt("slots", 0);
                    }
                    JSONObject ctx = o.optJSONObject("context");
                    if (ctx != null) {
                        a.ctxUsed = ctx.optDouble("used", 0);
                        a.ctxTotal = ctx.optDouble("total", 0);
                        a.ctxPct = ctx.optDouble("pct", 0);
                    }
                    JSONObject tok = o.optJSONObject("tokens");
                    if (tok != null) {
                        a.tokOut = tok.optDouble("generated", 0);
                        a.tokIn = tok.optDouble("prompt", 0);
                        a.tokCached = tok.optDouble("cached", 0);
                    }
                    a.compactions = o.isNull("compactions") ? null : o.optInt("compactions", 0);
                    double[] hdata = histData.get(a.name);
                    int[] hmeta = histMeta.get(a.name);
                    if (hdata == null) {
                        hdata = new double[60];
                        hmeta = new int[]{0, 0};
                        histData.put(a.name, hdata);
                        histMeta.put(a.name, hmeta);
                    }
                    hdata[hmeta[0]] = a.online ? a.tps : 0;
                    hmeta[0] = (hmeta[0] + 1) % 60;
                    if (hmeta[1] < 60) {
                        hmeta[1]++;
                    }
                    a.hist = hdata;
                    a.histIdx = hmeta[0];
                    a.histCount = hmeta[1];
                    agents.add(a);
                } catch (Exception ignored) {
                }
            }
        }
        invalidate();
    }

    /** Keeps history across refreshes by name. */
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

    private int stateColor(Agent a) {
        if (!a.online) {
            return C_RED;
        }
        if ("streaming".equals(a.state)) {
            return C_GREEN;
        }
        if ("prompt".equals(a.state) || "working".equals(a.state)) {
            return C_AMBER;
        }
        return C_GREY;
    }

    private String stateText(Agent a) {
        if (!a.online) {
            return "OFFLINE";
        }
        if ("streaming".equals(a.state)) {
            return "STREAMING";
        }
        if ("prompt".equals(a.state)) {
            return "PROMPT";
        }
        if ("working".equals(a.state)) {
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

    private void drawSpark(Canvas c, Agent a, float l, float t, float r, float b) {
        if (a.histCount < 2) {
            return;
        }
        double max = 1.0;
        for (int i = 0; i < a.histCount; i++) {
            max = Math.max(max, a.hist[(a.histIdx - a.histCount + i + a.hist.length) % a.hist.length]);
        }
        spark.reset();
        for (int i = 0; i < a.histCount; i++) {
            double v = a.hist[(a.histIdx - a.histCount + i + a.hist.length) % a.hist.length];
            float x = l + (r - l) * (a.histCount == 1 ? 0 : (float) i / (a.histCount - 1));
            float y = b - (b - t) * (float) (v / max);
            if (i == 0) {
                spark.moveTo(x, y);
            } else {
                spark.lineTo(x, y);
            }
        }
        sparkPaint.setColor(C_ACCENT);
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

        int onlineCount = 0;
        int activeCount = 0;
        double totalTps = 0;
        for (int i = 0; i < agents.size(); i++) {
            Agent a = agents.get(i);
            if (a.online) {
                onlineCount++;
            }
            if (a.online && !"idle".equals(a.state)) {
                activeCount++;
            }
            totalTps += a.tps;
        }

        labelPaint.setColor(C_ACCENT);
        labelPaint.setTextSize(dp(15));
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("AGENTS", dp(18), dp(28), labelPaint);
        labelPaint.setFakeBoldText(false);

        labelPaint.setTextSize(dp(11));
        labelPaint.setColor(C_LABEL);
        String summary = agents.size() == 0
                ? "no agents configured - add them on the config page"
                : (onlineCount + " online \u00B7 " + activeCount + " active \u00B7 "
                   + String.format("%.0f", totalTps) + " tok/s");
        fit(labelPaint, summary, w - dp(320));
        canvas.drawText(summary, dp(96), dp(28), labelPaint);

        if (agents.isEmpty()) {
            return;
        }

        int n = Math.min(agents.size(), 4);
        int cols = n == 1 ? 1 : 2;
        int rows = (n + cols - 1) / cols;
        float top = dp(48);
        float cw = (w - pad * 2 - gap * (cols - 1)) / cols;
        float ch = (h - top - pad - gap * (rows - 1)) / rows;

        for (int i = 0; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            float l = pad + col * (cw + gap);
            float t = top + row * (ch + gap);
            drawAgent(canvas, agents.get(i), l, t, l + cw, t + ch);
        }

        if (agents.size() > 4) {
            labelPaint.setColor(C_LABEL);
            labelPaint.setTextSize(dp(10));
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("+" + (agents.size() - 4) + " more", w - pad, h - dp(2), labelPaint);
        }
    }

    private void drawAgent(Canvas c, Agent a, float l, float t, float r, float b) {
        drawCard(c, l, t, r, b);
        float w = r - l;
        float h = b - t;
        float pad = dp(16);
        boolean big = h > dp(420);

        // name
        labelPaint.setColor(C_TEXT);
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextSize(Math.min(dp(16), h * 0.062f));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        fit(labelPaint, a.name, w - dp(150));
        c.drawText(a.name, l + pad, t + labelPaint.getTextSize() + dp(8), labelPaint);
        labelPaint.setFakeBoldText(false);

        // status chip (top right)
        int sc = stateColor(a);
        String st = stateText(a);
        labelPaint.setTextSize(dp(11));
        float tw = labelPaint.measureText(st);
        chip.set(r - pad - tw - dp(22), t + dp(12), r - pad, t + dp(36));
        chipPaint.setColor((sc & 0x00FFFFFF) | 0x33000000);
        c.drawRoundRect(chip, dp(12), dp(12), chipPaint);
        labelPaint.setColor(sc);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(st, chip.left + dp(11), chip.top + dp(17), labelPaint);

        // model
        subPaint.setColor(C_LABEL);
        subPaint.setTextSize(dp(11));
        String model = a.model;
        if (model.length() > 42) {
            model = model.substring(model.length() - 42);
        }
        fit(subPaint, model, w - pad * 2);
        c.drawText(model, l + pad, t + dp(46), subPaint);

        // big TPS
        valuePaint.setColor(a.online ? C_TEXT : C_GREY);
        float tpsSize = Math.min(h * 0.26f, w * 0.22f);
        if (!big) {
            tpsSize = Math.min(h * 0.30f, w * 0.20f);
        }
        valuePaint.setTextSize(tpsSize);
        valuePaint.setTextAlign(Paint.Align.LEFT);
        String tpsStr = String.format("%.1f", a.tps);
        c.drawText(tpsStr, l + pad, t + h * (big ? 0.40f : 0.44f), valuePaint);
        float tpsW = valuePaint.measureText(tpsStr);
        subPaint.setColor(C_SUB);
        subPaint.setTextSize(Math.max(dp(12), tpsSize * 0.22f));
        c.drawText("tok/s", l + pad + tpsW + dp(8), t + h * (big ? 0.40f : 0.44f), subPaint);

        // average
        subPaint.setTextSize(dp(11));
        subPaint.setColor(C_SUB);
        c.drawText("avg " + String.format("%.1f", a.tpsAvg) + " tok/s",
                l + pad, t + h * (big ? 0.48f : 0.54f), subPaint);

        // sparkline on the right for big cards
        if (big) {
            drawSpark(c, a, l + w * 0.52f, t + h * 0.18f, r - pad, t + h * 0.50f);
        }

        // context
        float ctxY = t + h * (big ? 0.62f : 0.66f);
        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(10));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("CONTEXT", l + pad, ctxY, labelPaint);
        subPaint.setColor(C_TEXT);
        subPaint.setTextSize(dp(11));
        subPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText((a.ctxPct > 0 ? Math.round(a.ctxPct) + "% \u00B7 " : "")
                        + kfmt(a.ctxUsed) + " / " + kfmt(a.ctxTotal),
                r - pad, ctxY, subPaint);

        float barY = ctxY + dp(8);
        bar.set(l + pad, barY, r - pad, barY + dp(8));
        barBg.setColor(C_TRACK);
        c.drawRoundRect(bar, dp(4), dp(4), barBg);
        int bc = a.ctxPct >= 85 ? C_RED : (a.ctxPct >= 60 ? C_AMBER : C_GREEN);
        bar.set(l + pad, barY, l + pad + (w - pad * 2) * (float) Math.min(100, Math.max(0, a.ctxPct)) / 100f,
                barY + dp(8));
        barFg.setColor(bc);
        c.drawRoundRect(bar, dp(4), dp(4), barFg);

        // tokens
        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(10));
        subPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("in " + kfmt(a.tokIn) + " \u00B7 out " + kfmt(a.tokOut)
                + " \u00B7 cached " + kfmt(a.tokCached),
                l + pad, t + h * (big ? 0.82f : 0.86f), subPaint);

        // requests + compactions
        c.drawText(a.processing + " req \u00B7 " + a.busySlots + "/" + a.slots
                        + " slots \u00B7 q " + a.deferred
                        + " \u00B7 comp " + (a.compactions == null ? "--" : a.compactions),
                l + pad, t + h * (big ? 0.90f : 0.95f), subPaint);

        if (!a.online && a.error.length() > 0) {
            subPaint.setColor(C_RED);
            subPaint.setTextSize(dp(9));
            String e = a.error.length() > 70 ? a.error.substring(0, 70) : a.error;
            c.drawText(e, l + pad, b - dp(6), subPaint);
        }
    }
}
