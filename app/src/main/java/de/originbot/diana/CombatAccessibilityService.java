package de.originbot.diana;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.graphics.PixelFormat;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.Toast;

/** Experimental timed taps only. No screen analysis or boss attack recognition. */
public class CombatAccessibilityService extends AccessibilityService {
  static CombatAccessibilityService instance;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean running;
  private boolean dodgeTestRunning;
  private boolean gestureInFlight;
  private boolean periodicDodgeEnabled = true;
  private long nextPeriodicDodgeAt;
  private long nextAttackAt;
  private long dodgePriorityUntil;
  private float testDodgeX, testDodgeY;
  private final Runnable repeatedDodgeTest = new Runnable() {
    @Override public void run() {
      if (!dodgeTestRunning) return;
      tapWithFeedback(testDodgeX, testDodgeY);
      handler.postDelayed(this, 2000L);
    }
  };
  private long lastDodgeAt;
  private boolean screenshotPending;
  private boolean warningPreviouslyVisible;
  private int warningFrames;
  private long lastWarningAt;
  private final Runnable scanLoop = new Runnable() {
    @Override public void run() {
      if (!running || Build.VERSION.SDK_INT < 30 || screenshotPending) return;
      screenshotPending = true;
      takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
        @Override public void onSuccess(ScreenshotResult result) {
          screenshotPending = false;
          Bitmap frame = null;
          Bitmap readable = null;
          try {
            frame = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
            if (frame != null) {
              readable = frame.copy(Bitmap.Config.ARGB_8888, false);
              if (readable != null) analyzeFrame(readable);
            }
          } catch (RuntimeException ignored) {
          } finally {
            if (readable != null) readable.recycle();
            if (frame != null) frame.recycle();
            result.getHardwareBuffer().close();
            scheduleScan();
          }
        }
        @Override public void onFailure(int errorCode) {
          screenshotPending = false;
          scheduleScan();
        }
      });
    }
  };
  private void scheduleScan() {
    if (running && Build.VERSION.SDK_INT >= 30) handler.postDelayed(scanLoop, 160);
  }
  private void analyzeFrame(Bitmap frame) {
    if (!running) return;
    int w = frame.getWidth(), h = frame.getHeight();
    int hits = 0, samples = 0;
    // Central battlefield only: exclude red HP bars and action buttons at the edges.
    for (int y = h * 38 / 100; y < h * 82 / 100; y += Math.max(3, h / 85)) {
      for (int x = w * 23 / 100; x < w * 76 / 100; x += Math.max(3, w / 115)) {
        int c = frame.getPixel(x, y);
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        if (r > 155 && r > g * 1.75f && r > b * 1.55f && g < 135) hits++;
        samples++;
      }
    }
    boolean warning = samples > 0 && hits > 12 && hits * 100 > samples;
    warningFrames = warning ? Math.min(3, warningFrames + 1) : 0;
    if (warningFrames >= 1 && SystemClock.uptimeMillis() - lastWarningAt > 900L) {
      lastWarningAt = SystemClock.uptimeMillis();
      warningPreviouslyVisible = true;
      onIncomingAttackDetected();
    }
    if (!warning) warningPreviouslyVisible = false;
  }
  private static final long DODGE_COOLDOWN_MS = 850L;
  private float attackX, attackY, dodgeX, dodgeY;
  private WindowManager windowManager;
  private Button overlayButton;
  private Button dodgeMarker;
  private Button dodgeTestButton;
  private WindowManager.LayoutParams markerParams;
  private int screenW, screenH;
  private String keyX() { return screenW > screenH ? "dodgeLandscapeX" : "dodgePortraitX"; }
  private String keyY() { return screenW > screenH ? "dodgeLandscapeY" : "dodgePortraitY"; }
  private void refreshScreen() {
    DisplayMetrics m = new DisplayMetrics();
    windowManager.getDefaultDisplay().getRealMetrics(m);
    screenW = m.widthPixels;
    screenH = m.heightPixels;
  }
  @Override public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    if (windowManager == null) return;
    refreshScreen();
    if (running) stopCombat();
    if (dodgeMarker != null && markerParams != null) {
      markerParams.x = Math.max(0, Math.round(screenW * getPreferences(MODE_PRIVATE).getFloat(keyX(), 0.90f)) - dodgeMarker.getWidth() / 2);
      markerParams.y = Math.max(0, Math.round(screenH * getPreferences(MODE_PRIVATE).getFloat(keyY(), 0.88f)) - dodgeMarker.getHeight() / 2);
      windowManager.updateViewLayout(dodgeMarker, markerParams);
    }
  }

  private final Runnable loop = new Runnable() {
    @Override public void run() {
      if (!running) return;
      long now = SystemClock.uptimeMillis();
      // Fallback for Diana attacks without reliable red telegraphs.
      if (periodicDodgeEnabled && now >= nextPeriodicDodgeAt && !gestureInFlight) {
        nextPeriodicDodgeAt = now + 2000L;
        onIncomingAttackDetected();
      }
      if (!gestureInFlight && now >= dodgePriorityUntil && now >= nextAttackAt) {
        tap(attackX, attackY);
        nextAttackAt = now + 650L;
      }
      if (running) handler.postDelayed(this, 100L);
    }
  };

  @Override protected void onServiceConnected() {
    super.onServiceConnected();
    instance = this;
    showOverlay();
  }

  private void showOverlay() {
    if (overlayButton != null) return;
    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    overlayButton = new Button(this);
    DisplayMetrics dm = new DisplayMetrics();
    windowManager.getDefaultDisplay().getRealMetrics(dm);
    screenW = dm.widthPixels; screenH = dm.heightPixels;
    overlayButton.setText("BOT START");
    overlayButton.setAllCaps(false);
    overlayButton.setOnClickListener(v -> {
      if (running) {
        stopCombat();
      } else {
        refreshScreen();
        float fx = getPreferences(MODE_PRIVATE).getFloat(keyX(), 0.90f);
        float fy = getPreferences(MODE_PRIVATE).getFloat(keyY(), 0.88f);
        startCombat(screenW * 0.83f, screenH * 0.80f,
                    screenW * fx, screenH * fy);
      }
    });
    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT
    );
    params.gravity = Gravity.TOP | Gravity.LEFT;
    // Place controls at top center, away from the game's top-left minimap.
    params.x = Math.round(screenW * 0.40f);
    params.y = 16;
    try {
      windowManager.addView(overlayButton, params);
      showDodgeMarker();
      showDodgeTestButton();
    } catch (RuntimeException e) {
      overlayButton = null;
    }
  }

  private android.content.SharedPreferences getPreferences(int mode) {
    return getSharedPreferences("combat", mode);
  }

  private void showDodgeMarker() {
    dodgeMarker = new Button(this);
    dodgeMarker.setText("ZIEL");
    dodgeMarker.setTextSize(10);
    dodgeMarker.setMinWidth(0);
    dodgeMarker.setMinimumWidth(0);
    dodgeMarker.setMinHeight(0);
    dodgeMarker.setMinimumHeight(0);
    dodgeMarker.setPadding(6, 2, 6, 2);
    dodgeMarker.setAllCaps(false);
    float fx = getPreferences(MODE_PRIVATE).getFloat(keyX(), 0.90f);
    float fy = getPreferences(MODE_PRIVATE).getFloat(keyY(), 0.88f);
    markerParams = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    markerParams.gravity = Gravity.TOP | Gravity.LEFT;
    markerParams.x = Math.round(screenW * fx) - 25;
    markerParams.y = Math.round(screenH * fy) - 20;
    dodgeMarker.setOnTouchListener(new View.OnTouchListener() {
      float downX, downY;
      int startX, startY;
      @Override public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            downX = event.getRawX(); downY = event.getRawY();
            startX = markerParams.x; startY = markerParams.y;
            return true;
          case MotionEvent.ACTION_MOVE:
            markerParams.x = Math.max(0, Math.min(screenW - dodgeMarker.getWidth(), startX + Math.round(event.getRawX() - downX)));
            markerParams.y = Math.max(0, Math.min(screenH - dodgeMarker.getHeight(), startY + Math.round(event.getRawY() - downY)));
            windowManager.updateViewLayout(dodgeMarker, markerParams);
            return true;
          case MotionEvent.ACTION_UP:
            float x = (markerParams.x + dodgeMarker.getWidth() / 2f) / screenW;
            float y = (markerParams.y + dodgeMarker.getHeight() / 2f) / screenH;
            getPreferences(MODE_PRIVATE).edit().putFloat(keyX(), x).putFloat(keyY(), y).apply();
            return true;
          default: return true;
        }
      }
    });
    windowManager.addView(dodgeMarker, markerParams);
  }

  private void showDodgeTestButton() {
    dodgeTestButton = new Button(this);
    dodgeTestButton.setText("AUSWEICH TEST");
    dodgeTestButton.setAllCaps(false);
    dodgeTestButton.setOnClickListener(v -> {
      if (running) return;
      if (dodgeTestRunning) {
        stopDodgeTest();
        return;
      }
      refreshScreen();
      testDodgeX = dodgeMarker == null ? screenW * 0.90f : markerParams.x + dodgeMarker.getWidth() / 2f;
      testDodgeY = dodgeMarker == null ? screenH * 0.88f : markerParams.y + dodgeMarker.getHeight() / 2f;
      dodgeTestRunning = true;
      if (dodgeMarker != null) dodgeMarker.setVisibility(View.GONE);
      dodgeTestButton.setText("TEST STOP");
      handler.postDelayed(repeatedDodgeTest, 350L);
    });
    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    p.gravity = Gravity.TOP | Gravity.LEFT;
    // Keep test control below the start button, outside the minimap.
    p.x = Math.round(screenW * 0.40f);
    p.y = 110;
    windowManager.addView(dodgeTestButton, p);
  }

  private void stopDodgeTest() {
    dodgeTestRunning = false;
    handler.removeCallbacks(repeatedDodgeTest);
    if (dodgeMarker != null) dodgeMarker.setVisibility(View.VISIBLE);
    if (dodgeTestButton != null) dodgeTestButton.setText("AUSWEICH TEST");
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
  @Override public void onInterrupt() { stopCombat(); }
  @Override public void onDestroy() {
    stopDodgeTest();
    stopCombat();
    if (windowManager != null && dodgeTestButton != null) {
      windowManager.removeView(dodgeTestButton);
      dodgeTestButton = null;
    }
    if (windowManager != null && dodgeMarker != null) {
      windowManager.removeView(dodgeMarker);
      dodgeMarker = null;
    }
    if (windowManager != null && overlayButton != null) {
      windowManager.removeView(overlayButton);
      overlayButton = null;
    }
    if (instance == this) instance = null;
    super.onDestroy();
  }

  public void startCombat(float ax, float ay, float dx, float dy) {
    stopDodgeTest();
    stopCombat();
    attackX = ax; attackY = ay; dodgeX = dx; dodgeY = dy;
    lastDodgeAt = 0L;
    nextPeriodicDodgeAt = SystemClock.uptimeMillis() + 1000L;
    nextAttackAt = 0L;
    dodgePriorityUntil = 0L;
    lastWarningAt = 0L;
    warningFrames = 0;
    warningPreviouslyVisible = false;
    running = true;
    updateOverlay();
    if (dodgeMarker != null) dodgeMarker.setVisibility(View.GONE);
    if (dodgeTestButton != null) dodgeTestButton.setVisibility(View.GONE);
    handler.post(loop);
    scheduleScan();
  }

  public void stopCombat() {
    running = false;
    handler.removeCallbacks(loop);
    handler.removeCallbacks(scanLoop);
    if (dodgeMarker != null) dodgeMarker.setVisibility(View.VISIBLE);
    if (dodgeTestButton != null) dodgeTestButton.setVisibility(View.VISIBLE);
    updateOverlay();
  }

  private void updateOverlay() {
    if (overlayButton != null) overlayButton.setText(running ? "BOT STOP" : "BOT START");
  }

  public boolean isRunning() { return running; }

  /** Called by a future screen-analysis module only after an attack warning is detected. */
  public void onIncomingAttackDetected() {
    if (!running) return;
    long now = android.os.SystemClock.uptimeMillis();
    if (now - lastDodgeAt < DODGE_COOLDOWN_MS || gestureInFlight) return;
    lastDodgeAt = now;
    dodgePriorityUntil = now + 400L;
    nextPeriodicDodgeAt = Math.max(nextPeriodicDodgeAt, now + 2000L);
    handler.removeCallbacks(loop);
    tap(dodgeX, dodgeY);
    handler.postDelayed(loop, 400L);
  }

  private void tapWithFeedback(float x, float y) {
    sendTap(x, y, true);
  }

  private void tap(float x, float y) {
    sendTap(x, y, false);
  }

  private void sendTap(float x, float y, boolean feedback) {
    if (gestureInFlight) {
      if (feedback) Toast.makeText(this, "Geste noch aktiv", Toast.LENGTH_SHORT).show();
      return;
    }
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture = new GestureDescription.Builder()
      .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
    gestureInFlight = true;
    boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
      @Override public void onCompleted(GestureDescription g) {
        gestureInFlight = false;
        if (feedback) Toast.makeText(CombatAccessibilityService.this, "Ausweich-Beruehrung ausgefuehrt", Toast.LENGTH_SHORT).show();
      }
      @Override public void onCancelled(GestureDescription g) {
        gestureInFlight = false;
        if (feedback) Toast.makeText(CombatAccessibilityService.this, "Ausweich-Beruehrung abgebrochen", Toast.LENGTH_SHORT).show();
      }
    }, null);
    if (!accepted) {
      gestureInFlight = false;
      if (feedback) Toast.makeText(this, "Ausweich-Beruehrung nicht angenommen", Toast.LENGTH_SHORT).show();
    }
  }
}
