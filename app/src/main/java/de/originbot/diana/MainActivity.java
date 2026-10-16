package de.originbot.diana;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(32, 48, 32, 32);
    TextView info = new TextView(this);
    info.setText("OriginBot Diana – Gesten-Test\nNur auf eigenem Gerät testen.\nKeine automatische Boss- oder Angriffserkennung.");
    root.addView(info);
    Button settings = new Button(this);
    settings.setText("Bedienungshilfe aktivieren");
    settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    root.addView(settings);
    Button test = new Button(this);
    test.setText("Test-Tipp in Bildschirmmitte");
    test.setOnClickListener(v -> {
      CombatAccessibilityService service = CombatAccessibilityService.instance;
      if (service == null) {
        info.setText("Bitte zuerst OriginBot Diana in den Bedienungshilfen aktivieren.");
      } else {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        service.tap(metrics.widthPixels / 2f, metrics.heightPixels / 2f);
        info.setText("Testgeste gesendet. Kein automatischer Kampf aktiv.");
      }
    });
    root.addView(test);
    setContentView(root);
  }
}
