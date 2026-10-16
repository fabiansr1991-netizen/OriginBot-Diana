package de.originbot.diana;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/** Opt-in timed gesture prototype. No visual recognition or boss strategy. */
public class CombatAccessibilityService extends AccessibilityService {
  static CombatAccessibilityService instance;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean running = false;
  private int step = 0;
  private float attackX, attackY, dodgeX, dodgeY;
  private final Runnable loop = new Runnable() {
    @Override public void run() {
      if (!running) return;
      // Every fifth action is a dodge; otherwise an attack.
      boolean dodge = (++step % 5 == 0);
      tap(dodge ? dodgeX : attackX, dodge ? dodgeY : attackY);
      if (running) handler.postDelayed(this, dodge ? 1100 : 650);
    }
  };

  @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
  @Override public void onDestroy() { stopCombat(); if (instance == this) instance = null; super.onDestroy(); }
  @Override public void onInterrupt() { stopCombat(); }
  @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

  public void startCombat(float ax, float ay, float dx, float dy) {
    stopCombat();
    attackX = ax; attackY = ay; dodgeX = dx; dodgeY = dy;
    step = 0;
    running = true;
    handler.post(loop);
  }
  public void stopCombat() {
    running = false;
    handler.removeCallbacks(loop);
  }
  public boolean isRunning() { return running; }

  public void tap(float x, float y) {
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture = new GestureDescription.Builder()
      .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
    dispatchGesture(gesture, null, null);
  }
}
