package de.originbot.diana;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
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
  private long lastDodgeAt;
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
      tap(attackX, attackY);
      if (running) handler.postDelayed(this, 650);
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
    params.x = 8;
    params.y = 24;
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
    markerParams.x = (int)(screenW * fx) - 25;
    markerParams.y = (int)(screenH * fy) - 20;
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
      if (dodgeMarker != null) dodgeMarker.setVisibility(View.GONE);
      if (dodgeTestButton != null) dodgeTestButton.setVisibility(View.GONE);
      float fx = getPreferences(MODE_PRIVATE).getFloat("dodgeX", 0.90f);
      float fy = getPreferences(MODE_PRIVATE).getFloat("dodgeY", 0.88f);
      refreshScreen();
      final float x = screenW * fx, y = screenH * fy;
      handler.postDelayed(() -> {
        tapWithFeedback(x, y);
        handler.postDelayed(() -> {
          if (!running && dodgeMarker != null) dodgeMarker.setVisibility(View.VISIBLE);
          if (dodgeTestButton != null) dodgeTestButton.setVisibility(View.VISIBLE);
        }, 500);
      }, 200);
    });
    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    p.gravity = Gravity.TOP | Gravity.LEFT;
    p.x = 8;
    p.y = 110;
    windowManager.addView(dodgeTestButton, p);
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
  @Override public void onInterrupt() { stopCombat(); }
  @Override public void onDestroy() {
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
    stopCombat();
    attackX = ax; attackY = ay; dodgeX = dx; dodgeY = dy;
    lastDodgeAt = 0L;
    running = true;
    updateOverlay();
    if (dodgeMarker != null) dodgeMarker.setVisibility(View.GONE);
    if (dodgeTestButton != null) dodgeTestButton.setVisibility(View.GONE);
    handler.post(loop);
  }

  public void stopCombat() {
    running = false;
    handler.removeCallbacks(loop);
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
    if (now - lastDodgeAt < DODGE_COOLDOWN_MS) return;
    lastDodgeAt = now;
    handler.removeCallbacks(loop);
    tap(dodgeX, dodgeY);
    handler.postDelayed(loop, 350);
  }

  private void tapWithFeedback(float x, float y) {
    sendTap(x, y, true);
  }

  private void tap(float x, float y) {
    sendTap(x, y, false);
  }

  private void sendTap(float x, float y, boolean feedback) {
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture = new GestureDescription.Builder()
      .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
    boolean accepted = dispatchGesture(gesture, feedback ? new GestureResultCallback() {
      @Override public void onCompleted(GestureDescription g) {
        Toast.makeText(CombatAccessibilityService.this, "Ausweich-Beruehrung ausgefuehrt", Toast.LENGTH_SHORT).show();
      }
      @Override public void onCancelled(GestureDescription g) {
        Toast.makeText(CombatAccessibilityService.this, "Ausweich-Beruehrung abgebrochen", Toast.LENGTH_SHORT).show();
      }
    } : null, null);
    if (feedback && !accepted) Toast.makeText(this, "Ausweich-Beruehrung nicht angenommen", Toast.LENGTH_SHORT).show();
  }
}
