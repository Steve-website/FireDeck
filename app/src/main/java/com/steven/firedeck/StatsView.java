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

/** Live PC dashboard: gauges, sparklines, thresholds. */
public class StatsView extends View {

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

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gaugeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gaugeFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private final RectF arc = new RectF();
    private final RectF bar = new RectF();
    private final Path spark = new Path();

    private double cpuPct, cpuTemp = -1, cpuFreq = -1;
    private double gpuPct = -1, gpuTemp = -1, gpuMemUsed = -1, gpuMemTotal = -1;
    private double gpuPower = -1, gpuFan = -1, gpuClock = -1;
    private double ramPct, ramUsed, ramTotal;
    private double diskPct, diskFree;
    private double netDown, netUp;
    private double uptime;
    private String cpuName = "";
    private String gpuName = "";
    private String topName = "";
    private double topCpu;
    private int cpuCores;
    private boolean hasGpu, hasTop;

    private static final int HIST = 60;
    private final double[] cpuHist = new double[HIST];
    private final double[] gpuHist = new double[HIST];
    private final double[] ramHist = new double[HIST];
    private final double[] netHist = new double[HIST];
    private int histIdx = 0;
    private int histCount = 0;

    public StatsView(Context c) {
        super(c);
        setBackgroundColor(C_BG);
        labelPaint.setColor(C_LABEL);
        valuePaint.setColor(C_TEXT);
        valuePaint.setFakeBoldText(true);
        subPaint.setColor(C_SUB);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(C_BORDER);
        gaugeBg.setStyle(Paint.Style.STROKE);
        gaugeBg.setColor(C_TRACK);
        gaugeBg.setStrokeCap(Paint.Cap.ROUND);
        gaugeFg.setStyle(Paint.Style.STROKE);
        gaugeFg.setStrokeCap(Paint.Cap.ROUND);
        sparkPaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStrokeWidth(dp(2));
        sparkPaint.setStrokeCap(Paint.Cap.ROUND);
        barBg.setColor(C_TRACK);
        barFg.setColor(C_ACCENT);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void pushAll(double v1, double v2, double v3, double v4) {
        cpuHist[histIdx] = v1;
        gpuHist[histIdx] = v2 < 0 ? 0 : v2;
        ramHist[histIdx] = v3;
        netHist[histIdx] = v4;
        histIdx = (histIdx + 1) % HIST;
        if (histCount < HIST) {
            histCount++;
        }
    }

    public void setStats(JSONObject o) {
        if (o == null) {
            return;
        }
        try {
            JSONObject cpu = o.optJSONObject("cpu");
            if (cpu != null) {
                cpuPct = cpu.optDouble("pct", 0);
                cpuTemp = cpu.isNull("temp") ? -1 : cpu.optDouble("temp", -1);
                cpuFreq = cpu.isNull("freq") ? -1 : cpu.optDouble("freq", -1);
                cpuName = cpu.optString("name", "");
                cpuCores = cpu.optInt("cores", 0);
            }
            JSONObject gpu = o.optJSONObject("gpu");
            hasGpu = gpu != null;
            if (gpu != null) {
                gpuPct = gpu.optDouble("pct", -1);
                gpuTemp = gpu.optDouble("temp", -1);
                gpuMemUsed = gpu.optDouble("mem_used", -1);
                gpuMemTotal = gpu.optDouble("mem_total", -1);
                gpuPower = gpu.optDouble("power", -1);
                gpuFan = gpu.optDouble("fan", -1);
                gpuClock = gpu.optDouble("clock", -1);
                gpuName = gpu.optString("name", "");
            }
            if (gpuTemp < 0) {
                gpuTemp = o.isNull("gpu_temp") ? -1 : o.optDouble("gpu_temp", -1);
            }
            JSONObject ram = o.optJSONObject("ram");
            if (ram != null) {
                ramPct = ram.optDouble("pct", 0);
                ramUsed = ram.optDouble("used", 0);
                ramTotal = ram.optDouble("total", 0);
            }
            JSONObject disk = o.optJSONObject("disk");
            if (disk != null) {
                diskPct = disk.optDouble("pct", 0);
                diskFree = disk.optDouble("free", 0);
            }
            JSONObject net = o.optJSONObject("net");
            if (net != null) {
                netDown = net.optDouble("down", 0);
                netUp = net.optDouble("up", 0);
            }
            JSONArray top = o.optJSONArray("top");
            hasTop = top != null && top.length() > 0;
            if (hasTop) {
                JSONObject t = top.getJSONObject(0);
                topName = t.optString("name", "");
                topCpu = t.optDouble("cpu", 0);
            }
            uptime = o.optDouble("uptime", 0);
            pushAll(cpuPct, gpuPct, ramPct, netDown);
        } catch (Exception ignored) {
        }
        invalidate();
    }

    // ---------- formatting ----------

    private static String pct(double v) {
        return v < 0 ? "--" : Math.round(v) + "%";
    }

    private static String temp(double v) {
        return v < 0 ? "--" : Math.round(v) + "\u00B0C";
    }

    private static String gb(double v) {
        return String.format("%.1f", v);
    }

    private static String mbs(double v) {
        return v >= 100 ? Math.round(v) + " MB/s" : String.format("%.1f MB/s", v);
    }

    private static String uptimeStr(double s) {
        int t = (int) s;
        int d = t / 86400;
        int h = (t % 86400) / 3600;
        int m = (t % 3600) / 60;
        return d > 0 ? (d + "d " + h + "h") : (h + "h " + m + "m");
    }

    private static int threshold(double pct) {
        if (pct >= 85) {
            return C_RED;
        }
        if (pct >= 60) {
            return C_AMBER;
        }
        return C_GREEN;
    }

    private static int tempColor(double t) {
        if (t < 0) {
            return C_LABEL;
        }
        if (t >= 85) {
            return C_RED;
        }
        if (t >= 70) {
            return C_AMBER;
        }
        return C_GREEN;
    }

    // ---------- primitives ----------

    private void fit(Paint p, String text, float maxW) {
        while (p.measureText(text) > maxW && p.getTextSize() > dp(9)) {
            p.setTextSize(p.getTextSize() - dp(1));
        }
    }

    private void drawCard(Canvas c, float l, float t, float r, float b) {
        card.set(l, t, r, b);
        fill.setColor(C_CARD);
        c.drawRoundRect(card, dp(14), dp(14), fill);
        border.setStrokeWidth(dp(1.2f));
        c.drawRoundRect(card, dp(14), dp(14), border);
    }

    private void drawText(Canvas c, String s, float x, float y, Paint p, float maxW) {
        fit(p, s, maxW);
        c.drawText(s, x, y, p);
    }

    private void drawGauge(Canvas c, float cx, float cy, float radius,
                           double value, int color, String center, String sub) {
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        gaugeBg.setStrokeWidth(dp(13));
        c.drawArc(arc, 135, 270, false, gaugeBg);

        gaugeFg.setStrokeWidth(dp(13));
        gaugeFg.setColor(color);
        float sweep = (float) (270.0 * Math.max(0, Math.min(100, value)) / 100.0);
        c.drawArc(arc, 135, sweep, false, gaugeFg);

        valuePaint.setColor(C_TEXT);
        valuePaint.setTextSize(radius * 0.62f);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(center, cx, cy + radius * 0.10f, valuePaint);

        labelPaint.setTextSize(dp(11));
        labelPaint.setColor(C_LABEL);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(sub, cx, cy + radius * 0.42f, labelPaint);
    }

    private void drawBar(Canvas c, float l, float t, float r, float b, double pct, int color) {
        bar.set(l, t, r, b);
        barBg.setColor(C_TRACK);
        c.drawRoundRect(bar, (b - t) / 2, (b - t) / 2, barBg);
        float w = (r - l) * (float) Math.max(0, Math.min(100, pct)) / 100f;
        bar.set(l, t, l + Math.max((b - t), w), b);
        barFg.setColor(color);
        c.drawRoundRect(bar, (b - t) / 2, (b - t) / 2, barFg);
    }

    private void drawSpark(Canvas c, float l, float t, float r, float b,
                           double[] hist, int color, double maxVal) {
        if (histCount < 2) {
            return;
        }
        spark.reset();
        double max = Math.max(1.0, maxVal);
        for (int i = 0; i < histCount; i++) {
            int idx = (histIdx - histCount + i + HIST) % HIST;
            double v = Math.max(0, Math.min(max, hist[idx]));
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

    // ---------- draw ----------

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
        labelPaint.setTextAlign(Paint.Align.LEFT);
        labelPaint.setFakeBoldText(true);
        canvas.drawText("PC STATS", dp(18), dp(28), labelPaint);
        labelPaint.setFakeBoldText(false);
        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(11));
        canvas.drawText("live \u00B7 up " + uptimeStr(uptime), dp(18), dp(46), labelPaint);

        float top = dp(62);
        float bottom = h - pad;
        float rowGap = gap;
        float topH = (bottom - top - rowGap) * 0.53f;
        float botH = bottom - top - rowGap - topH;

        float halfW = (w - pad * 2 - gap) / 2f;
        float cpuL = pad;
        float cpuR = pad + halfW;
        float gpuL = cpuR + gap;
        float gpuR = w - pad;

        drawCard(canvas, cpuL, top, cpuR, top + topH);
        drawCard(canvas, gpuL, top, gpuR, top + topH);

        // CPU card
        float radius = Math.min(topH * 0.33f, halfW * 0.20f);
        float gx = cpuL + dp(20) + radius;
        float gy = top + topH / 2f;
        drawGauge(canvas, gx, gy, radius, cpuPct, threshold(cpuPct), pct(cpuPct), "CPU");

        float tx = gx + radius + dp(18);
        float maxW = cpuR - tx - dp(14);
        valuePaint.setColor(tempColor(cpuTemp));
        valuePaint.setTextSize(dp(26));
        valuePaint.setTextAlign(Paint.Align.LEFT);
        drawText(canvas, temp(cpuTemp), tx, top + topH * 0.34f, valuePaint, maxW);

        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(12));
        String cpuLine = (cpuFreq > 0 ? String.format("%.1f GHz", cpuFreq / 1000.0) : "")
                + (cpuCores > 0 ? " \u00B7 " + cpuCores + " threads" : "");
        drawText(canvas, cpuLine.trim(), tx, top + topH * 0.52f, subPaint, maxW);

        if (hasTop) {
            drawText(canvas, "top: " + topName + " " + Math.round(topCpu) + "%",
                    tx, top + topH * 0.68f, subPaint, maxW);
        }

        // GPU card
        float gRadius = Math.min(topH * 0.33f, halfW * 0.20f);
        float ggx = gpuL + dp(20) + gRadius;
        float ggy = top + topH / 2f;
        drawGauge(canvas, ggx, ggy, gRadius, hasGpu ? gpuPct : 0,
                threshold(gpuPct), hasGpu ? pct(gpuPct) : "--", "GPU");

        float gtx = ggx + gRadius + dp(18);
        float gMax = gpuR - gtx - dp(14);
        valuePaint.setColor(tempColor(gpuTemp));
        valuePaint.setTextSize(dp(26));
        valuePaint.setTextAlign(Paint.Align.LEFT);
        drawText(canvas, temp(gpuTemp), gtx, top + topH * 0.34f, valuePaint, gMax);

        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(12));
        if (hasGpu) {
            String mem = String.format("%.1f / %.0f GB VRAM", gpuMemUsed / 1024.0, gpuMemTotal / 1024.0);
            drawText(canvas, mem, gtx, top + topH * 0.52f, subPaint, gMax);

            String watts = (gpuPower >= 0 ? Math.round(gpuPower) + " W" : "");
            String fan = (gpuFan >= 0 ? "fan " + Math.round(gpuFan) + "%" : "");
            drawText(canvas, (watts + " \u00B7 " + fan).trim(), gtx, top + topH * 0.68f, subPaint, gMax);

            if (gpuClock >= 0) {
                drawText(canvas, Math.round(gpuClock) + " MHz core", gtx, top + topH * 0.84f, subPaint, gMax);
            }
        } else {
            drawText(canvas, "not detected", gtx, top + topH * 0.52f, subPaint, gMax);
        }

