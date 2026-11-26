package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Populates the compact Home dashboard widgets:
 * - CPU / RAM / DISK bars
 * - Weather + AQI (Depok)
 * - Prayer Times (Depok, Kemenag RI method=20)
 * - IHSG & S&P 500 with sparklines
 * - Google Photos shared album widget rotating every 60 seconds
 */
final class DashboardController {
    private static final double LAT = -6.4025;
    private static final double LON = 106.7942;
    private static final int PRAYER_METHOD = 20;
    private static final long STATS_INTERVAL_MS = 5_000L;
    private static final long PRAYER_TICK_MS = 30_000L;
    private static final long WEATHER_REFRESH_MS = 30 * 60_000L;
    private static final long MARKET_REFRESH_MS = 5 * 60_000L;
    private static final long PHOTO_ROTATE_MS = 60_000L;

    private static final Pattern GOOGLE_PHOTO_URL = Pattern.compile(
            "https://lh3\\.googleusercontent\\.com/(?:pw/|a/)?[A-Za-z0-9_\\-]{20,}");

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final String albumUrl;

    private final View rootOverlay;
    private final TextView weatherText;
    private final TextView cpuValue;
    private final TextView ramValue;
    private final TextView ramSub;
    private final View ramBarFill;
    private final TextView diskValue;
    private final TextView diskSub;
    private final View diskBarFill;

    private final TextView prayerMeta;
    private final TextView prayerCountdown;
    private final LinearLayout[] prayerCols = new LinearLayout[5];
    private final TextView[] prayerTimes = new TextView[5];

    private final TextView ihsgBadge;
    private final TextView ihsgPrice;
    private final TextView ihsgDelta;
    private final SparklineView ihsgChart;
    private final TextView spBadge;
    private final TextView spPrice;
    private final TextView spDelta;
    private final SparklineView spChart;

    private final ImageView photoImage;
    private final TextView photoStatus;

    private boolean running;
    private long lastWeatherFetch;
    private long lastMarketFetch;
    private String lastPrayerDay = "";
    private final int[] prayerMinutes = new int[]{-1, -1, -1, -1, -1};
    private final List<String> photoUrls = new ArrayList<>();
    private int photoIndex;

    DashboardController(Activity activity) {
        this.activity = activity;
        this.albumUrl = activity.getString(R.string.google_photos_album_url).trim();
        this.rootOverlay = activity.findViewById(R.id.dashboardOverlay);
        this.weatherText = activity.findViewById(R.id.dashWeather);
        this.cpuValue = activity.findViewById(R.id.dashCpuValue);
        this.ramValue = activity.findViewById(R.id.dashRamValue);
        this.ramSub = activity.findViewById(R.id.dashRamSub);
        this.ramBarFill = activity.findViewById(R.id.dashRamFill);
        this.diskValue = activity.findViewById(R.id.dashDiskValue);
        this.diskSub = activity.findViewById(R.id.dashDiskSub);
        this.diskBarFill = activity.findViewById(R.id.dashDiskFill);

        this.prayerMeta = activity.findViewById(R.id.dashPrayerMeta);
        this.prayerCountdown = activity.findViewById(R.id.dashPrayerCountdown);
        int[] colIds = {R.id.prayerCol0, R.id.prayerCol1, R.id.prayerCol2, R.id.prayerCol3, R.id.prayerCol4};
        int[] timeIds = {R.id.prayerTime0, R.id.prayerTime1, R.id.prayerTime2, R.id.prayerTime3, R.id.prayerTime4};
        for (int i = 0; i < 5; i++) {
            prayerCols[i] = activity.findViewById(colIds[i]);
            prayerTimes[i] = activity.findViewById(timeIds[i]);
        }

        this.ihsgBadge = null;
        this.ihsgPrice = null;
        this.ihsgDelta = null;
        this.ihsgChart = null;
        this.spBadge = null;
        this.spPrice = null;
        this.spDelta = null;
        this.spChart = null;

        this.photoImage = activity.findViewById(R.id.dashPhotoImage);
        this.photoStatus = activity.findViewById(R.id.dashPhotoStatus);
    }

