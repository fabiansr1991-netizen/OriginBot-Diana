package de.originbot.diana;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;

/** Experimental timed taps only. No screen analysis or boss attack recognition. */
public class CombatAccessibilityService extends AccessibilityService {
  static CombatAccessibilityService instance;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean running;
  private int step;
  private float attackX, attackY, dodgeX, dodgeY;
  private WindowManager windowManager;
  private Button overlayButton;
  private Button dodgeMarker;
  private Button dodgeTestButton;
  private WindowManager.LayoutParams markerParams;
  private int screenW, screenH;

  private final Runnable loop = new Runnable() {
    @Override public void run() {
      if (!running) return;
      boolean dodge = (++step % 5 == 0);
      tap(dodge ? dodgeX : attackX, dodge ? dodgeY : attackY);
      if (running) handler.postDelayed(this, dodge ? 1100 : 650);
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
        DisplayMetrics currentMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(currentMetrics);
        screenW = currentMetrics.widthPixels; screenH = currentMetrics.heightPixels;
        float fx = getPreferences(MODE_PRIVATE).getFloat("dodgeX", 0.70f);
        float fy = getPreferences(MODE_PRIVATE).getFloat("dodgeY", 0.80f);
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
    params.gravity = Gravity.TOP | Gravity.END;
    params.x = 12;
    params.y = 150;
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
    dodgeMarker.setText("AUSWEICH\nVERSCHIEBEN");
    dodgeMarker.setAllCaps(false);
    float fx = getPreferences(MODE_PRIVATE).getFloat("dodgeX", 0.70f);
    float fy = getPreferences(MODE_PRIVATE).getFloat("dodgeY", 0.80f);
    markerParams = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    markerParams.gravity = Gravity.TOP | Gravity.LEFT;
    markerParams.x = (int)(screenW * fx) - 75;
    markerParams.y = (int)(screenH * fy) - 55;
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
            getPreferences(MODE_PRIVATE).edit().putFloat("dodgeX", x).putFloat("dodgeY", y).apply();
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
      float fx = getPreferences(MODE_PRIVATE).getFloat("dodgeX", 0.70f);
      float fy = getPreferences(MODE_PRIVATE).getFloat("dodgeY", 0.80f);
      DisplayMetrics metrics = new DisplayMetrics();
      windowManager.getDefaultDisplay().getRealMetrics(metrics);
      handler.postDelayed(() -> {
        tap(metrics.widthPixels * fx, metrics.heightPixels * fy);
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
    p.gravity = Gravity.TOP | Gravity.END;
    p.x = 12;
    p.y = 270;
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
    step = 0;
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

  private void tap(float x, float y) {
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture = new GestureDescription.Builder()
      .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
    dispatchGesture(gesture, null, null);
  }
}
