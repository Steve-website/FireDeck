package com.steven.firedeck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
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

    private static class Gpu {
        String name = "";
        double temp = -1, util = -1, vramUsed = -1, vramTotal = -1, power = -1;
    }

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
        double cpuPct = -1, ramPct = -1, ramUsed, ramTotal, diskPct = -1, diskFree;
        int gpuCount = 0;
        String gpuName = "";
        double gpuTemp = -1, gpuUtil = -1, vramUsed = -1, vramTotal = -1, gpuPower = -1;
        final ArrayList<Gpu> gpus = new ArrayList<Gpu>();
        double[] hist = new double[60];
        int histIdx = 0;
        int histCount = 0;
    }

    private final ArrayList<Agent> agents = new ArrayList<Agent>();
    private final java.util.HashMap<String, double[]> histData = new java.util.HashMap<String, double[]>();
    private final java.util.HashMap<String, int[]> histMeta = new java.util.HashMap<String, int[]>();

    private int expanded = -1;
    private final RectF[] slotRects = new RectF[]{
            new RectF(), new RectF(), new RectF(), new RectF()};
    private int slotCount = 0;

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
                    JSONObject sys = o.optJSONObject("sys");
                    if (sys != null) {
                        a.cpuPct = sys.optDouble("cpu_pct", -1);
                        a.ramPct = sys.optDouble("ram_pct", -1);
                        a.ramUsed = sys.optDouble("ram_used", 0);
                        a.ramTotal = sys.optDouble("ram_total", 0);
                        a.diskPct = sys.optDouble("disk_pct", -1);
                        a.diskFree = sys.optDouble("disk_free", 0);
                        a.gpuCount = sys.optInt("gpu_count", 0);
                        JSONObject g = sys.optJSONObject("gpu");
                        if (g != null) {
                            a.gpuName = g.optString("name", "");
                            a.gpuTemp = g.isNull("temp") ? -1 : g.optDouble("temp", -1);
                            a.gpuUtil = g.isNull("util") ? -1 : g.optDouble("util", -1);
                            a.vramUsed = g.isNull("vram_used") ? -1 : g.optDouble("vram_used", -1);
                            a.vramTotal = g.isNull("vram_total") ? -1 : g.optDouble("vram_total", -1);
                            a.gpuPower = g.isNull("power") ? -1 : g.optDouble("power", -1);
                        }
                        JSONArray gpus = sys.optJSONArray("gpus");
                        if (gpus != null) {
                            for (int k = 0; k < gpus.length(); k++) {
                                JSONObject go = gpus.optJSONObject(k);
                                if (go == null) {
                                    continue;
                                }
                                Gpu gg = new Gpu();
                                gg.name = go.optString("name", "");
                                gg.temp = go.isNull("temp") ? -1 : go.optDouble("temp", -1);
                                gg.util = go.isNull("util") ? -1 : go.optDouble("util", -1);
                                gg.vramUsed = go.isNull("vram_used") ? -1 : go.optDouble("vram_used", -1);
                                gg.vramTotal = go.isNull("vram_total") ? -1 : go.optDouble("vram_total", -1);
                                gg.power = go.isNull("power") ? -1 : go.optDouble("power", -1);
                                a.gpus.add(gg);
                            }
                        }
                    }
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

    private void drawSpark(Canvas c, Agent a, float l, float t, float r, float b) {        if (a.histCount < 2) {
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

    private String shortGpu(String n) {
        if (n == null) {
            return "";
        }
        String s = n.replace("NVIDIA GeForce ", "").replace("AMD ", "");
        return s.length() > 26 ? s.substring(0, 26) : s;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float pad = dp(12);
        float gap = dp(12);

        if (expanded >= 0 && expanded < agents.size()) {
            drawDetail(canvas, agents.get(expanded));
            return;
        }

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
            slotRects[i].set(l, t, l + cw, t + ch);
            drawAgent(canvas, agents.get(i), l, t, l + cw, t + ch);
        }
        slotCount = n;

        if (agents.size() > 4) {
            labelPaint.setColor(C_LABEL);
            labelPaint.setTextSize(dp(10));
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("+" + (agents.size() - 4) + " more", w - pad, h - dp(2), labelPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) {
            return true;
        }
        if (expanded >= 0) {
            expanded = -1;
            invalidate();
            return true;
        }
        for (int i = 0; i < slotCount; i++) {
            if (slotRects[i].contains(e.getX(), e.getY())) {
                expanded = i;
                invalidate();
                break;
            }
        }
        return true;
    }

    private void drawDetail(Canvas c, Agent a) {
        float w = getWidth();
        float h = getHeight();
        float pad = dp(14);
        float gap = dp(10);

        labelPaint.setColor(C_ACCENT);
        labelPaint.setTextSize(dp(11));
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("AGENT DETAIL", pad, dp(20), labelPaint);
        labelPaint.setFakeBoldText(false);

        labelPaint.setColor(C_TEXT);
        labelPaint.setTextSize(dp(22));
        labelPaint.setFakeBoldText(true);
        fit(labelPaint, a.name, w - dp(340));
        c.drawText(a.name, pad, dp(48), labelPaint);
        labelPaint.setFakeBoldText(false);

        subPaint.setColor(C_LABEL);
        subPaint.setTextSize(dp(11));
        fit(subPaint, a.model, w - dp(340));
        c.drawText(a.model, pad, dp(66), subPaint);

        int sc = stateColor(a);
        String st = stateText(a);
        labelPaint.setTextSize(dp(11));
        float tw = labelPaint.measureText(st);
        chip.set(dp(150), dp(6), dp(150) + tw + dp(22), dp(30));
        chipPaint.setColor((sc & 0x00FFFFFF) | 0x33000000);
        c.drawRoundRect(chip, dp(12), dp(12), chipPaint);
        labelPaint.setColor(sc);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(st, chip.left + dp(11), chip.top + dp(17), labelPaint);

        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(10));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("tap to close", w - pad, dp(54), labelPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);

        int n = a.gpus.size();
        float top = dp(82);
        float footer = dp(86);
        float area = h - top - footer - pad;
        float rowH = n > 0 ? Math.min(dp(150), (area - gap * (n - 1)) / n) : dp(110);

        if (n == 0) {
            drawCard(c, pad, top, w - pad, top + dp(90));
            subPaint.setColor(C_LABEL);
            subPaint.setTextSize(dp(12));
            c.drawText("no GPU telemetry from this server", pad + dp(16), top + dp(40), subPaint);
        } else {
            for (int i = 0; i < n; i++) {
                float rt = top + i * (rowH + gap);
                drawGpuRow(c, a.gpus.get(i), i, pad, rt, w - pad, rt + rowH);
            }
        }

        float fy = h - pad - dp(54);
        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(11));
        subPaint.setTextAlign(Paint.Align.LEFT);
        String sysLine = "CPU " + Math.round(a.cpuPct) + "% \u00B7 RAM " + Math.round(a.ramPct)
                + "% (" + String.format("%.1f", a.ramUsed) + "/" + String.format("%.1f", a.ramTotal)
                + " GB) \u00B7 DISK " + Math.round(a.diskPct) + "%";
        fit(subPaint, sysLine, w - pad * 2);
        c.drawText(sysLine, pad, fy, subPaint);

        String sessLine = "avg " + String.format("%.1f", a.tpsAvg) + " tok/s \u00B7 in " + kfmt(a.tokIn)
                + " \u00B7 out " + kfmt(a.tokOut) + " \u00B7 cached " + kfmt(a.tokCached)
                + " \u00B7 ctx " + kfmt(a.ctxUsed) + "/" + kfmt(a.ctxTotal);
        fit(subPaint, sessLine, w - pad * 2);
        c.drawText(sessLine, pad, fy + dp(18), subPaint);

        String reqLine = a.processing + " req \u00B7 " + a.busySlots + "/" + a.slots
                + " slots \u00B7 q " + a.deferred + " \u00B7 comp "
                + (a.compactions == null ? "--" : a.compactions);
        c.drawText(reqLine, pad, fy + dp(36), subPaint);
    }

    private void drawGpuRow(Canvas c, Gpu g, int index, float l, float t, float r, float b) {
        drawCard(c, l, t, r, b);
        float pad = dp(16);
        float h = b - t;

        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(10));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("GPU " + index, l + pad, t + dp(20), labelPaint);

        labelPaint.setColor(C_TEXT);
        labelPaint.setTextSize(dp(14));
        labelPaint.setFakeBoldText(true);
        fit(labelPaint, shortGpu(g.name), r - l - dp(260));
        c.drawText(shortGpu(g.name), l + pad, t + dp(42), labelPaint);
        labelPaint.setFakeBoldText(false);

        String temp = g.temp >= 0 ? Math.round(g.temp) + "\u00B0C" : "--";
        int tc = g.temp >= 85 ? C_RED : (g.temp >= 70 ? C_AMBER : C_GREEN);
        valuePaint.setColor(tc);
        valuePaint.setTextSize(dp(20));
        valuePaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(temp, r - pad, t + dp(36), valuePaint);

        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(10));
        subPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText((g.util >= 0 ? Math.round(g.util) + "%" : "--")
                + " \u00B7 " + (g.power >= 0 ? Math.round(g.power) + "W" : "--"),
                r - pad, t + dp(52), subPaint);
        subPaint.setTextAlign(Paint.Align.LEFT);

        float vUsed = g.vramUsed >= 0 ? (float) g.vramUsed : 0;
        float vTotal = g.vramTotal > 0 ? (float) g.vramTotal : 0;
        float barY = t + h - dp(22);
        bar.set(l + pad, barY, r - pad, barY + dp(8));
        barBg.setColor(C_TRACK);
        c.drawRoundRect(bar, dp(4), dp(4), barBg);
        if (vTotal > 0) {
            float p = Math.min(100f, 100f * vUsed / vTotal);
            int bc = p >= 90 ? C_RED : (p >= 70 ? C_AMBER : C_GREEN);
            bar.set(l + pad, barY, l + pad + (r - l - pad * 2) * p / 100f, barY + dp(8));
            barFg.setColor(bc);
            c.drawRoundRect(bar, dp(4), dp(4), barFg);
        }

        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(10));
        subPaint.setTextAlign(Paint.Align.LEFT);
        String vram = vTotal > 0
                ? String.format("%.1f / %.0f GB", vUsed, vTotal)
                : "VRAM n/a";
        c.drawText(vram, l + pad, barY - dp(6), subPaint);
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
        c.drawText(tpsStr, l + pad, t + h * (big ? 0.34f : 0.38f), valuePaint);
        float tpsW = valuePaint.measureText(tpsStr);
        subPaint.setColor(C_SUB);
        subPaint.setTextSize(Math.max(dp(12), tpsSize * 0.22f));
        c.drawText("tok/s", l + pad + tpsW + dp(8), t + h * (big ? 0.34f : 0.38f), subPaint);

        // average
        subPaint.setTextSize(dp(11));
        subPaint.setColor(C_SUB);
        c.drawText("avg " + String.format("%.1f", a.tpsAvg) + " tok/s",
                l + pad, t + h * (big ? 0.42f : 0.47f), subPaint);

        // sparkline on the right for big cards
        if (big) {
            drawSpark(c, a, l + w * 0.52f, t + h * 0.15f, r - pad, t + h * 0.44f);
        }

        // context
        float ctxY = t + h * (big ? 0.52f : 0.55f);
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
                l + pad, t + h * (big ? 0.68f : 0.72f), subPaint);

        // hardware: GPU temp / VRAM / utilization
        if (a.gpuCount > 0) {
            String gpuLine = "GPU " + shortGpu(a.gpuName);
            if (a.gpuTemp >= 0) {
                gpuLine += " \u00B7 " + Math.round(a.gpuTemp) + "\u00B0C";
            }
            if (a.vramUsed >= 0 && a.vramTotal > 0) {
                gpuLine += " \u00B7 " + String.format("%.1f", a.vramUsed)
                        + "/" + String.format("%.0f", a.vramTotal) + " GB";
            }
            if (a.gpuUtil >= 0) {
                gpuLine += " \u00B7 " + Math.round(a.gpuUtil) + "%";
            }
            if (a.gpuPower >= 0) {
                gpuLine += " \u00B7 " + Math.round(a.gpuPower) + "W";
            }
            if (a.gpuCount > 1) {
                gpuLine += " \u00B7 +" + (a.gpuCount - 1) + " GPU";
            }
            int gc = a.gpuTemp >= 85 ? C_RED : (a.gpuTemp >= 70 ? C_AMBER : C_SUB);
            subPaint.setColor(gc);
            subPaint.setTextSize(dp(10));
            fit(subPaint, gpuLine, w - pad * 2);
            c.drawText(gpuLine, l + pad, t + h * (big ? 0.77f : 0.81f), subPaint);
        }

        // hardware: CPU / RAM / DISK
        if (a.cpuPct >= 0 || a.ramPct >= 0) {
            String sysLine = "CPU " + Math.round(a.cpuPct) + "%";
            if (a.ramTotal > 0) {
                sysLine += " \u00B7 RAM " + Math.round(a.ramPct) + "%";
                if (big) {
                    sysLine += " (" + String.format("%.1f", a.ramUsed) + "/"
                            + String.format("%.1f", a.ramTotal) + " GB)";
                }
            }
            if (a.diskPct >= 0) {
                sysLine += " \u00B7 DISK " + Math.round(a.diskPct) + "%";
                if (big && a.diskFree > 0) {
                    sysLine += " (" + Math.round(a.diskFree) + " GB free)";
                }
            }
            subPaint.setColor(C_SUB);
            subPaint.setTextSize(dp(10));
            fit(subPaint, sysLine, w - pad * 2);
            c.drawText(sysLine, l + pad, t + h * (big ? 0.84f : 0.88f), subPaint);
        }

        // requests + compactions
        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(10));
        c.drawText(a.processing + " req \u00B7 " + a.busySlots + "/" + a.slots
                        + " slots \u00B7 q " + a.deferred
                        + " \u00B7 comp " + (a.compactions == null ? "--" : a.compactions),
                l + pad, t + h * (big ? 0.93f : 0.95f), subPaint);

        if (a.gpuCount > 1) {
            labelPaint.setColor(C_ACCENT);
            labelPaint.setTextSize(dp(9));
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            c.drawText("tap for GPUs", r - pad, t + h * (big ? 0.93f : 0.95f), labelPaint);
            labelPaint.setTextAlign(Paint.Align.LEFT);
        }

        if (!a.online && a.error.length() > 0) {
            subPaint.setColor(C_RED);
            subPaint.setTextSize(dp(9));
            String e = a.error.length() > 70 ? a.error.substring(0, 70) : a.error;
            c.drawText(e, l + pad, b - dp(6), subPaint);
        }
    }
}
