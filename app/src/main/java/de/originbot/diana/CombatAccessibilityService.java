package de.originbot.diana;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
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
    overlayButton.setText("BOT START");
    overlayButton.setAllCaps(false);
    overlayButton.setOnClickListener(v -> {
      if (running) {
        stopCombat();
      } else {
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        startCombat(dm.widthPixels * 0.83f, dm.heightPixels * 0.80f,
                    dm.widthPixels * 0.70f, dm.heightPixels * 0.80f);
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
    } catch (RuntimeException e) {
      overlayButton = null;
    }
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
  @Override public void onInterrupt() { stopCombat(); }
  @Override public void onDestroy() {
    stopCombat();
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
    handler.post(loop);
  }

  public void stopCombat() {
    running = false;
    handler.removeCallbacks(loop);
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
