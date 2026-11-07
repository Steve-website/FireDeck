package com.steven.firedeck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * FireDeck - Stream Deck style grid for the 2013 Kindle Fire HD 7.
 * Pages, PNG icons, long-press actions, macros, and a live PC stats overlay.
 */
public class MainActivity extends Activity {

    private static final String DEF_HOST = "192.168.1.100";
    private static final int DEF_PORT = 9100;

    private static class Page {
        String name = "Page";
        String kind = "deck";
        int cols = 4;
        int rows = 3;
        JSONArray buttons;
    }

    private DeckView deck;
    private AgentsView agentsView;
    private volatile boolean agentsActive = false;
    private TextView status;
    private LinearLayout pageBar;
    private HorizontalScrollView pageScroll;
    private SharedPreferences prefs;
    private final Handler ui = new Handler();

    private FrameLayout statsOverlay;
    private StatsView statsView;
    private volatile boolean statsVisible = false;

    private final ArrayList<Page> pages = new ArrayList<Page>();
    private int pageIndex = 0;

    private final HashMap<String, Bitmap> imageCache = new HashMap<String, Bitmap>();
    private final HashSet<String> loadingImages = new HashSet<String>();

    private volatile boolean running = true;
    private String host;
    private int port;
    private String base;
    private String lastJson = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("firedeck", MODE_PRIVATE);
        host = prefs.getString("host", DEF_HOST);
        port = prefs.getInt("port", DEF_PORT);
        base = "http://" + host + ":" + port;

        FrameLayout root = new FrameLayout(this);

