package de.originbot.diana;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
  private TextView info;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(32, 48, 32, 32);
    info = new TextView(this);
    info.setText("OriginBot Diana – Testmodus\nNur feste Zeitintervalle, keine Boss-Erkennung.\nNach Aktivierung erscheint BOT START als schwebender Knopf.\nIm Spiel BOT START/BOT STOP verwenden.\nTest zuerst außerhalb des Spiels.");
    root.addView(info);
    Button settings = new Button(this);
    settings.setText("Bedienungshilfe aktivieren");
    settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    root.addView(settings);
    Button start = new Button(this);
    start.setText("Testmodus starten (10 Sekunden)");
    start.setOnClickListener(v -> {
      CombatAccessibilityService service = CombatAccessibilityService.instance;
      if (service == null) {
        info.setText("Zuerst OriginBot Diana in den Bedienungshilfen aktivieren.");
        return;
      }
      DisplayMetrics metrics = getResources().getDisplayMetrics();
      float w = metrics.widthPixels, h = metrics.heightPixels;
      // Placeholder coordinates, not calibrated for a particular device or game.
      service.startCombat(w * 0.83f, h * 0.80f, w * 0.70f, h * 0.80f);
      info.setText("Testmodus läuft maximal 10 Sekunden. Stopp jederzeit möglich.");
      new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
        if (service.isRunning()) { service.stopCombat(); info.setText("Testmodus beendet."); }
      }, 10000);
    });
    root.addView(start);
    Button stop = new Button(this);
    stop.setText("SOFORT STOPPEN");
    stop.setOnClickListener(v -> {
      CombatAccessibilityService service = CombatAccessibilityService.instance;
      if (service != null) service.stopCombat();
      info.setText("Testmodus gestoppt.");
    });
    root.addView(stop);
    setContentView(root);
  }
}
