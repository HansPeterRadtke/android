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
import android.widget.CheckBox;
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
        CaretTestEditText input=new CaretTestEditText(this);input.setHint("Ich wollte heute …");
        input.setMinLines(2);input.setGravity(Gravity.TOP);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        layout.addView(input,new LinearLayout.LayoutParams(-1,dp(95)));
        TextView caretTest=new TextView(this);
        caretTest.setTextSize(13);
        caretTest.setPadding(0,dp(3),0,dp(5));
        layout.addView(caretTest,new LinearLayout.LayoutParams(-1,-2));
        input.setReadout(caretTest);
        label("Feldtypen ausprobieren (URL / E-Mail)",15);
        EditText urlTest=new EditText(this);
        urlTest.setHint("https://example.com/path-name");
        urlTest.setSingleLine(true);
        urlTest.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        urlTest.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        layout.addView(urlTest,new LinearLayout.LayoutParams(-1,dp(52)));
        EditText emailTest=new EditText(this);
        emailTest.setHint("user@example.com");
        emailTest.setSingleLine(true);
        emailTest.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailTest.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        layout.addView(emailTest,new LinearLayout.LayoutParams(-1,dp(52)));
        label("Neuronale Wortvorhersage (Deutsch und Englisch)",19);
        label("Zwei kleine Offline-Modelle analysieren mehr als die letzten zwei Wörter: das deutsche Modell von Destin Moss (4,7 MB, ODC-By und CC BY 2.0 FR) sowie Chime-SMS-2M von Luke Steuber für Englisch (2,2 MB, CC BY-SA 4.0). Sie berechnen Vorschläge im Hintergrund und ersetzen niemals Wörter automatisch. Persönliche Wortauswahl bleibt vorrangig.",14);
        action("Englisches Chime-Modell und Lizenz",() -> {
            Intent i=new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://huggingface.co/lukeslp/chime-sms-2m"));
            try{startActivity(i);}catch(Exception e){new AlertDialog.Builder(this)
                .setMessage("https://huggingface.co/lukeslp/chime-sms-2m")
                .setPositiveButton("OK",null).show();}
        });
        action("Deutsches neuronales Modell und Lizenz",() -> {
            Intent i=new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://huggingface.co/youcoded/next-word-german"));
            try{startActivity(i);}catch(Exception e){new AlertDialog.Builder(this)
                .setMessage("https://huggingface.co/youcoded/next-word-german")
                .setPositiveButton("OK",null).show();}
        });
        CheckBox neural=new CheckBox(this);
        neural.setText("Neuronale Vorschläge für Deutsch und Englisch verwenden");
        neural.setChecked(getSharedPreferences("keyboard_settings",0).getBoolean("neural_predictions",true));
        neural.setOnCheckedChangeListener((button,checked)-> {
            getSharedPreferences("keyboard_settings",0).edit().putBoolean("neural_predictions",checked).apply();
            AdaptiveKeyboardService.refreshSettings();
        });
        layout.addView(neural);
        label("Lernverhalten",19);
        label("Beim Weitertippen wird nichts übernommen. Leertaste, Satzzeichen, Eingabetaste oder eine bewusst gewählte Vorhersage bestätigen Wörter bzw. Satzzeichen-Tokens. Der vollständige Text wird nicht gespeichert; nur lokale Übergangsstatistiken. Passwörter, URLs, E-Mail-Adressen und private Felder werden von der persönlichen Lernstatistik ausgeschlossen.",14);
        action("Privatmodus umschalten",() -> {
            boolean newValue=!getSharedPreferences("keyboard_settings",0).getBoolean("incognito",false);
            getSharedPreferences("keyboard_settings",0).edit().putBoolean("incognito",newValue).apply();
            AdaptiveKeyboardService.refreshSettings();
            new AlertDialog.Builder(this).setMessage(newValue?"Privatmodus an. Es wird nichts gelernt.":"Privatmodus aus. Lernen bei bestätigten Wörtern aktiv.").setPositiveButton("OK",null).show();
        });
        label("GIF-Suche: Reaktionen statt Wikimedia-Animationen",19);
        label("ReactionGIFs liefert Reaktionen direkt in der Tastatur, ohne Konto und ohne Schlüssel. Englische Suchbegriffe funktionieren am besten. GIPHY ist mit eigenem Schlüssel ebenfalls verfügbar. Nur ausdrücklich gestartete GIF-Suchbegriffe verlassen das Gerät.",14);
        action("Kostenlosen GIPHY-Schlüssel erstellen",() -> {
            Intent browser=new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://developers.giphy.com/"));
            try {startActivity(browser);}
            catch(Exception e) {new AlertDialog.Builder(this).setMessage("Kein Browser verfügbar. developers.giphy.com im Browser öffnen.").setPositiveButton("OK",null).show();}
        });
        label("Bei GIPHY anmelden, eine API-App erstellen und den Schlüssel hier einfügen.",13);
        EditText key=new EditText(this);
        key.setHint("GIPHY API-Schlüssel (optional)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSingleLine(true);layout.addView(key);
        action("GIF-Schlüssel speichern",() -> {
            getSharedPreferences("keyboard_settings",0).edit().putString("giphy_key",key.getText().toString().trim()).apply();
            key.setText("");new AlertDialog.Builder(this).setMessage("Schlüssel lokal gespeichert. Die GIF-Suche verwendet jetzt GIPHY.").setPositiveButton("OK",null).show();
        });
        action("GIPHY-Schlüssel entfernen (zurück zu ReactionGIFs)",() -> {
            getSharedPreferences("keyboard_settings",0).edit().remove("giphy_key").apply();
            new AlertDialog.Builder(this).setMessage("GIPHY-Schlüssel gelöscht. Ohne Schlüssel nutzt die Tastatur ReactionGIFs.").setPositiveButton("OK",null).show();
        });
        final String[] ratings={"g","pg","pg-13","r"};
        final String[] labels={"G: familienfreundlich","PG: milde Sprache", "PG-13: kräftige Sprache", "R: derber Humor und starke Sprache"};
        String storedRating=getSharedPreferences("keyboard_settings",0).getString("giphy_rating","r");
        int selectedRating=3;
        for(int i=0;i<ratings.length;i++)if(ratings[i].equals(storedRating))selectedRating=i;
        final int[] selected={selectedRating};
        Button ratingButton=new Button(this);
        ratingButton.setText("GIPHY-Filter: "+labels[selected[0]]);
        ratingButton.setAllCaps(false);
        ratingButton.setOnClickListener(v -> {
            selected[0]=(selected[0]+1)%ratings.length;
            getSharedPreferences("keyboard_settings",0).edit().putString("giphy_rating",ratings[selected[0]]).apply();
            ratingButton.setText("GIPHY-Filter: "+labels[selected[0]]);
        });
        layout.addView(ratingButton,new LinearLayout.LayoutParams(-1,-2));
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
        label("Datenschutz: Android kann den Inhalt von Tastatur-Eingaben an das ausgewählte IME übermitteln. Diese App speichert bestätigte Wort- und Satzzeichen-Tokens als lokale Häufigkeitsstatistiken sowie die letzten zwanzig kopierten Texte. Als vertraulich markierte Clips werden nicht in den Verlauf aufgenommen. Die gespeicherte Datei liegt in der privaten App-Datenbank; Android-Backups sind abgeschaltet.",13);
    }
    private void label(String value,int size) {
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);
        v.setPadding(0,dp(10),0,dp(9));layout.addView(v,new LinearLayout.LayoutParams(-1,-2));
    }
    private static final class CaretTestEditText extends EditText {
        private TextView readout;
        CaretTestEditText(Context context) {super(context);}
        void setReadout(TextView status) {readout=status;updateStatus();}
        @Override protected void onSelectionChanged(int start,int end) {
            super.onSelectionChanged(start,end);
            updateStatus();
        }
        private void updateStatus() {
            if(readout==null)return;
            int start=Math.max(0,getSelectionStart()),end=Math.max(0,getSelectionEnd());
            String caption=start==end?"Cursor: "+end+" / "+length():
                "Markierung: "+Math.min(start,end)+" – "+Math.max(start,end);
            readout.setText(caption);
        }
    }
    private void action(String title,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(5);layout.addView(b,p);
    }
}
