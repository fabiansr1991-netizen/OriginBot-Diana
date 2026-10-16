package de.originbot.diana;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(32, 48, 32, 32);
    TextView status = new TextView(this);
    status.setText("OriginBot Diana – Grundgerüst\nNoch keine automatische Kampferkennung.\nAktiviere die Bedienungshilfe nur für eigene Tests.");
    root.addView(status);
    Button settings = new Button(this);
    settings.setText("Bedienungshilfe öffnen");
    settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    root.addView(settings);
    setContentView(root);
  }
}
