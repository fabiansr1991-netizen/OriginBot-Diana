package de.originbot.diana;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/** Basis für explizit ausgelöste Testgesten; keine automatische Spielsteuerung. */
public class CombatAccessibilityService extends AccessibilityService {
  @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
  @Override public void onInterrupt() { }

  /** Koordinaten in Bildschirm-Pixeln. Noch nicht an eine UI gekoppelt. */
  public void tap(float x, float y) {
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture = new GestureDescription.Builder()
      .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
    dispatchGesture(gesture, null, null);
  }
}