        // bottom row
        float by = top + topH + rowGap;
        float bw = (w - pad * 2 - gap * 3) / 4f;
        float[] xs = {pad, pad + bw + gap, pad + (bw + gap) * 2, pad + (bw + gap) * 3};

        // RAM
        drawCard(canvas, xs[0], by, xs[0] + bw, by + botH);
        drawBottom(canvas, xs[0], by, bw, botH, "RAM", pct(ramPct),
                gb(ramUsed) + " / " + gb(ramTotal) + " GB", ramPct, threshold(ramPct), true,
                ramHist, C_ACCENT, 100);

        // TEMP
        drawCard(canvas, xs[1], by, xs[1] + bw, by + botH);
        String tv = cpuTemp >= 0 ? temp(cpuTemp) : temp(gpuTemp);
        String ts = cpuTemp >= 0
                ? "CPU" + (gpuTemp >= 0 ? " \u00B7 GPU " + Math.round(gpuTemp) + "\u00B0C" : "")
                : "GPU \u00B7 CPU n/a";
        double tval = cpuTemp >= 0 ? cpuTemp : gpuTemp;
        drawBottom(canvas, xs[1], by, bw, botH, "TEMP", tv, ts, tval, tempColor(tval), false,
                null, 0, 100);

        // NET
        drawCard(canvas, xs[2], by, xs[2] + bw, by + botH);
        drawBottom(canvas, xs[2], by, bw, botH, "NETWORK", mbs(netDown),
                "\u2191 " + mbs(netUp), -1, C_ACCENT, true, netHist, 0xFF26C6DA,
                Math.max(1.0, maxOf(netHist)));

