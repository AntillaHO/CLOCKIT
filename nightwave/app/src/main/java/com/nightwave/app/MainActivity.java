package com.nightwave.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/** NIGHTWAVE: playback-capture meter plus OS media-volume ceiling. Not a DSP limiter. */
public final class MainActivity extends Activity {
    private static final int REQ_RECORD = 24;
    private static final int REQ_PROJECTION = 25;
    private static final int BG = Color.rgb(8, 14, 29);
    private static final int PANEL = Color.rgb(18, 29, 49);
    private static final int MUTED = Color.rgb(149, 168, 190);
    private static final int LIGHT = Color.rgb(241, 248, 255);
    private static final int ACCENT = Color.rgb(105, 225, 219);

    private TextView rmsText, peakText, stateText, thresholdText, capText, capState;
    private ProgressBar rmsBar, peakBar;
    private Button captureButton;
    private CheckBox enableCap;
    private AudioRecord recorder;
    private MediaProjection projection;
    private Thread meterThread;
    private volatile boolean running;
    private int thresholdDb = -18;
    private int capPercent = 35;
    private AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ceilingCheck = new Runnable() {
        @Override public void run() {
            if (enableCap != null && enableCap.isChecked()) enforceCeiling();
            handler.postDelayed(this, 450);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(24), dp(28), dp(24), dp(35));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);

        TextView brand = txt("◉  NIGHTWAVE", 25, LIGHT, true);
        brand.setLetterSpacing(.13f);
        root.addView(brand);
        root.addView(txt("SLEEP AUDIO  /  CONTROL CENTER", 11, MUTED, false));
        gap(root, 24);

        LinearLayout meterPanel = panel(root);
        meterPanel.addView(txt("LIVE AUDIO LEVEL", 12, ACCENT, true));
        gap(meterPanel, 9);
        rmsText = txt("−∞", 56, LIGHT, true);
        meterPanel.addView(rmsText);
        meterPanel.addView(txt("RMS  ·  dBFS", 12, MUTED, false));
        gap(meterPanel, 12);
        rmsBar = bar(meterPanel, ACCENT);
        gap(meterPanel, 20);
        peakText = txt("PEAK  −∞ dBFS", 15, LIGHT, true);
        meterPanel.addView(peakText);
        gap(meterPanel, 7);
        peakBar = bar(meterPanel, Color.rgb(255, 183, 105));
        gap(meterPanel, 16);
        stateText = txt("●  Monitoring nicht gestartet", 13, MUTED, false);
        meterPanel.addView(stateText);
        gap(root, 14);

        LinearLayout warnPanel = panel(root);
        warnPanel.addView(txt("PEAK-ALARM", 12, MUTED, true));
        thresholdText = txt("−18 dBFS", 23, LIGHT, true);
        warnPanel.addView(thresholdText);
        SeekBar warn = new SeekBar(this);
        warn.setMax(55);
        warn.setProgress(42);
        warn.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        warnPanel.addView(warn);
        warn.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                thresholdDb = p - 60;
                thresholdText.setText(String.format(Locale.GERMANY, "%d dBFS", thresholdDb));
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        warnPanel.addView(txt("Warnung bei lauten Signalen – keine Audioveränderung.", 12, MUTED, false));
        gap(root, 14);

        LinearLayout capPanel = panel(root);
        capPanel.addView(txt("MEDIA VOLUME CAP", 12, ACCENT, true));
        capText = txt("35 %", 23, LIGHT, true);
        capPanel.addView(capText);
        SeekBar capSlider = new SeekBar(this);
        capSlider.setMax(95);
        capSlider.setProgress(capPercent - 5);
        capSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        capPanel.addView(capSlider);
        capSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                capPercent = value + 5;
                capText.setText(capPercent + " %");
                if (enableCap != null && enableCap.isChecked()) enforceCeiling();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        enableCap = new CheckBox(this);
        enableCap.setText("Medienlautstärke begrenzen");
        enableCap.setTextColor(LIGHT);
        enableCap.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        capPanel.addView(enableCap);
        capState = txt("Aus – Android-Medienlautstärke bleibt unverändert.", 12, MUTED, false);
        capPanel.addView(capState);
        enableCap.setOnCheckedChangeListener((b, checked) -> {
            capState.setText(checked ? "Aktiv: Medienlautstärke wird auf Maximum begrenzt." : "Aus – Android-Medienlautstärke bleibt unverändert.");
            if (checked) enforceCeiling();
        });
        gap(root, 19);