    void onResume() {
        if (rootOverlay == null) return;
        running = true;
        main.removeCallbacksAndMessages(null);
        main.post(statsTick);
        main.post(prayerTick);
        main.post(networkTick);
        main.post(photoTick);
    }

    void onPause() {
        running = false;
        main.removeCallbacksAndMessages(null);
    }

    void setGridScrolled(boolean scrolledDown) {
        if (rootOverlay == null) return;
        rootOverlay.animate().cancel();
        if (scrolledDown) {
            rootOverlay.setAlpha(0f);
            rootOverlay.setVisibility(View.INVISIBLE);
        } else {
            rootOverlay.setVisibility(View.VISIBLE);
            rootOverlay.animate().alpha(1f).setDuration(150).start();
        }
    }

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            io.execute(DashboardController.this::readSystemStats);
            main.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    private final Runnable prayerTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            updatePrayerHighlight();
            String today = dayKey();
            if (!today.equals(lastPrayerDay)) {
                io.execute(() -> fetchPrayerTimes(today));
            }
            main.postDelayed(this, PRAYER_TICK_MS);
        }
    };

    private final Runnable networkTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            if (now - lastWeatherFetch >= WEATHER_REFRESH_MS) {
                lastWeatherFetch = now;
                io.execute(DashboardController.this::fetchWeatherAndAqi);
            }
            main.postDelayed(this, 60_000L);
        }
    };

    private final Runnable photoTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (albumUrl.isEmpty()) {
                if (photoStatus != null) {
                    photoStatus.setVisibility(View.VISIBLE);
                    photoStatus.setText("Google Photos · tambahkan link album");
                }
                return;
            }
            io.execute(DashboardController.this::loadNextPhoto);
            main.postDelayed(this, PHOTO_ROTATE_MS);
        }
    };

    private void readSystemStats() {
        String cpuGhz = readCpuGhz();
        long[] mem = readMemMb();
        long[] disk = readDiskGb();
        main.post(() -> {
            if (!running) return;
            if (cpuValue != null) cpuValue.setText(cpuGhz);
            if (mem != null && ramValue != null && ramSub != null && ramBarFill != null) {
                int pct = (int) Math.min(100, Math.round((mem[0] * 100.0) / Math.max(1, mem[1])));
                ramValue.setText(pct + "%");
                ramSub.setText(String.format(Locale.US, "%.1f / %.1f GB", mem[0] / 1024.0, mem[1] / 1024.0));
                setBarPercent(ramBarFill, pct);
            }
            if (disk != null && diskValue != null && diskSub != null && diskBarFill != null) {
                int pct = (int) Math.min(100, Math.round(((disk[1] - disk[0]) * 100.0) / Math.max(1, disk[1])));
                diskValue.setText(pct + "%");
                diskSub.setText(String.format(Locale.US, "Bebas %.1f GB", disk[0] / 1024.0));
                setBarPercent(diskBarFill, pct);
            }
        });
    }

    private static void setBarPercent(View fill, int pct) {
        View parent = (View) fill.getParent();
        int maxW = parent != null ? parent.getWidth() : 0;
        if (maxW <= 0) maxW = (int) (72 * fill.getResources().getDisplayMetrics().density);
        android.view.ViewGroup.LayoutParams lp = fill.getLayoutParams();
        lp.width = Math.max(4, (maxW * pct) / 100);
        fill.setLayoutParams(lp);
    }

    private static String readCpuGhz() {
        try {
            String raw = new String(Files.readAllBytes(Paths.get(
                    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")), StandardCharsets.UTF_8).trim();
            long khz = Long.parseLong(raw);
            return String.format(Locale.US, "%.1f GHz", khz / 1_000_000.0);
        } catch (Exception e) {
            return "—";
        }
    }

    private static long[] readMemMb() {
        try {
            List<String> lines = Files.readAllLines(Paths.get("/proc/meminfo"), StandardCharsets.UTF_8);
            return MemInfo.usedOfTotalMb(lines);
        } catch (Exception e) {
            return null;
        }
    }

    private static long[] readDiskGb() {
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long totalMb = (sf.getBlockCountLong() * sf.getBlockSizeLong()) / (1024L * 1024L);
            long freeMb = (sf.getAvailableBlocksLong() * sf.getBlockSizeLong()) / (1024L * 1024L);
            return new long[]{freeMb, totalMb};
        } catch (Exception e) {
            return null;
        }
    }

    private void fetchWeatherAndAqi() {
        try {
            String wJson = httpGet(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
                    LAT, LON));
            String aJson = httpGet(String.format(Locale.US,
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f&current=us_aqi",
                    LAT, LON));
            JSONObject wCur = new JSONObject(wJson).getJSONObject("current");
            int temp = (int) Math.round(wCur.getDouble("temperature_2m"));
            int code = wCur.getInt("weather_code");
            int aqi = new JSONObject(aJson).getJSONObject("current").getInt("us_aqi");
            String label = temp + "°C " + weatherLabel(code) + " · AQI " + aqi;
            main.post(() -> {
                if (running && weatherText != null) weatherText.setText(label);
            });
        } catch (Exception ignored) {
        }
    }

    private static String weatherLabel(int code) {
        if (code == 0) return "Cerah";
        if (code <= 2) return "Cerah berawan";
        if (code == 3) return "Berawan";
        if (code <= 48) return "Berkabut";
        if (code <= 67) return "Hujan";
        if (code <= 82) return "Hujan lebat";
        return "Badai petir";
    }

    private void fetchPrayerTimes(String today) {
        try {
            String json = httpGet(String.format(Locale.US,
                    "https://api.aladhan.com/v1/timings?latitude=%.4f&longitude=%.4f&method=%d",
                    LAT, LON, PRAYER_METHOD));
            JSONObject data = new JSONObject(json).getJSONObject("data");
            JSONObject t = data.getJSONObject("timings");
            String[] keys = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
            String[] times = new String[5];
            int[] mins = new int[5];
            for (int i = 0; i < 5; i++) {
                String raw = t.getString(keys[i]).replaceAll("\\s*\\(.*\\)", "").trim();
                times[i] = raw;
                String[] parts = raw.split(":");
                mins[i] = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
            JSONObject hijri = data.getJSONObject("date").getJSONObject("hijri");
            String hDay = hijri.getString("day");
            String hMonth = hijri.getJSONObject("month").getString("en");
            String hYear = hijri.getString("year");
            String meta = hDay + " " + hMonth + " " + hYear + " H  |  DEPOK";

            main.post(() -> {
                if (!running) return;
                lastPrayerDay = today;
                if (prayerMeta != null) prayerMeta.setText(meta);
                for (int i = 0; i < 5; i++) {
                    prayerMinutes[i] = mins[i];
                    if (prayerTimes[i] != null) prayerTimes[i].setText(times[i]);
                }
                updatePrayerHighlight();
            });
        } catch (Exception ignored) {
        }
    }

    private void updatePrayerHighlight() {
        if (prayerMinutes[0] < 0) return;
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int nextIdx = -1;
        for (int i = 0; i < 5; i++) {
            if (prayerMinutes[i] > nowMin) {
                nextIdx = i;
                break;
            }
        }
        int diffMin;
        int highlightIdx;
        if (nextIdx >= 0) {
            highlightIdx = nextIdx;
            diffMin = prayerMinutes[nextIdx] - nowMin;
        } else {
            highlightIdx = 0;
            diffMin = (24 * 60 - nowMin) + prayerMinutes[0];
        }
        String[] names = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
        int h = diffMin / 60;
        int m = diffMin % 60;
        String cd = String.format(Locale.US, "%s dalam %02dj %02dm", names[highlightIdx], h, m);
        if (prayerCountdown != null) prayerCountdown.setText(cd);
        for (int i = 0; i < 5; i++) {
            if (prayerCols[i] != null) {
                prayerCols[i].setBackgroundResource(i == highlightIdx ? R.drawable.dashboard_prayer_active : 0);
            }
        }
    }

    private void fetchMarkets() {
        fetchOneMarket("%5EJKSE", ihsgBadge, ihsgPrice, ihsgDelta, ihsgChart);
        fetchOneMarket("%5EGSPC", spBadge, spPrice, spDelta, spChart);
    }

    private void fetchOneMarket(String symbol, TextView badge, TextView priceView,
                                TextView deltaView, SparklineView chart) {
        try {
            String json = httpGet("https://query1.finance.yahoo.com/v8/finance/chart/"
                    + symbol + "?interval=15m&range=1d");
            JSONObject res = new JSONObject(json).getJSONObject("chart")
                    .getJSONArray("result").getJSONObject(0);
            JSONObject meta = res.getJSONObject("meta");
            double price = meta.getDouble("regularMarketPrice");
            double prev = meta.optDouble("chartPreviousClose", price);
            String state = meta.optString("marketState", "CLOSED");
            boolean open = "REGULAR".equalsIgnoreCase(state);

            JSONArray closes = res.getJSONObject("indicators").getJSONArray("quote")
                    .getJSONObject(0).getJSONArray("close");
            List<Float> pts = new ArrayList<>();
            for (int i = 0; i < closes.length(); i++) {
                if (!closes.isNull(i)) pts.add((float) closes.getDouble(i));
            }

            double diff = price - prev;
            double pct = prev != 0 ? (diff / prev) * 100.0 : 0.0;
            boolean up = diff >= 0;
            DecimalFormatSymbols sym = new DecimalFormatSymbols(new Locale("id", "ID"));
            DecimalFormat df = new DecimalFormat("#,##0.00", sym);
            String priceStr = df.format(price);
            String deltaStr = (up ? "▲ " : "▼ ") + df.format(Math.abs(diff))
                    + " · " + df.format(Math.abs(pct)) + "%";
            int color = up ? 0xFF4ADE80 : 0xFFEF6C9A;

            main.post(() -> {
                if (!running) return;
                if (badge != null) {
                    badge.setText(open ? "OPEN" : "CLOSED");
                    badge.setBackgroundResource(open ? R.drawable.dashboard_badge_open : R.drawable.dashboard_badge_closed);
                    badge.setTextColor(open ? 0xFF4ADE80 : 0xB3FFFFFF);
                }
                if (priceView != null) priceView.setText(priceStr);
                if (deltaView != null) {
                    deltaView.setText(deltaStr);
                    deltaView.setTextColor(color);
                }
                if (chart != null) chart.setSeries(pts, color);
            });
        } catch (Exception ignored) {
        }
    }

    private void loadNextPhoto() {
        try {
            if (photoUrls.isEmpty()) {
                String html = httpGet(albumUrl);
                Matcher m = GOOGLE_PHOTO_URL.matcher(html);
                Set<String> found = new LinkedHashSet<>();
                while (m.find()) {
                    String u = m.group();
                    if (!u.contains("/a/")) found.add(u + "=w800-h600");
                }
                photoUrls.addAll(found);
            }
            if (photoUrls.isEmpty()) {
                main.post(() -> {
                    if (running && photoStatus != null) {
                        photoStatus.setVisibility(View.VISIBLE);
                        photoStatus.setText("Album Google Photos kosong / privat");
                    }
                });
                return;
            }
            String url = photoUrls.get(photoIndex % photoUrls.size());
            photoIndex = (photoIndex + 1) % photoUrls.size();
            Bitmap bmp = httpBitmap(url);
            if (bmp == null) return;
            main.post(() -> {
                if (!running || photoImage == null) return;
                if (photoStatus != null) photoStatus.setVisibility(View.GONE);
                photoImage.setAlpha(0f);
                photoImage.setImageBitmap(bmp);
                photoImage.animate().alpha(1f).setDuration(350).start();
            });
        } catch (Exception ignored) {
        }
    }

    private static String dayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) CleanYourTV/1.0");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static Bitmap httpBitmap(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream is = conn.getInputStream()) {
            return BitmapFactory.decodeStream(is);
        } finally {
            conn.disconnect();
        }
    }
}