        LinearLayout baseLayout = new LinearLayout(this);
        baseLayout.setOrientation(LinearLayout.VERTICAL);
        baseLayout.setBackgroundColor(0xFF000000);
        root.addView(baseLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout content = new FrameLayout(this);
        baseLayout.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        deck = new DeckView(this);
        content.addView(deck, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        agentsView = new AgentsView(this);
        agentsView.setVisibility(View.GONE);
        content.addView(agentsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setText("connecting...");
        status.setTextSize(11);
        status.setTextColor(0xFFB0BEC5);
        status.setPadding(10, 3, 10, 3);
        status.setBackgroundColor(0x66000000);
        content.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT));
        status.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });

        pageScroll = new HorizontalScrollView(this);
        pageScroll.setBackgroundColor(0xFF0B0D11);
        pageScroll.setHorizontalScrollBarEnabled(false);
        pageBar = new LinearLayout(this);
        pageBar.setOrientation(LinearLayout.HORIZONTAL);
        pageBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        pageScroll.addView(pageBar);
        pageScroll.setVisibility(View.GONE);
        baseLayout.addView(pageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildStatsOverlay(root);

        setContentView(root);

        deck.setListener(new DeckView.Listener() {
            public void onPress(int index) {
                if (isStatsButton(index)) {
                    showStats();
                } else {
                    sendPress(index, false);
                }
            }

            public void onHold(int index) {
                if (isStatsButton(index)) {
                    showStats();
                } else {
                    sendPress(index, true);
                }
            }
        });

        deck.setImageProvider(new DeckView.ImageProvider() {
            public Bitmap get(String url) {
                return imageFor(url);
            }
        });

        startPolling();
        startStatsPolling();
        startAgentsPolling();
    }

    private void buildStatsOverlay(FrameLayout root) {
        statsOverlay = new FrameLayout(this);
        statsOverlay.setBackgroundColor(0xFF0B0D11);
        statsOverlay.setVisibility(View.GONE);

        statsView = new StatsView(this);
        statsOverlay.addView(statsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button back = new Button(this);
        back.setText("Back");
        back.setTextSize(11);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideStats();
            }
        });
        statsOverlay.addView(back, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT));

        root.addView(statsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void showStats() {
        statsVisible = true;
        statsOverlay.setVisibility(View.VISIBLE);
        statsOverlay.bringToFront();
    }

    private void hideStats() {
        statsVisible = false;
        statsOverlay.setVisibility(View.GONE);
    }

    private boolean isStatsButton(int index) {
        if (pages.isEmpty()) {
            return false;
        }
        JSONArray arr = pages.get(pageIndex).buttons;
        if (arr == null || index >= arr.length()) {
            return false;
        }
        JSONObject b = arr.optJSONObject(index);
        return b != null && "stats".equals(b.optString("type"));
    }

    private void startStatsPolling() {
        new Thread(new Runnable() {
            public void run() {
                while (running) {
                    if (statsVisible) {
                        try {
                            String json = httpGet(base + "/stats");
                            final JSONObject o = new JSONObject(json);
                            ui.post(new Runnable() {
                                public void run() {
                                    statsView.setStats(o);
                                }
                            });
                        } catch (Exception ignored) {
                        }
                        sleep(1500);
                    } else {
                        sleep(400);
                    }
                }
            }
        }).start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void startPolling() {
        running = true;
        new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        String json = httpGet(base + "/layout");
                        if (json != null && !json.equals(lastJson)) {
                            lastJson = json;
                            final String j = json;
                            ui.post(new Runnable() {
                                public void run() {
                                    applyLayout(j);
                                }
                            });
                        }
                        setStatus("online " + host + ":" + port);
                    } catch (Exception e) {
                        setStatus("offline - retrying");
                    }
                    sleep(3000);
                }
            }
        }).start();
    }

    private void applyLayout(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("pages");
            ArrayList<Page> list = new ArrayList<Page>();

            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Page p = new Page();
                    p.name = o.optString("name", "Page");
                    p.kind = o.optString("kind", "deck");
                    p.cols = o.optInt("cols", 4);
                    p.rows = o.optInt("rows", 3);
                    p.buttons = o.optJSONArray("buttons");
                    list.add(p);
                }
            }
            if (list.isEmpty()) {
                Page p = new Page();
                p.name = root.optString("name", "Main");
                p.cols = root.optInt("cols", 4);
                p.rows = root.optInt("rows", 3);
                p.buttons = root.optJSONArray("buttons");
                list.add(p);
            }

            pages.clear();
            pages.addAll(list);
            if (pageIndex >= pages.size()) {
                pageIndex = 0;
            }
            rebuildPageBar();
            applyPage();
        } catch (Exception e) {
            setStatus("bad layout: " + e.getMessage());
        }
    }

    private void applyPage() {
        if (pages.isEmpty()) {
            agentsActive = false;
            agentsView.setVisibility(View.GONE);
            deck.setVisibility(View.VISIBLE);
            deck.setLayout(4, 3, null);
            return;
        }
        Page p = pages.get(pageIndex);
        boolean agents = "agents".equals(p.kind);
        agentsActive = agents;
        if (agents) {
            deck.setVisibility(View.GONE);
            agentsView.setVisibility(View.VISIBLE);
        } else {
            agentsView.setVisibility(View.GONE);
            deck.setVisibility(View.VISIBLE);
            deck.setLayout(p.cols, p.rows, p.buttons);
        }
    }

    private void startAgentsPolling() {
        new Thread(new Runnable() {
            public void run() {
                while (running) {
                    if (agentsActive) {
                        try {
                            String json = httpGet(base + "/agents");
                            final JSONObject o = new JSONObject(json);
                            ui.post(new Runnable() {
                                public void run() {
                                    agentsView.setAgent(o);
                                }
                            });
                        } catch (Exception ignored) {
                        }
                        sleep(1000);
                    } else {
                        sleep(500);
                    }
                }
            }
        }).start();
    }

    private void rebuildPageBar() {
        pageBar.removeAllViews();
        if (pages.size() <= 1) {
            pageScroll.setVisibility(View.GONE);
            return;
        }
        pageScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            final Page p = pages.get(i);
            Button b = new Button(this);
            b.setText(p.name);
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setPadding(dp(14), dp(2), dp(14), dp(2));
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            if (i == pageIndex) {
                b.setBackgroundColor(0xFF4FC3F7);
                b.setTextColor(0xFF06222E);
            } else {
                b.setBackgroundColor(0xFF1B1F29);
                b.setTextColor(0xFFE6E9EF);
            }
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    pageIndex = idx;
                    rebuildPageBar();
                    applyPage();
                }
            });
            pageBar.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void sendPress(final int index, final boolean hold) {
        final int page = pageIndex;
        new Thread(new Runnable() {
            public void run() {
                try {
                    httpGet(base + "/press?page=" + page + "&id=" + index + (hold ? "&hold=1" : ""));
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private Bitmap imageFor(String url) {
        if (url == null || url.length() == 0) {
            return null;
        }
        synchronized (imageCache) {
            Bitmap b = imageCache.get(url);
            if (b != null) {
                return b;
            }
        }
        synchronized (loadingImages) {
            if (loadingImages.contains(url)) {
                return null;
            }
            loadingImages.add(url);
        }
        final String u = url;
        new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] data = httpGetBytes(base + u);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    final Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    if (bm != null) {
                        synchronized (imageCache) {
                            if (imageCache.size() > 100) {
                                imageCache.clear();
                            }
                            imageCache.put(u, bm);
                        }
                        ui.post(new Runnable() {
                            public void run() {
                                deck.invalidate();
                            }
                        });
                    }
                } catch (Exception ignored) {
                } finally {
                    synchronized (loadingImages) {
                        loadingImages.remove(u);
                    }
                }
            }
        }).start();
        return null;
    }

    private String httpGet(String url) throws Exception {
        return new String(httpGetBytes(url), "UTF-8");
    }

    private byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(4000);
        try {
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            is.close();
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            public void run() {
                status.setText(s);
            }
        });
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 16, 32, 16);

        TextView hl = new TextView(this);
        hl.setText("PC host / IP");
        box.addView(hl);
        final EditText hostBox = new EditText(this);
        hostBox.setSingleLine(true);
        hostBox.setText(host);
        box.addView(hostBox);

        TextView pl = new TextView(this);
        pl.setText("Port");
        box.addView(pl);
        final EditText portBox = new EditText(this);
        portBox.setSingleLine(true);
        portBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        portBox.setText(String.valueOf(port));
        box.addView(portBox);

        new AlertDialog.Builder(this)
                .setTitle("FireDeck")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        host = hostBox.getText().toString().trim();
                        try {
                            port = Integer.parseInt(portBox.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        base = "http://" + host + ":" + port;
                        prefs.edit().putString("host", host).putInt("port", port).commit();
                        lastJson = "";
                        setStatus("connecting...");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
