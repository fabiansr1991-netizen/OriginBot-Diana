package de.originbot.diana;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

public class CombatAccessibilityService extends AccessibilityService {
  static CombatAccessibilityService instance;
  @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
  @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }
  @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
  @Override public void onInterrupt() {}
  public void tap(float x, float y) {
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture = new GestureDescription.Builder()
      .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
    dispatchGesture(gesture, null, null);
  }
}
