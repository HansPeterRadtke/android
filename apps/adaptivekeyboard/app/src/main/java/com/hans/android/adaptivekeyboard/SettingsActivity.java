package com.hans.android.adaptivekeyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.InputType;

/** Simple onboarding and explicit local-data controls. */
public final class SettingsActivity extends Activity {
    private LinearLayout layout;
    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density*v); }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        layout=new LinearLayout(this); layout.setPadding(dp(22),dp(26),dp(22),dp(24));layout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(layout); setContentView(scroll);
        label("KontextTastatur",24);
        label("Deutsch QWERTZ. Drei Vorschläge. Das mittlere Wort ist immer der beste Treffer. Nur bestätigte Wörter und ausdrücklich gewählte Vorschläge werden gelernt. Alles bleibt im geschützten App-Speicher.",15);
        action("1. Tastatur in Android aktivieren",() -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        action("2. KontextTastatur auswählen",() -> {
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm!=null) imm.showInputMethodPicker();
        });
        label("Hier ausprobieren:",16);
        EditText input=new EditText(this);input.setHint("Ich wollte heute …");
        input.setMinLines(2);input.setGravity(Gravity.TOP);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        layout.addView(input,new LinearLayout.LayoutParams(-1,dp(95)));
        label("Lernverhalten",19);
        label("Beim Weitertippen wird nichts übernommen. Erst Leertaste, Satzzeichen, Eingabetaste oder die Wahl eines Vorschlags bestätigt ein Wort. Das vollständige Eingabefeld und Passwörter werden nicht gespeichert. Private Felder werden automatisch ausgeschlossen.",14);
        action("Privatmodus umschalten",() -> {
            boolean newValue=!getSharedPreferences("keyboard_settings",0).getBoolean("incognito",false);
            getSharedPreferences("keyboard_settings",0).edit().putBoolean("incognito",newValue).apply();
            AdaptiveKeyboardService.refreshSettings();
            new AlertDialog.Builder(this).setMessage(newValue?"Privatmodus an. Es wird nichts gelernt.":"Privatmodus aus. Lernen bei bestätigten Wörtern aktiv.").setPositiveButton("OK",null).show();
        });
        label("GIF-Suche mit GIPHY",19);
        label("GIPHY verlangt einen eigenen API-Schlüssel. Die Internetverbindung wird nur nach einer ausdrücklich gestarteten GIF-Suche verwendet. Die Eingaben in anderen Apps werden nicht an GIPHY übertragen.",14);
        EditText key=new EditText(this);
        key.setHint("GIPHY API-Schlüssel (optional)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSingleLine(true);layout.addView(key);
        action("GIF-Schlüssel speichern",() -> {
            getSharedPreferences("keyboard_settings",0).edit().putString("giphy_key",key.getText().toString().trim()).apply();
            key.setText("");new AlertDialog.Builder(this).setMessage("GIF-Schlüssel lokal gespeichert.").setPositiveButton("OK",null).show();
        });
        label("Eigene Daten löschen",19);
        action("Gelernte Wörter und Auswahlhistorie löschen",() -> new AlertDialog.Builder(this)
            .setTitle("Lernhistorie wirklich löschen?")
            .setMessage("Wörter, Wortfolgen und gelernte Auswahlen werden dauerhaft entfernt. Das eingebaute Wörterbuch bleibt erhalten.")
            .setNegativeButton("Abbrechen",null)
            .setPositiveButton("Löschen",(dialog,which) -> {
                AdaptiveKeyboardService.clearLearning(this);
                new AlertDialog.Builder(this).setMessage("Löschung angefordert. Lernhistorie wird im App-Speicher gelöscht.").setPositiveButton("OK",null).show();
            }).show());
        action("Gespeicherte Zwischenablage löschen",() -> new AlertDialog.Builder(this)
            .setTitle("Zwischenablage löschen?").setNegativeButton("Abbrechen",null)
            .setPositiveButton("Löschen",(dialog,which) -> new LearningDatabase(this).clearClipboard()).show());
        label("Datenschutz: Android kann den Inhalt von Tastatur-Eingaben an das ausgewählte IME übermitteln. Diese App speichert nur bestätigte Wortstatistiken und freiwillig angeheftete Zwischenablageeinträge. Die gespeicherte Datei liegt in der privaten App-Datenbank; Android-Backups sind abgeschaltet.",13);
    }
    private void label(String value,int size) {
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);
        v.setPadding(0,dp(10),0,dp(9));layout.addView(v,new LinearLayout.LayoutParams(-1,-2));
    }
    private void action(String title,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(5);layout.addView(b,p);
    }
}