        // DISK
        drawCard(canvas, xs[3], by, xs[3] + bw, by + botH);
        drawBottom(canvas, xs[3], by, bw, botH, "DISK", Math.round(diskFree) + " GB",
                "free \u00B7 " + Math.round(diskPct) + "% used", diskPct, threshold(diskPct), false,
                null, 0, 100);
    }

    private double maxOf(double[] hist) {
        double m = 0;
        for (int i = 0; i < HIST; i++) {
            m = Math.max(m, hist[i]);
        }
        return m;
    }

    private void drawBottom(Canvas c, float l, float t, float w, float h,
                            String label, String value, String sub,
                            double barPct, int color, boolean sparkline,
                            double[] hist, int sparkColor, double sparkMax) {
        float pad = dp(16);
        labelPaint.setColor(C_LABEL);
        labelPaint.setTextSize(dp(11));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(label, l + pad, t + dp(24), labelPaint);

        valuePaint.setColor(C_TEXT);
        valuePaint.setTextSize(Math.min(h * 0.22f, w * 0.20f));
        valuePaint.setTextAlign(Paint.Align.LEFT);
        drawText(c, value, l + pad, t + h * 0.52f, valuePaint, w - pad * 2);

        subPaint.setColor(C_SUB);
        subPaint.setTextSize(dp(11));
        drawText(c, sub, l + pad, t + h * 0.66f, subPaint, w - pad * 2);

        if (sparkline && hist != null) {
            drawSpark(c, l + pad, t + h * 0.72f, l + w - pad, t + h - dp(10), hist, sparkColor, sparkMax);
        } else if (barPct >= 0) {
            drawBar(c, l + pad, t + h - dp(20), l + w - pad, t + h - dp(12), barPct, color);
        }
    }
}
