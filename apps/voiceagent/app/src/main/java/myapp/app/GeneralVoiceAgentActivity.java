package myapp.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;
import okhttp3.Request;
import java.io.InputStream;
import android.os.Handler;
import android.os.Looper;
import com.google.android.material.button.MaterialButton;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;

/**
 * General-purpose UI skin over the single existing Android03 PCM/WS transport.
 * No STT, LLM, audio synthesis, inference runtime or speaker model on this device.
 */
public final class GeneralVoiceAgentActivity extends MainActivity {
  private static final String PREFS = "general_voice_client_v1";
  private static final int PICK_AVATAR = 1701;
  private static final int[] BACKGROUNDS = {
      0xfff1f7fb, 0xfffcf3ea, 0xfff0f7f1, 0xfff5f5f6
  };
  private static final int[] ACCENTS = {
      0xff17668e, 0xff9b5928, 0xff267a58, 0xff565e73
  };
  private static final String[] THEMES = {"Ocean", "Copper", "Forest", "Neutral"};
  private static final String[] ANIMATIONS = {"Pulse", "Wave bars", "Ring", "Still"};
  private static final String[] SIZES = {"Compact", "Standard", "Large"};

  /**
   * Owner-controlled ADB provisioning for a debuggable personal-device build.
   * No token is shipped with, logged by, or fetched anonymously by the APK.
   * A private app-UID file is accepted only in this debug installation,
   * encrypted into Android Keystore once, then erased and deleted before
   * network startup. Release APKs never use this mechanism.
   */
  @Override protected void onCreate(Bundle savedInstanceState) {
    if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      java.io.File staged = new java.io.File(getFilesDir(), "voice_provision_once");
      if (staged.isFile()) {
        byte[] bytes = null;
        try {
          if (staged.length() > 0 && staged.length() < 512) {
            bytes = java.nio.file.Files.readAllBytes(staged.toPath());
            String bearer = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (VoiceWebSocketAuth.configured(bearer)) {
              VoiceCredentialVault.save(this, bearer);
            }
          }
        } catch (Exception ignored) {
          // No logging, no fallback that embeds or broadcasts a credential.
        } finally {
          if (bytes != null) java.util.Arrays.fill(bytes, (byte)0);
          try {
            if (staged.isFile()) {
              try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(staged, "rw")) {
                byte[] zero = new byte[128];
                long remaining = file.length();
                file.seek(0);
                while (remaining > 0) {
                  int amount = (int)Math.min(zero.length, remaining);
                  file.write(zero, 0, amount);
                  remaining -= amount;
                }
                file.getFD().sync();
              }
              staged.delete();
            }
          } catch (Exception ignored) {}
        }
      }
    }
    super.onCreate(savedInstanceState);
  }

  private final GeneralVoicePairing pairing = new GeneralVoicePairing();
  private final ExecutorService pairNetwork = Executors.newSingleThreadExecutor();
  private final Handler pairHandler = new Handler(Looper.getMainLooper());
  private final AtomicBoolean pairInFlight = new AtomicBoolean(false);
  private volatile boolean pairingForeground = false;
  private MaterialButton connectButton;
  private final Runnable refreshPairing = new Runnable() {
    @Override public void run() {
      pollPairingOnce();
    }
  };

  private boolean needsPairing() {
    return GeneralVoiceProfile.DEFAULT_ENDPOINT.equals(endpoint())
        && !VoiceCredentialVault.configured(this);
  }

  private void pairingStatus(String text) {
    if(needsPairing()) showVoiceClientPairingStatus("Connect this phone",text);
  }

  private void connectPhone() {
    if(!needsPairing())return;
    GeneralVoicePairing.Session existing=GeneralVoicePairVault.load(this);
    if(existing!=null && System.currentTimeMillis()<existing.expiresAt) {
      openApproval(existing.pairId);
      return;
    }
    if(!pairInFlight.compareAndSet(false,true))return;
    pairingStatus("Requesting one-time approval from Raspi.");
    pairNetwork.execute(() -> {
      GeneralVoicePairing.Session pending=null;
      try {pending=pairing.begin();}
      catch(Exception ignored) {}
      final GeneralVoicePairing.Session issued=pending;
      runOnUiThread(() -> {
        pairInFlight.set(false);
        if(isFinishing() || isDestroyed())return;
        if(issued==null || !GeneralVoicePairVault.save(this,issued)) {
          pairingStatus("Unable to reach Raspi. Tap Connect to retry.");
          return;
        }
        if(pairingForeground) openApproval(issued.pairId);
        else pairingStatus("Return here and tap Connect to approve this phone.");
      });
    });
  }

  private void openApproval(String pairId) {
    try {
      // The browser sends Raspi's existing secure admin session cookie.
      // The private proof is NOT in this URL and NOT shared with the browser.
      Intent browser=new Intent(Intent.ACTION_VIEW,
          Uri.parse(GeneralVoicePairing.approvalUrl(pairId)));
      pairingStatus("Approve this phone in Raspi, then return here.");
      startActivity(browser);
    }catch(Exception failed) {
      pairingStatus("Cannot open Raspi approval. Tap Connect to retry.");
    }
  }

  private void pollPairingOnce() {
    if(!pairingForeground || !needsPairing())return;
    final GeneralVoicePairing.Session pending=GeneralVoicePairVault.load(this);
    if(pending==null)return;
    if(!pairInFlight.compareAndSet(false,true))return;
    pairNetwork.execute(() -> {
      GeneralVoicePairing.Poll result=null;
      boolean expired=false;
      try {
        result=pairing.poll(pending);
      }catch(GeneralVoicePairing.PairExpiredException expiredOnServer){
        expired=true;
      }catch(IOException failed){
        expired=System.currentTimeMillis()>=pending.expiresAt;
      }
      final GeneralVoicePairing.Poll received=result;
      final boolean stale=expired;
      runOnUiThread(() -> {
        pairInFlight.set(false);
        if(isFinishing() || isDestroyed())return;
        if(stale){
          GeneralVoicePairVault.clear(this);
          pairingStatus("Approval expired. Tap Connect to start again.");
          return;
        }
        if(received!=null && "paired".equals(received.status)) {
          if(VoiceCredentialVault.save(this,received.bearer)){
            GeneralVoicePairVault.clear(this);
            if(connectButton!=null)connectButton.setVisibility(View.GONE);
            showVoiceClientPairingStatus("Connecting","Server pairing completed securely.");
            if(pairingForeground) reconnectVoiceClientAfterProvisioning();
            return;
          }
          GeneralVoicePairVault.clear(this);
          pairingStatus("Cannot protect the server credential. Tap Connect to retry.");
        } else if(received!=null && "pending".equals(received.status)) {
          pairingStatus("Approve this phone in Raspi, then return here.");
        } else {
          pairingStatus("Waiting for private Raspi pairing. Tap Connect to retry.");
        }
        if(pairingForeground && needsPairing()) {
          pairHandler.removeCallbacks(refreshPairing);
          pairHandler.postDelayed(refreshPairing,3000);
        }
      });
    });
  }

  private GeneralVoiceVisual visual;
  private ImageView avatar;
  private LinearLayout skinRow;
  private String currentState = "READY";

  private SharedPreferences preferences() {
    return getSharedPreferences(PREFS, MODE_PRIVATE);
  }
  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
  private String endpoint() {
    String saved = preferences().getString("endpoint", GeneralVoiceProfile.DEFAULT_ENDPOINT);
    try { return GeneralVoiceProfile.normalizeEndpoint(saved); }
    catch (IllegalArgumentException invalid) { return GeneralVoiceProfile.DEFAULT_ENDPOINT; }
  }
  private String voiceId() {
    try { return GeneralVoiceProfile.normalizeVoice(preferences().getString("voice", "")); }
    catch (IllegalArgumentException invalid) { return ""; }
  }

  @Override protected String getVoiceWebSocketEndpoint() { return endpoint(); }

  @Override protected String getVoiceHealthEndpoint() {
    return GeneralVoiceProfile.healthFor(endpoint());
  }

  @Override protected Request authorizeVoiceWebSocket(String url, String bearer) {
    return VoiceWebSocketAuth.requestForEndpoint(url, bearer, endpoint());
  }

  @Override protected void customizeVoiceClientState(JSONObject state) throws Exception {
    String voice = voiceId();
    if (!voice.isEmpty()) state.put("voice_id", voice);
  }

  @Override protected void customizeVoiceClientScreen(LinearLayout root) {
    int palette = GeneralVoiceProfile.option(preferences().getInt("palette", 0), THEMES.length);
    int size = GeneralVoiceProfile.option(preferences().getInt("size", 1), SIZES.length);
    int accent = ACCENTS[palette];
    root.setBackgroundColor(BACKGROUNDS[palette]);
    root.setPadding(dp(size==0?10:14), dp(8), dp(size==0?10:14),dp(8));

    skinRow = new LinearLayout(this);
    skinRow.setGravity(Gravity.CENTER_VERTICAL);
    skinRow.setOrientation(LinearLayout.HORIZONTAL);
    skinRow.setPadding(dp(4), dp(4), dp(4), dp(4));

    int frameSize = size==0 ? 58 : size==1 ? 76 : 100;
    FrameLayout artwork = new FrameLayout(this);
    LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(dp(frameSize),dp(frameSize));
    frameParams.setMarginEnd(dp(9));
    skinRow.addView(artwork,frameParams);

    avatar = new ImageView(this);
    avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
    avatar.setContentDescription("Selected agent image");
    FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    imageParams.setMargins(dp(4),dp(4),dp(4),dp(4));
    artwork.addView(avatar, imageParams);
    loadAvatar();

    visual = new GeneralVoiceVisual(this);
    visual.configure(accent, preferences().getInt("animation",0));
    artwork.addView(visual,new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    visual.setAvatarPresent(!preferences().getString("avatar","").isEmpty() && avatar.getDrawable()!=null);

    LinearLayout text = new LinearLayout(this);
    text.setOrientation(LinearLayout.VERTICAL);
    TextView title = new TextView(this);
    title.setText("VOICE AGENT");
    title.setTextColor(accent);
    title.setTextSize(size==0?13:16);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    text.addView(title);
    TextView explanation = new TextView(this);
    explanation.setText("Speech and replies run on your server");
    explanation.setTextSize(size==0?11:13);
    explanation.setTextColor(0xff4a5568);
    text.addView(explanation);
    skinRow.addView(text,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));

    Button customize = new Button(this);
    customize.setText("Customize");
    customize.setTextSize(12);
    customize.setAllCaps(false);
    customize.setMinHeight(dp(48));
    customize.setContentDescription("Configure voice agent image, animation, appearance, voice and server");
    customize.setOnClickListener(v->showClientConfiguration());
    skinRow.addView(customize,new LinearLayout.LayoutParams(dp(105),dp(52)));
    root.addView(skinRow,1);
    if(needsPairing()) {
      connectButton = new MaterialButton(this);
      connectButton.setText("Connect to server");
      connectButton.setMinHeight(dp(48));
      connectButton.setContentDescription("Securely approve this phone in the private Raspi portal");
      connectButton.setOnClickListener(v -> connectPhone());
      root.addView(connectButton,2);
    }
  }

  @Override protected void onVoiceClientStatus(String state) {
    currentState=state;
    if(visual!=null)visual.state(state);
    if("UNAVAILABLE".equals(state) && needsPairing())
      pairingStatus("Tap Connect to authorize this phone in Raspi.");
  }

  private Bitmap loadBoundedImage(Uri uri) throws Exception {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    try(InputStream input=getContentResolver().openInputStream(uri)) {
      if(input == null)throw new IllegalArgumentException("Image unavailable");
      BitmapFactory.decodeStream(input,null,bounds);
    }
    if(bounds.outWidth < 1 || bounds.outHeight < 1)
      throw new IllegalArgumentException("Not a supported image");
    int sample=1;
    while(bounds.outWidth/sample>512 || bounds.outHeight/sample>512)sample*=2;
    BitmapFactory.Options decode=new BitmapFactory.Options();
    decode.inSampleSize=sample;
    try(InputStream input=getContentResolver().openInputStream(uri)) {
      if(input == null)throw new IllegalArgumentException("Image unavailable");
      return BitmapFactory.decodeStream(input,null,decode);
    }
  }

  private void loadAvatar() {
    if(avatar==null)return;
    String saved=preferences().getString("avatar","");
    if(saved.isEmpty()) {avatar.setImageDrawable(null);if(visual!=null)visual.setAvatarPresent(false);return;}
    try {
      Bitmap bitmap = loadBoundedImage(Uri.parse(saved));
      if(bitmap == null)throw new IllegalArgumentException("Image unreadable");
      avatar.setImageBitmap(bitmap);
      if(visual!=null)visual.setAvatarPresent(true);
    } catch(Exception invalid) {
      avatar.setImageDrawable(null);
      if(visual!=null)visual.setAvatarPresent(false);
      // A revoked SAF grant is not a reason to crash or contact the server.
    }
  }

  private void chooseImage() {
    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("image/*");
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(intent,PICK_AVATAR);
  }
  @Override protected void onActivityResult(int request,int result,Intent intent) {
    super.onActivityResult(request,result,intent);
    if(request!=PICK_AVATAR || result!=Activity.RESULT_OK || intent==null
        || intent.getData()==null)return;
    Uri uri=intent.getData();
    try {
      Bitmap preview=loadBoundedImage(uri);
      if(preview==null)throw new IllegalArgumentException("Cannot decode image");
      getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
      preferences().edit().putString("avatar",uri.toString()).apply();
      loadAvatar();
    }catch(Exception invalid){
      Toast.makeText(this,"Cannot keep this image; choose a local image file.",Toast.LENGTH_LONG).show();
    }
  }

  private static Spinner picker(Activity activity,String[] values,int choice) {
    Spinner spinner=new Spinner(activity);
    ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,values);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    spinner.setSelection(choice);
    spinner.setMinimumHeight(Math.round(52*activity.getResources().getDisplayMetrics().density));
    return spinner;
  }
  private void field(LinearLayout container,String label,View value) {
    TextView heading=new TextView(this);
    heading.setText(label);
    heading.setTextSize(15);
    heading.setPadding(0,dp(13),0,dp(4));
    container.addView(heading);
    container.addView(value,new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
  }
  private EditText edit(String initial,String hint) {
    EditText view=new EditText(this);
    view.setSingleLine(true);
    view.setText(initial);
    view.setHint(hint);
    view.setTextSize(16);
    view.setSelectAllOnFocus(false);
    return view;
  }
  private void showClientConfiguration() {
    SharedPreferences prefs=preferences();
    LinearLayout content=new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20),dp(12),dp(20),dp(16));

    Spinner palette=picker(this,THEMES,GeneralVoiceProfile.option(prefs.getInt("palette",0),THEMES.length));
    Spinner animation=picker(this,ANIMATIONS,GeneralVoiceProfile.option(prefs.getInt("animation",0),ANIMATIONS.length));
    Spinner size=picker(this,SIZES,GeneralVoiceProfile.option(prefs.getInt("size",0),SIZES.length));
    field(content,"Colors",palette);
    field(content,"Agent animation",animation);
    field(content,"Visual size",size);

    Button image=new Button(this);
    image.setText("Choose avatar image");
    image.setAllCaps(false);
    image.setMinHeight(dp(48));
    content.addView(image);
    image.setOnClickListener(v->chooseImage());
    Button clear=new Button(this);
    clear.setText("Remove image");
    clear.setAllCaps(false);
    clear.setMinHeight(dp(48));
    content.addView(clear);
    clear.setOnClickListener(v->{preferences().edit().remove("avatar").apply();loadAvatar();});

    EditText voice=edit(voiceId(),"Empty = server default");
    field(content,"Requested server voice ID",voice);
    TextView note=new TextView(this);
    note.setText("The server must support and acknowledge a voice ID. This phone does not synthesize voices.");
    note.setTextColor(0xff606673);
    note.setTextSize(13);
    content.addView(note);

    EditText server=edit(endpoint(),"wss://your-server/ws");
    server.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    field(content,"Compatible secure audio server",server);
    TextView warning=new TextView(this);
    warning.setText("Changing the server removes the old server credential. Re-enter the new server credential under Voice settings. No private token is stored in the APK.");
    warning.setTextSize(13);
    warning.setTextColor(0xff606673);
    content.addView(warning);

    ScrollView scroll=new ScrollView(this);
    scroll.addView(content);
    androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
      .setTitle("Voice Agent appearance and server")
      .setView(scroll)
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Save",null)
      .create();
    dialog.setOnShowListener(ignored -> dialog.getButton(
        android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(clicked -> {
        if(isVoiceClientSessionActive()){
          Toast.makeText(this,"Stop listening before changing settings.",Toast.LENGTH_LONG).show();
          return;
        }
        final String destination,requestedVoice;
        try {
          destination=GeneralVoiceProfile.normalizeEndpoint(server.getText().toString());
          requestedVoice=GeneralVoiceProfile.normalizeVoice(voice.getText().toString());
        }catch(IllegalArgumentException invalid){
          Toast.makeText(this,invalid.getMessage(),Toast.LENGTH_LONG).show();
          return;
        }
        if(!destination.equals(endpoint())){
          if(!VoiceCredentialVault.clear(this)) {
            Toast.makeText(this,"Could not remove previous server credential. Server unchanged.",Toast.LENGTH_LONG).show();
            return;
          }
        }
        boolean saved=prefs.edit()
            .putString("endpoint",destination)
            .putString("voice",requestedVoice)
            .putInt("palette",palette.getSelectedItemPosition())
            .putInt("animation",animation.getSelectedItemPosition())
            .putInt("size",size.getSelectedItemPosition())
            .commit();
        if(!saved){
          Toast.makeText(this,"Configuration could not be saved.",Toast.LENGTH_LONG).show();
          return;
        }
        dialog.dismiss();
        recreate(); // Rebuild visuals and securely reconnect with updated preferences.
      }));
    dialog.show();
  }

  @Override protected void onPause() {
    pairingForeground = false;
    pairHandler.removeCallbacks(refreshPairing);
    if(visual!=null)visual.state("READY");
    super.onPause();
  }
  @Override protected void onResume() {
    super.onResume();
    pairingForeground = true;
    if(visual!=null)visual.state(currentState);
    if(VoiceCredentialVault.configured(this)) {
      reconnectVoiceClientAfterProvisioning();
    }
    if(needsPairing()) {
      pairingStatus("Tap Connect to authorize this phone in Raspi.");
      pairHandler.removeCallbacks(refreshPairing);
      pairHandler.postDelayed(refreshPairing,250);
    }
  }
  @Override protected void onDestroy() {
    pairingForeground = false;
    pairHandler.removeCallbacks(refreshPairing);
    pairNetwork.shutdownNow();
    super.onDestroy();
  }
}
