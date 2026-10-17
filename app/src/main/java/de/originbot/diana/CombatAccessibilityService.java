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
  private boolean dodgeQueued;
  private boolean dodgeLeftNext = true;
  private float dangerDirectionX = 0f, dangerDirectionY = -1f;
  private float joystickX, joystickY;
  private Button joystickMarker;
  private WindowManager.LayoutParams joystickMarkerParams;
  private boolean periodicDodgeEnabled = true;
  private boolean survivalMode = true;
  private Button survivalButton;
  private Button rotationButton;
  private Button calibrationTestButton;
  private int calibrationTestSlot = 0;
  private final Button[] skillMarkers = new Button[3];
  private final WindowManager.LayoutParams[] skillMarkerParams = new WindowManager.LayoutParams[3];
  private final float[] skillX = {0.77f, 0.85f, 0.92f};
  private final float[] skillY = {0.67f, 0.64f, 0.70f};
  private int skillStep = 0;
  private final long[] skillReadyAt = new long[3];
  private static final long SKILL_RETRY_MS = 1800L;
  private long nextHeroSwitchAt = 0L;
  private long nextCameraSweepAt = 0L;
  private long nextTargetAttemptAt = 0L;
  private static final float TARGET_X = 438f / 1536f;
  private static final float TARGET_Y = 626f / 709f;
  private long lastCombatActionAt = 0L;
  private boolean cameraSweepRight = true;
  private long nextUltimateAttemptAt = 0L;
  private float ultimateX = 1061f / 1536f;
  private float ultimateY = 539f / 709f;
  private long lastThreatAt = 0L;
  private long threatStartedAt = 0L;
  private long lastCounterAt = 0L;
  private boolean threatActive = false;
  private int clearThreatFrames = 0;
  private static final long REACTION_DELAY_MS = 45L;
  private static final long COUNTER_DELAY_MS = 330L;
  private boolean rotationEnabled = false;
  private boolean autoCalibrating = false;
  private Button autoCalibrationButton;
  private int rotationSlot = 0;
  private long nextRotationAt;
  private final float[] heroSlotX = {0.93f, 0.93f, 0.93f, 0.93f};
  private final float[] heroSlotY = {0.24f, 0.34f, 0.44f, 0.54f};
  private final Button[] heroMarkers = new Button[4];
  private final WindowManager.LayoutParams[] heroMarkerParams = new WindowManager.LayoutParams[4];
  private Button attackMarker;
  private WindowManager.LayoutParams attackMarkerParams;
  private boolean rotationCalibrated() {
    for (int i = 0; i < 4; i++)
      if (!getPreferences(MODE_PRIVATE).getBoolean("heroSet" + i + (screenW > screenH ? "L" : "P"), false)) return false;
    return true;
  }
  private String heroKey(int i, String axis) { return "hero" + i + axis + (screenW > screenH ? "L" : "P"); }
  private void loadHeroSlots() {
    for (int i = 0; i < 4; i++) {
      heroSlotX[i] = getPreferences(MODE_PRIVATE).getFloat(heroKey(i, "X"), 0.93f);
      heroSlotY[i] = getPreferences(MODE_PRIVATE).getFloat(heroKey(i, "Y"), 0.24f + i * 0.10f);
    }
  }
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
    if (running && Build.VERSION.SDK_INT >= 30) handler.postDelayed(scanLoop, 65);
  }
  private void analyzeFrame(Bitmap frame) {
    if (!running) return;
    int w = frame.getWidth(), h = frame.getHeight();
    int hits = 0, samples = 0;
    float weightedX = 0f, weightedY = 0f;
    // Central battlefield only: exclude red HP bars and action buttons at the edges.
    for (int y = h * 38 / 100; y < h * 82 / 100; y += Math.max(3, h / 85)) {
      for (int x = w * 23 / 100; x < w * 76 / 100; x += Math.max(3, w / 115)) {
        int c = frame.getPixel(x, y);
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        boolean redWarning = r > 155 && r > g * 1.75f && r > b * 1.55f && g < 135;
        boolean blueWarning = b > 155 && b > r * 1.45f && b > g * 1.15f && r < 145;
        if (redWarning || blueWarning) { hits++; weightedX += x; weightedY += y; }
        samples++;
      }
    }
    boolean warning = samples > 0 && hits > 12 && hits * 100 > samples;
    warningFrames = warning ? Math.min(3, warningFrames + 1) : 0;
    if (warning) {
      // Move away from the warning centroid, with a fallback if it overlaps the hero.
      float cx = weightedX / Math.max(1, hits), cy = weightedY / Math.max(1, hits);
      float dx = w * 0.50f - cx, dy = h * 0.66f - cy;
      float magnitude = (float)Math.hypot(dx, dy);
      if (magnitude > w * 0.035f) { dangerDirectionX = dx / magnitude; dangerDirectionY = dy / magnitude; }
      else { dangerDirectionX = dodgeLeftNext ? -1f : 1f; dangerDirectionY = -0.25f; dodgeLeftNext = !dodgeLeftNext; }
      clearThreatFrames = 0;
      if (!threatActive) {
        threatActive = true;
        threatStartedAt = SystemClock.uptimeMillis();
      }
    } else {
      clearThreatFrames++;
      if (clearThreatFrames >= 2) threatActive = false;
    }
    if (warningFrames >= 1 && SystemClock.uptimeMillis() - lastWarningAt > 900L) {
      lastThreatAt = SystemClock.uptimeMillis();
      lastWarningAt = SystemClock.uptimeMillis();
      warningPreviouslyVisible = true;
      onIncomingAttackDetected();
    }
    if (!warning) warningPreviouslyVisible = false;
  }
  private static final long DODGE_COOLDOWN_MS = 480L;
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
    loadSkills();
    for (int i = 0; i < 3; i++) {
      if (skillMarkers[i] != null) {
        skillMarkerParams[i].x = Math.round(screenW * skillX[i]) - skillMarkers[i].getWidth()/2;
        skillMarkerParams[i].y = Math.round(screenH * skillY[i]) - skillMarkers[i].getHeight()/2;
        windowManager.updateViewLayout(skillMarkers[i], skillMarkerParams[i]);
      }
    }
    loadHeroSlots();
    for (int i = 0; i < 4; i++) {
      if (heroMarkers[i] != null) {
        heroMarkerParams[i].x = Math.round(screenW * heroSlotX[i]) - heroMarkers[i].getWidth() / 2;
        heroMarkerParams[i].y = Math.round(screenH * heroSlotY[i]) - heroMarkers[i].getHeight() / 2;
        windowManager.updateViewLayout(heroMarkers[i], heroMarkerParams[i]);
      }
    }
    if (attackMarker != null) {
      String suffix = screenW > screenH ? "L" : "P";
      attackMarkerParams.x = Math.round(screenW * getPreferences(MODE_PRIVATE).getFloat("attackX" + suffix, 0.83f)) - attackMarker.getWidth() / 2;
      attackMarkerParams.y = Math.round(screenH * getPreferences(MODE_PRIVATE).getFloat("attackY" + suffix, 0.80f)) - attackMarker.getHeight() / 2;
      windowManager.updateViewLayout(attackMarker, attackMarkerParams);
    }
    if (joystickMarker != null && joystickMarkerParams != null) {
      joystickMarkerParams.x = Math.round(screenW * getPreferences(MODE_PRIVATE).getFloat("joystickX", 0.14f)) - joystickMarker.getWidth() / 2;
      joystickMarkerParams.y = Math.round(screenH * getPreferences(MODE_PRIVATE).getFloat("joystickY", 0.78f)) - joystickMarker.getHeight() / 2;
      windowManager.updateViewLayout(joystickMarker, joystickMarkerParams);
    }
    if (dodgeMarker != null && markerParams != null) {
      markerParams.x = Math.max(0, Math.round(screenW * getPreferences(MODE_PRIVATE).getFloat(keyX(), 0.90f)) - dodgeMarker.getWidth() / 2);
      markerParams.y = Math.max(0, Math.round(screenH * getPreferences(MODE_PRIVATE).getFloat(keyY(), 0.88f)) - dodgeMarker.getHeight() / 2);
      windowManager.updateViewLayout(dodgeMarker, markerParams);
    }
  }

  // Each cycle uses all three mapped skills, one basic attack, then changes hero.
  // Android gestures are serialized to prevent dropped inputs.
  private final Runnable loop = new Runnable() {
    @Override public void run() {
      if (!running) return;
      long now = SystemClock.uptimeMillis();
      // Do not spam dodge in safe moments. Screenshot threat detection triggers it separately.
      if (screenW > screenH && !gestureInFlight && !dodgeQueued && !threatActive
          && now >= dodgePriorityUntil && now >= nextTargetAttemptAt) {
        // Experimental target reticle tap; screenshot does not confirm game behavior.
        tap(screenW * TARGET_X, screenH * TARGET_Y);
        nextTargetAttemptAt = now + 5000L;
        nextAttackAt = Math.max(nextAttackAt, now + 250L);
      }
      if (!gestureInFlight && !dodgeQueued && now >= dodgePriorityUntil && now >= nextCameraSweepAt && !threatActive) {
        cameraSweep();
        nextCameraSweepAt = now + 1600L;
        nextAttackAt = Math.max(nextAttackAt, now + 280L);
      }
      if (!gestureInFlight && !dodgeQueued && now >= dodgePriorityUntil && now >= nextAttackAt) {
        // Limit skill retries to avoid hammering buttons during cooldowns.
        int readySkill = -1;
        for (int offset = 0; offset < 3; offset++) {
          int index = (skillStep + offset) % 3;
          if (now >= skillReadyAt[index]) { readySkill = index; break; }
        }
        if (rotationEnabled && now >= nextHeroSwitchAt && readySkill < 0) {
          // Rotation must take priority even while abilities keep becoming ready.
          tap(screenW * heroSlotX[rotationSlot], screenH * heroSlotY[rotationSlot]);
          rotationSlot = (rotationSlot + 1) % 3;
          nextHeroSwitchAt = now + 1800L;
          for (int i = 0; i < 3; i++) skillReadyAt[i] = now + 500L;
          nextAttackAt = now + 570L;
        } else if (screenW > screenH && now >= nextUltimateAttemptAt) {
          // Screenshot-derived glowing ultimate icon. Attempt sparingly; availability not yet verified.
          tap(screenW * ultimateX, screenH * ultimateY);
          nextUltimateAttemptAt = now + 2200L;
          nextAttackAt = now + 260L;
        } else if (readySkill >= 0) {
          tap(screenW * skillX[readySkill], screenH * skillY[readySkill]);
          skillReadyAt[readySkill] = now + SKILL_RETRY_MS;
          skillStep = (readySkill + 1) % 3;
          nextAttackAt = now + 175L;

        } else {
          tap(attackX, attackY);
          nextAttackAt = now + 190L;
        }
      }
      if (running) handler.postDelayed(this, 20L);
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
    overlayButton.setText("AUTO START");
    overlayButton.setAllCaps(false);
    overlayButton.setAlpha(0.45f);
    overlayButton.setOnClickListener(v -> {
      if (running) {
        stopCombat();
      } else {
        refreshScreen();
        float fx = getPreferences(MODE_PRIVATE).getFloat(keyX(), 0.90f);
        float fy = getPreferences(MODE_PRIVATE).getFloat(keyY(), 0.88f);
        startCombat(screenW * getPreferences(MODE_PRIVATE).getFloat("attackX" + (screenW > screenH ? "L" : "P"), 0.83f), screenH * getPreferences(MODE_PRIVATE).getFloat("attackY" + (screenW > screenH ? "L" : "P"), 0.80f),
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
      // Single-control mode: no calibration markers or secondary buttons.
      // Previously saved calibration values remain available to the combat loop.
      overlayButton.setTextSize(12);
      overlayButton.setMinWidth(0);
      overlayButton.setMinimumWidth(0);
      overlayButton.setMinHeight(0);
      overlayButton.setMinimumHeight(0);
      overlayButton.setPadding(12, 5, 12, 5);
      overlayButton.setAlpha(0.65f);
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
    dodgeMarker.setTextSize(14);
    dodgeMarker.setMinWidth(0);
    dodgeMarker.setMinimumWidth(0);
    dodgeMarker.setMinHeight(0);
    dodgeMarker.setMinimumHeight(0);
    dodgeMarker.setPadding(0, 0, 0, 0);
    dodgeMarker.setGravity(Gravity.CENTER);
    dodgeMarker.setAllCaps(false);
    dodgeMarker.setAlpha(0.8f);
    float fx = getPreferences(MODE_PRIVATE).getFloat(keyX(), 0.90f);
    float fy = getPreferences(MODE_PRIVATE).getFloat(keyY(), 0.88f);
    markerParams = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    markerParams.gravity = Gravity.TOP | Gravity.LEFT;
    int dodgeDiameter = Math.round(Math.min(screenW, screenH) * 0.105f);
    markerParams.width = dodgeDiameter;
    markerParams.height = dodgeDiameter;
    markerParams.x = Math.round(screenW * fx) - dodgeDiameter / 2;
    markerParams.y = Math.round(screenH * fy) - dodgeDiameter / 2;
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

  private void showJoystickMarker() {
    joystickMarker = new Button(this);
    joystickMarker.setText("STICK");
    joystickMarker.setTextSize(16);
    joystickMarker.setAllCaps(false);
    joystickMarker.setAlpha(0.70f);
    joystickMarker.setPadding(0, 0, 0, 0);
    joystickMarker.setGravity(Gravity.CENTER);
    joystickMarker.setTextColor(Color.WHITE);
    joystickMarkerParams = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    joystickMarkerParams.gravity = Gravity.TOP | Gravity.LEFT;
    int stickDiameter = Math.round(Math.min(screenW, screenH) * 0.25f);
    joystickMarkerParams.width = stickDiameter;
    joystickMarkerParams.height = stickDiameter;
    joystickMarkerParams.x = Math.round(screenW * getPreferences(MODE_PRIVATE).getFloat("joystickX", 0.14f)) - stickDiameter / 2;
    joystickMarkerParams.y = Math.round(screenH * getPreferences(MODE_PRIVATE).getFloat("joystickY", 0.78f)) - stickDiameter / 2;
    joystickMarker.setOnTouchListener(new View.OnTouchListener() {
      float downX, downY;
      int startX, startY;
      @Override public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            downX = event.getRawX(); downY = event.getRawY();
            startX = joystickMarkerParams.x; startY = joystickMarkerParams.y;
            return true;
          case MotionEvent.ACTION_MOVE:
            joystickMarkerParams.x = Math.max(0, Math.min(screenW - joystickMarker.getWidth(), startX + Math.round(event.getRawX() - downX)));
            joystickMarkerParams.y = Math.max(0, Math.min(screenH - joystickMarker.getHeight(), startY + Math.round(event.getRawY() - downY)));
            windowManager.updateViewLayout(joystickMarker, joystickMarkerParams);
            return true;
          case MotionEvent.ACTION_UP:
            getPreferences(MODE_PRIVATE).edit()
              .putFloat("joystickX", (joystickMarkerParams.x + joystickMarker.getWidth() / 2f) / screenW)
              .putFloat("joystickY", (joystickMarkerParams.y + joystickMarker.getHeight() / 2f) / screenH)
              .apply();
            return true;
          default: return true;
        }
      }
    });
    windowManager.addView(joystickMarker, joystickMarkerParams);
  }

  private WindowManager.LayoutParams calibrationParams(float x, float y) {
    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    p.gravity = Gravity.TOP | Gravity.LEFT;
    int diameter = Math.round(Math.min(screenW, screenH) * 0.105f);
    p.width = diameter;
    p.height = diameter;
    p.x = Math.round(screenW * x) - diameter / 2;
    p.y = Math.round(screenH * y) - diameter / 2;
    return p;
  }

  private void attachDrag(Button b, WindowManager.LayoutParams p, Runnable save) {
    b.setAlpha(0.85f);
    b.setTextSize(13);
    b.setAllCaps(false);
    b.setMinWidth(0); b.setMinimumWidth(0);
    b.setMinHeight(0); b.setMinimumHeight(0);
    b.setPadding(0, 0, 0, 0);
    b.setGravity(Gravity.CENTER);
    b.setTextColor(Color.WHITE);
    b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.argb(110, 35, 35, 55)));
    b.setOnTouchListener(new View.OnTouchListener() {
      float downX, downY;
      int startX, startY;
      @Override public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            downX = event.getRawX(); downY = event.getRawY();
            startX = p.x; startY = p.y;
            return true;
          case MotionEvent.ACTION_MOVE:
            p.x = Math.max(0, Math.min(screenW - b.getWidth(), startX + Math.round(event.getRawX() - downX)));
            p.y = Math.max(0, Math.min(screenH - b.getHeight(), startY + Math.round(event.getRawY() - downY)));
            windowManager.updateViewLayout(b, p);
            return true;
          case MotionEvent.ACTION_UP:
            save.run();
            return true;
          default: return true;
        }
      }
    });
    windowManager.addView(b, p);
  }

  private void showHeroMarkers() {
    loadHeroSlots();
    for (int i = 0; i < 4; i++) {
      final int slot = i;
      Button b = new Button(this);
      b.setText("H" + (i + 1));
      heroMarkers[i] = b;
      WindowManager.LayoutParams p = calibrationParams(heroSlotX[i], heroSlotY[i]);
      heroMarkerParams[i] = p;
      attachDrag(b, p, () -> {
        float x = (p.x + b.getWidth() / 2f) / screenW;
        float y = (p.y + b.getHeight() / 2f) / screenH;
        heroSlotX[slot] = x; heroSlotY[slot] = y;
        getPreferences(MODE_PRIVATE).edit()
          .putFloat(heroKey(slot, "X"), x).putFloat(heroKey(slot, "Y"), y)
          .putBoolean("heroSet" + slot + (screenW > screenH ? "L" : "P"), true).apply();
        Toast.makeText(this, "H" + (slot + 1) + " gespeichert", Toast.LENGTH_SHORT).show();
      });
    }
  }

  private void showAttackMarker() {
    String suffix = screenW > screenH ? "L" : "P";
    Button b = new Button(this);
    b.setText("ANGRIFF");
    attackMarker = b;
    WindowManager.LayoutParams p = calibrationParams(
      getPreferences(MODE_PRIVATE).getFloat("attackX" + suffix, 0.83f),
      getPreferences(MODE_PRIVATE).getFloat("attackY" + suffix, 0.80f));
    attackMarkerParams = p;
    attachDrag(b, p, () -> {
      getPreferences(MODE_PRIVATE).edit()
        .putFloat("attackX" + suffix, (p.x + b.getWidth() / 2f) / screenW)
        .putFloat("attackY" + suffix, (p.y + b.getHeight() / 2f) / screenH).apply();
      Toast.makeText(this, "Angriff gespeichert", Toast.LENGTH_SHORT).show();
    });
  }

  private void compactControls() {
    Button[] controls = {overlayButton, dodgeTestButton, survivalButton, rotationButton, calibrationTestButton, autoCalibrationButton};
    for (Button b : controls) {
      if (b == null) continue;
      b.setTextSize(12);
      b.setMinHeight(0); b.setMinimumHeight(0);
      b.setMinWidth(0); b.setMinimumWidth(0);
      b.setPadding(4, 1, 4, 1);
      b.setAlpha(0.75f);
    }
  }
  private String skillKey(int i, String axis) { return "skill" + i + axis + (screenW > screenH ? "L" : "P"); }
  private void loadSkills() {
    for (int i = 0; i < 3; i++) {
      skillX[i] = getPreferences(MODE_PRIVATE).getFloat(skillKey(i,"X"), skillX[i]);
      skillY[i] = getPreferences(MODE_PRIVATE).getFloat(skillKey(i,"Y"), skillY[i]);
    }
  }
  private void showSkillMarkers() {
    loadSkills();
    for (int i = 0; i < 3; i++) {
      final int slot = i;
      Button b = new Button(this);
      b.setText("F" + (i + 1));
      b.setMinWidth(0); b.setMinimumWidth(0);
      b.setMinHeight(0); b.setMinimumHeight(0);
      skillMarkers[i] = b;
      WindowManager.LayoutParams p = calibrationParams(skillX[i], skillY[i]);
      skillMarkerParams[i] = p;
      attachDrag(b, p, () -> {
        skillX[slot] = (p.x + b.getWidth()/2f)/screenW;
        skillY[slot] = (p.y + b.getHeight()/2f)/screenH;
        getPreferences(MODE_PRIVATE).edit()
          .putFloat(skillKey(slot,"X"), skillX[slot])
          .putFloat(skillKey(slot,"Y"), skillY[slot]).apply();
        Toast.makeText(this, "F" + (slot+1) + " gespeichert", Toast.LENGTH_SHORT).show();
      });
    }
  }

  private void showAutoCalibrationButton() {
    autoCalibrationButton = new Button(this);
    autoCalibrationButton.setText("AUTO SUCHEN");
    autoCalibrationButton.setTextSize(10);
    autoCalibrationButton.setAllCaps(false);
    autoCalibrationButton.setAlpha(0.45f);
    autoCalibrationButton.setOnClickListener(v -> {
      if (running || screenshotPending || Build.VERSION.SDK_INT < 30) {
        Toast.makeText(this, "Bot stoppen / Screenshot nicht bereit", Toast.LENGTH_SHORT).show();
        return;
      }
      autoCalibrating = true;
      autoCalibrationButton.setText("SUCHT...");
      takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
        @Override public void onSuccess(ScreenshotResult result) {
          Bitmap frame = null, readable = null;
          try {
            frame = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
            if (frame != null) readable = frame.copy(Bitmap.Config.ARGB_8888, false);
            if (readable != null) detectHeroSlots(readable);
            else Toast.makeText(CombatAccessibilityService.this, "Bild nicht lesbar", Toast.LENGTH_LONG).show();
          } catch (RuntimeException e) {
            Toast.makeText(CombatAccessibilityService.this, "Erkennung fehlgeschlagen", Toast.LENGTH_LONG).show();
          } finally {
            if (readable != null) readable.recycle();
            if (frame != null) frame.recycle();
            result.getHardwareBuffer().close();
            autoCalibrating = false;
            autoCalibrationButton.setText("AUTO SUCHEN");
          }
        }
        @Override public void onFailure(int errorCode) {
          autoCalibrating = false;
          autoCalibrationButton.setText("AUTO SUCHEN");
          Toast.makeText(CombatAccessibilityService.this, "Screenshot nicht erlaubt", Toast.LENGTH_LONG).show();
        }
      });
    });
    WindowManager.LayoutParams p = calibrationParams(0.40f, 0.64f);
    p.x = Math.round(screenW * 0.40f);
    p.y = 470;
    windowManager.addView(autoCalibrationButton, p);
  }

  // Conservative heuristic: find four similarly sized bright circular portrait outlines
  // in the rightmost 30% of a landscape screenshot. No coordinates are saved on weak matches.
  private void detectHeroSlots(Bitmap image) {
    int w = image.getWidth(), h = image.getHeight();
    if (w <= h) {
      Toast.makeText(this, "Automatik nur im Querformat", Toast.LENGTH_LONG).show();
      return;
    }
    java.util.ArrayList<int[]> candidates = new java.util.ArrayList<>();
    int radius = Math.max(10, Math.round(h * 0.035f));
    int step = Math.max(5, radius / 3);
    for (int y = radius * 2; y < h - radius * 2; y += step) {
      for (int x = w * 72 / 100; x < w - radius * 2; x += step) {
        int bright = 0, tested = 0;
        for (int a = 0; a < 12; a++) {
          double angle = a * Math.PI / 6;
          int px = x + (int)(radius * Math.cos(angle));
          int py = y + (int)(radius * Math.sin(angle));
          int c = image.getPixel(px, py);
          int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
          int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
          if (max > 175 && max - min < 100) bright++;
          tested++;
        }
        if (bright < 9) continue;
        boolean nearby = false;
        for (int[] p : candidates) if (Math.abs(p[0] - x) < radius && Math.abs(p[1] - y) < radius) { nearby = true; break; }
        if (!nearby) candidates.add(new int[]{x,y});
      }
    }
    // Ambiguous visual matches must never silently overwrite calibration.
    if (candidates.size() != 4) {
      Toast.makeText(this, "Keine eindeutigen 4 Charaktertasten erkannt (" + candidates.size() + "). Manuell H1-H4 setzen.", Toast.LENGTH_LONG).show();
      return;
    }
    java.util.Collections.sort(candidates, (a,b) -> Integer.compare(a[1], b[1]));
    int minGap = Math.max(radius * 2, h / 14);
    for (int i = 1; i < 4; i++) {
      if (candidates.get(i)[1] - candidates.get(i-1)[1] < minGap ||
          Math.abs(candidates.get(i)[0] - candidates.get(0)[0]) > radius) {
        Toast.makeText(this, "Treffer nicht plausibel. H1-H4 manuell setzen.", Toast.LENGTH_LONG).show();
        return;
      }
    }
    android.content.SharedPreferences.Editor edit = getPreferences(MODE_PRIVATE).edit();
    for (int i = 0; i < 4; i++) {
      float x = candidates.get(i)[0] / (float)w, y = candidates.get(i)[1] / (float)h;
      heroSlotX[i] = x; heroSlotY[i] = y;
      edit.putFloat(heroKey(i, "X"), x).putFloat(heroKey(i, "Y"), y)
          .putBoolean("heroSet" + i + "L", true);
      if (heroMarkers[i] != null) {
        heroMarkerParams[i].x = Math.round(screenW * x) - heroMarkers[i].getWidth() / 2;
        heroMarkerParams[i].y = Math.round(screenH * y) - heroMarkers[i].getHeight() / 2;
        windowManager.updateViewLayout(heroMarkers[i], heroMarkerParams[i]);
      }
    }
    edit.apply();
    Toast.makeText(this, "Vier Kandidaten gefunden: H1-H4 TEST vor Rotation pruefen", Toast.LENGTH_LONG).show();
  }

  private void showCalibrationTestButton() {
    calibrationTestButton = new Button(this);
    calibrationTestButton.setText("H1 TEST");
    calibrationTestButton.setTextSize(10);
    calibrationTestButton.setAllCaps(false);
    calibrationTestButton.setAlpha(0.45f);
    calibrationTestButton.setOnClickListener(v -> {
      if (running) { Toast.makeText(this, "Bot erst stoppen", Toast.LENGTH_SHORT).show(); return; }
      loadHeroSlots();
      int slot = calibrationTestSlot;
      if (!getPreferences(MODE_PRIVATE).getBoolean("heroSet" + slot + (screenW > screenH ? "L" : "P"), false)) {
        Toast.makeText(this, "H" + (slot + 1) + " zuerst verschieben", Toast.LENGTH_LONG).show();
        return;
      }
      tapWithFeedback(screenW * heroSlotX[slot], screenH * heroSlotY[slot]);
      calibrationTestSlot = (calibrationTestSlot + 1) % 4;
      calibrationTestButton.setText("H" + (calibrationTestSlot + 1) + " TEST");
    });
    WindowManager.LayoutParams p = calibrationParams(0.40f, 0.55f);
    p.x = Math.round(screenW * 0.40f);
    p.y = 380;
    windowManager.addView(calibrationTestButton, p);
  }

  private void showRotationButton() {
    rotationButton = new Button(this);
    rotationButton.setAllCaps(false);
    rotationButton.setAlpha(0.45f);
    rotationButton.setText("ROTATION AUS");
    rotationButton.setOnClickListener(v -> {
      if (!rotationEnabled && !rotationCalibrated()) {
        Toast.makeText(this, "Erst H1-H4 an die Charaktertasten ziehen", Toast.LENGTH_LONG).show();
        return;
      }
      rotationEnabled = !rotationEnabled;
      rotationSlot = 0;
      nextRotationAt = SystemClock.uptimeMillis() + 6500L;
      rotationButton.setText(rotationEnabled ? "ROTATION AN" : "ROTATION AUS");
      Toast.makeText(this, rotationEnabled
        ? "Testrotation: Slotpositionen vor Einsatz pruefen!"
        : "Rotation deaktiviert", Toast.LENGTH_LONG).show();
    });
    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    p.gravity = Gravity.TOP | Gravity.LEFT;
    p.x = Math.round(screenW * 0.40f);
    p.y = 290;
    windowManager.addView(rotationButton, p);
  }

  private void showSurvivalButton() {
    survivalButton = new Button(this);
    survivalButton.setAllCaps(false);
    survivalButton.setAlpha(0.45f);
    survivalButton.setText("SCHUTZ AN");
    survivalButton.setOnClickListener(v -> {
      survivalMode = !survivalMode;
      survivalButton.setText(survivalMode ? "SCHUTZ AN" : "SCHUTZ AUS");
      nextPeriodicDodgeAt = SystemClock.uptimeMillis() + 1000L;
    });
    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT);
    p.gravity = Gravity.TOP | Gravity.LEFT;
    p.x = Math.round(screenW * 0.40f);
    p.y = 200;
    windowManager.addView(survivalButton, p);
  }

  private void showDodgeTestButton() {
    dodgeTestButton = new Button(this);
    dodgeTestButton.setText("AUSWEICH TEST");
    dodgeTestButton.setAllCaps(false);
    dodgeTestButton.setAlpha(0.45f);
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
    if (windowManager != null) {
      for (int i = 0; i < 3; i++) {
        if (skillMarkers[i] != null) { windowManager.removeView(skillMarkers[i]); skillMarkers[i] = null; }
      }
      for (int i = 0; i < 4; i++) {
        if (heroMarkers[i] != null) { windowManager.removeView(heroMarkers[i]); heroMarkers[i] = null; }
      }
      if (attackMarker != null) { windowManager.removeView(attackMarker); attackMarker = null; }
    }
    if (windowManager != null && autoCalibrationButton != null) {
      windowManager.removeView(autoCalibrationButton);
      autoCalibrationButton = null;
    }
    if (windowManager != null && calibrationTestButton != null) {
      windowManager.removeView(calibrationTestButton);
      calibrationTestButton = null;
    }
    if (windowManager != null && rotationButton != null) {
      windowManager.removeView(rotationButton);
      rotationButton = null;
    }
    if (windowManager != null && survivalButton != null) {
      windowManager.removeView(survivalButton);
      survivalButton = null;
    }
    if (windowManager != null && dodgeTestButton != null) {
      windowManager.removeView(dodgeTestButton);
      dodgeTestButton = null;
    }
    if (windowManager != null && joystickMarker != null) {
      windowManager.removeView(joystickMarker);
      joystickMarker = null;
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
    joystickX = screenW * getPreferences(MODE_PRIVATE).getFloat("joystickX", 0.14f);
    joystickY = screenH * getPreferences(MODE_PRIVATE).getFloat("joystickY", 0.78f);
    dodgeLeftNext = true;
    dodgeQueued = false;
    lastDodgeAt = 0L;
    rotationSlot = 0;
    skillStep = 0;
    for (int i = 0; i < 3; i++) skillReadyAt[i] = 0L;
    nextHeroSwitchAt = SystemClock.uptimeMillis() + 3600L;
    nextCameraSweepAt = SystemClock.uptimeMillis() + 2100L;
    nextTargetAttemptAt = SystemClock.uptimeMillis() + 2200L;
    nextUltimateAttemptAt = SystemClock.uptimeMillis() + 1600L;
    rotationEnabled = true;
    periodicDodgeEnabled = true;
    survivalMode = true;
    loadHeroSlots();
    // Three visible swap portraits; active hero has no fourth swap button.
    if (screenW > screenH) {
      heroSlotX[0] = 1368f / 1536f; heroSlotY[0] = 157f / 709f;
      heroSlotX[1] = 1368f / 1536f; heroSlotY[1] = 255f / 709f;
      heroSlotX[2] = 1368f / 1536f; heroSlotY[2] = 354f / 709f;
    }
    loadSkills();
    nextRotationAt = SystemClock.uptimeMillis() + 6500L;
    nextPeriodicDodgeAt = SystemClock.uptimeMillis() + 1000L;
    nextAttackAt = 0L;
    dodgePriorityUntil = 0L;
    lastWarningAt = 0L;
    threatActive = false;
    clearThreatFrames = 0;
    warningFrames = 0;
    warningPreviouslyVisible = false;
    running = true;
    updateOverlay();
    if (dodgeMarker != null) dodgeMarker.setVisibility(View.GONE);
    if (joystickMarker != null) joystickMarker.setVisibility(View.GONE);
    if (dodgeTestButton != null) dodgeTestButton.setVisibility(View.GONE);
    if (calibrationTestButton != null) calibrationTestButton.setVisibility(View.GONE);
    if (autoCalibrationButton != null) autoCalibrationButton.setVisibility(View.GONE);
    for (Button b : heroMarkers) if (b != null) b.setVisibility(View.GONE);
    for (Button b : skillMarkers) if (b != null) b.setVisibility(View.GONE);
    if (attackMarker != null) attackMarker.setVisibility(View.GONE);
    handler.post(loop);
    scheduleScan();
  }

  public void stopCombat() {
    running = false;
    dodgeQueued = false;
    handler.removeCallbacks(loop);
    handler.removeCallbacks(scanLoop);
    if (dodgeMarker != null) dodgeMarker.setVisibility(View.VISIBLE);
    if (joystickMarker != null) joystickMarker.setVisibility(View.VISIBLE);
    if (dodgeTestButton != null) dodgeTestButton.setVisibility(View.VISIBLE);
    if (calibrationTestButton != null) calibrationTestButton.setVisibility(View.VISIBLE);
    if (autoCalibrationButton != null) autoCalibrationButton.setVisibility(View.VISIBLE);
    for (Button b : heroMarkers) if (b != null) b.setVisibility(View.VISIBLE);
    for (Button b : skillMarkers) if (b != null) b.setVisibility(View.VISIBLE);
    if (attackMarker != null) attackMarker.setVisibility(View.VISIBLE);
    updateOverlay();
  }

  private void updateOverlay() {
    if (overlayButton != null) overlayButton.setText(running ? "AUTO STOP" : "AUTO START");
  }

  public boolean isRunning() { return running; }

  /** Called by a future screen-analysis module only after an attack warning is detected. */
  public void onIncomingAttackDetected() {
    if (!running) return;
    long now = android.os.SystemClock.uptimeMillis();
    if (now - lastDodgeAt < DODGE_COOLDOWN_MS) return;
    if (gestureInFlight) { dodgeQueued = true; dodgePriorityUntil = now + 300L; return; }
    lastDodgeAt = now;
    dodgePriorityUntil = now + 260L;
    // Do not attack during the dodge recovery window.
    if (false && now - lastCounterAt > 1000L) {
      lastCounterAt = now;
      handler.postDelayed(() -> {
        if (running && !gestureInFlight && SystemClock.uptimeMillis() >= dodgePriorityUntil - 100L) {
          tap(attackX, attackY);
          nextAttackAt = SystemClock.uptimeMillis() + 350L;
        }
      }, COUNTER_DELAY_MS);
    }
    nextPeriodicDodgeAt = Math.max(nextPeriodicDodgeAt, now + 2000L);
    handler.removeCallbacks(loop);
    directedDodge();
    handler.postDelayed(loop, 150L);
  }

  /** Prefer a direct dodge tap: some games ignore simultaneous accessibility strokes. */
  // Short swipe through the clear upper-right play area, away from action controls.
  private void cameraSweep() {
    if (gestureInFlight || dodgeQueued || threatActive || screenW <= screenH) return;
    float fromX = screenW * (cameraSweepRight ? 0.47f : 0.74f);
    float toX = screenW * (cameraSweepRight ? 0.74f : 0.47f);
    float y = screenH * 0.30f;
    cameraSweepRight = !cameraSweepRight;
    Path path = new Path();
    path.moveTo(fromX, y);
    path.lineTo(toX, y);
    GestureDescription gesture = new GestureDescription.Builder()
      .addStroke(new GestureDescription.StrokeDescription(path, 0, 160)).build();
    gestureInFlight = true;
    boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
      @Override public void onCompleted(GestureDescription g) { gestureFinished(); }
      @Override public void onCancelled(GestureDescription g) { gestureFinished(); }
    }, null);
    if (!accepted) gestureFinished();
  }

  private void directedDodge() {
    if (gestureInFlight) { dodgeQueued = true; return; }
    // Steering joystick and dodge button in one atomic multi-touch gesture.
    // Direction is estimated from screen warning pixels, not verified game geometry.
    float dx = dangerDirectionX, dy = dangerDirectionY;
    float length = (float)Math.hypot(dx, dy);
    if (length < 0.1f) { dx = -1f; dy = 0f; length = 1f; }
    float reach = Math.min(screenW, screenH) * 0.085f;
    Path stick = new Path();
    stick.moveTo(joystickX, joystickY);
    stick.lineTo(joystickX + reach * dx / length, joystickY + reach * dy / length);
    Path dodge = new Path();
    dodge.moveTo(dodgeX, dodgeY);
    GestureDescription gesture = new GestureDescription.Builder()
      .addStroke(new GestureDescription.StrokeDescription(stick, 0, 155))
      .addStroke(new GestureDescription.StrokeDescription(dodge, 35, 65)).build();
    gestureInFlight = true;
    boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
      @Override public void onCompleted(GestureDescription g) { gestureFinished(); }
      @Override public void onCancelled(GestureDescription g) { gestureFinished(); }
    }, null);
    if (!accepted) gestureFinished();
  }

  private void gestureFinished() {
    gestureInFlight = false;
    if (running && dodgeQueued) {
      dodgeQueued = false;
      lastDodgeAt = SystemClock.uptimeMillis();
      dodgePriorityUntil = lastDodgeAt + 500L;
      handler.post(this::directedDodge);
    }
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
      .addStroke(new GestureDescription.StrokeDescription(path, 0, 45)).build();
    gestureInFlight = true;
    boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
      @Override public void onCompleted(GestureDescription g) {
        gestureFinished();
        if (feedback) Toast.makeText(CombatAccessibilityService.this, "Ausweich-Beruehrung ausgefuehrt", Toast.LENGTH_SHORT).show();
      }
      @Override public void onCancelled(GestureDescription g) {
        gestureFinished();
        if (feedback) Toast.makeText(CombatAccessibilityService.this, "Ausweich-Beruehrung abgebrochen", Toast.LENGTH_SHORT).show();
      }
    }, null);
    if (!accepted) {
      gestureFinished();
      if (feedback) Toast.makeText(this, "Ausweich-Beruehrung nicht angenommen", Toast.LENGTH_SHORT).show();
    }
  }
}