        captureButton = new Button(this);
        captureButton.setAllCaps(false);
        captureButton.setText("Monitoring starten");
        captureButton.setTextSize(16);
        captureButton.setTextColor(BG);
        captureButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        captureButton.setBackground(rounded(ACCENT, 18));
        root.addView(captureButton, new LinearLayout.LayoutParams(-1, dp(60)));
        captureButton.setOnClickListener(v -> {
            if (running) stopCapture(); else beginCapture();
        });
        gap(root, 18);
        root.addView(txt("HINWEIS  ·  Android lässt nicht jede App aufnehmen. Der Volume Cap begrenzt die System-Medienlautstärke, nicht einzelne Soundspitzen. Kein echter systemweiter dB-Limiter. Die Anzeige ist dBFS, kein Schalldruck in dB SPL.", 12, MUTED, false));
        handler.post(ceilingCheck);
    }

    private void beginCapture() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if (request == REQ_RECORD) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) beginCapture();
            else stateText.setText("Mikrofon-/Audio-Berechtigung benötigt.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_PROJECTION) return;
        if (result != RESULT_OK || data == null) {
            stateText.setText("Freigabe abgebrochen.");
            return;
        }
        try {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(result, data);
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME).build();
            int sr = 44100;
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, 8192))
                    .setAudioPlaybackCaptureConfig(config).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord initialization failed");
            recorder.startRecording();
            running = true;
            captureButton.setText("Monitoring stoppen");
            stateText.setText("●  Monitoring aktiv · kompatible Apps");
            meterThread = new Thread(this::readLevels, "NightwaveMeter");
            meterThread.start();
        } catch (Exception e) {
            stopCapture();
            stateText.setText("Audio-Capture fehlgeschlagen: " + e.getClass().getSimpleName());
        }
    }

    private void readLevels() {
        final AudioRecord local = recorder;
        short[] samples = new short[2048];
        long lastUi = 0;
        while (running && local != null) {
            int n;
            try { n = local.read(samples, 0, samples.length); }
            catch (Exception e) { break; }
            if (n <= 0) continue;
            double power = 0, peak = 0;
            for (int i = 0; i < n; i++) {
                double v = Math.abs(samples[i] / 32768.0);
                power += v * v;
                if (v > peak) peak = v;
            }
            double rmsDb = 20 * Math.log10(Math.max(Math.sqrt(power / n), 1e-6));
            double peakDb = 20 * Math.log10(Math.max(peak, 1e-6));
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastUi < 110) continue;
            lastUi = now;
            final int rmsProgress = toProgress(rmsDb);
            final int peakProgress = toProgress(peakDb);
            handler.post(() -> {
                if (!running) return;
                rmsText.setText(fmtDb(rmsDb));
                peakText.setText("PEAK  " + fmtDb(peakDb) + " dBFS");
                rmsBar.setProgress(rmsProgress);
                peakBar.setProgress(peakProgress);
                stateText.setText(peakDb >= thresholdDb ? "●  Spitze über Alarmgrenze" : "●  Monitoring aktiv · kompatible Apps");
                stateText.setTextColor(peakDb >= thresholdDb ? Color.rgb(255, 183, 105) : ACCENT);
            });
        }
    }

    private int toProgress(double db) { return Math.max(0, Math.min(100, (int) Math.round((db + 60) / 60 * 100))); }
    private String fmtDb(double db) { return db <= -99.9 ? "−∞" : String.format(Locale.GERMANY, "%.1f", db); }

    private void enforceCeiling() {
        if (audio == null) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int ceiling = Math.max(1, Math.round(max * capPercent / 100f));
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (current > ceiling) audio.setStreamVolume(AudioManager.STREAM_MUSIC, ceiling, 0);
    }

    private void stopCapture() {
        running = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release(); recorder = null;
        }
        if (projection != null) {
            projection.stop(); projection = null;
        }
        if (captureButton != null) captureButton.setText("Monitoring starten");
        if (stateText != null) { stateText.setText("●  Monitoring gestoppt"); stateText.setTextColor(MUTED); }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ceilingCheck);
        stopCapture();
        super.onDestroy();
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private TextView txt(String value, int size, int color, boolean strong) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextColor(color); v.setTextSize(size);
        if (strong) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setLineSpacing(dp(2), 1f);
        return v;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        return d;
    }
    private LinearLayout panel(LinearLayout root) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(19), dp(20), dp(19));
        p.setBackground(rounded(PANEL, 19));
        root.addView(p, new LinearLayout.LayoutParams(-1, -2));
        return p;
    }
    private ProgressBar bar(LinearLayout parent, int tint) {
        ProgressBar b = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        b.setMax(100);
        b.setProgressTintList(android.content.res.ColorStateList.valueOf(tint));
        b.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34, 48, 67)));
        parent.addView(b, new LinearLayout.LayoutParams(-1, dp(9)));
        return b;
    }
    private void gap(LinearLayout parent, int height) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
    }
}
