package com.hans.android.adaptivekeyboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.widget.GridLayout;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;

/** A stand-alone German QWERTZ IME. No network use outside the explicit GIF panel. */
public final class AdaptiveKeyboardService extends InputMethodService {
    private static final int BG=0xff18212a, KEY=0xff303e4b, PRESSED=0xff406179, TEXT=Color.WHITE;
    private static AdaptiveKeyboardService active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Never queue a user-initiated send behind background thumbnail downloads.
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService sendWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService languageWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"keyboard-offline-language-model");t.setDaemon(true);return t;
    });
    // At most one running and one queued neural task: stale text cannot pile up.
    private final ThreadPoolExecutor neuralWorker=new ThreadPoolExecutor(1,1,0L,
        TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r -> {
            Thread t=new Thread(r,"keyboard-neural-predict");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile ChimeEnglish neuralModel;
    private volatile GermanNextWord germanNeuralModel;
    private volatile GermanCaseModel germanCaseModel;
    private int predictionRevision=0;
    private boolean manualShiftPending=false;
    private final android.util.LruCache<String,byte[]> previewCache =
        new android.util.LruCache<String,byte[]>(5_000_000) {
            @Override protected int sizeOf(String key,byte[] value) {return value.length;}
        };
    private PredictionModel predictions;
    private BaselineNgrams baselineNgrams;
    private LearningDatabase database;
    private LinearLayout root, suggestionRow, toolbar, keys, panel;
    private TextView caretIndicator;
    private TextView activeSpaceKey;
    private SpaceSwipe spaceSwipe;
    private Runnable spaceHold;
    private float spaceDownX, spaceLatestX;
    private TextView[] suggestions = new TextView[3];
    private String[] visibleWords = new String[3];
    private String mode="letters",gifQuery="";
    private TextView gifStatus;
    private LinearLayout gifResults;
    private ScrollView gifScroll;
    private TextView gifQueryDisplay, gifMore;
    private String gifProvider="commons", gifRating="r";
    private boolean gifEditing=true;
    private int gifOffset=0, gifGridCells=0;
    private int gifPreviewsExpected, gifPreviewsLoaded, gifPreviewsFailed;
    private boolean shift=true,caps=false,sensitive=false,privateMode=false;
    private EditorInfo info;
    private int searchRevision=0;
    private PredictionModel.Event recentEvent;
    private String recentSurface="";
    private long recentAt=0;
    private Runnable backspaceRepeat;

    @Override public void onCreate() {
        super.onCreate();active=this;
        predictions=new PredictionModel();database=new LearningDatabase(this);
        reseed();
        try {database.restore(predictions);} catch(Exception e) {android.util.Log.e("KontextTastatur","Learning DB load failed",e);}
        // Parsing hundreds of thousands of offline ngrams can take multiple
        // seconds on slower phones. Never block the IME's main/UI thread.
        languageWorker.execute(() -> {
            BaselineNgrams loaded=new BaselineNgrams();
            for(String language:new String[]{"de","en"}) {
                if(Thread.currentThread().isInterrupted())return;
                try(java.io.InputStream stream=getAssets().open(language+"_next_words.ngram")) {
                    loaded.load(stream,language);
                } catch(Exception ex) {
                    android.util.Log.e("KontextTastatur","Offline next-word baseline load failed "+language,ex);
                }
            }
            ChimeEnglish englishModel=null;
            GermanNextWord germanModel=null;
            GermanCaseModel caseModel=null;
            try(java.io.InputStream modelStream=getAssets().open("chime_en.ltc")) {
                englishModel=new ChimeEnglish(modelStream);
            } catch(Exception ex) {
                android.util.Log.e("KontextTastatur","Optional offline English neural model not available",ex);
            }
            try(java.io.InputStream modelStream=getAssets().open("german_next_word.nwp")) {
                germanModel=new GermanNextWord(modelStream);
            } catch(Exception ex) {
                android.util.Log.e("KontextTastatur","Optional offline German neural model not available",ex);
            }
            try(java.io.InputStream caseStream=getAssets().open("de_word_case.gcase")) {
                caseModel=new GermanCaseModel(caseStream);
            } catch(Exception ex) {
                android.util.Log.e("KontextTastatur","German corpus capitalization unavailable",ex);
            }
            final ChimeEnglish ready=englishModel;
            final GermanNextWord germanReady=germanModel;
            final GermanCaseModel caseReady=caseModel;
            handler.post(() -> {
                if(active!=this)return;
                baselineNgrams=loaded;
                predictions.setBaselineNgrams(loaded);
                neuralModel=ready;
                germanNeuralModel=germanReady;
                germanCaseModel=caseReady;
                refreshSuggestions();
                android.util.Log.i("KontextTastatur","Offline ngram model loaded: "+loaded.size());
                if(ready!=null)android.util.Log.i("KontextTastatur",
                    "Offline English Chime neural model ready: "+ready.vocabularySize());
                if(germanReady!=null)android.util.Log.i("KontextTastatur",
                    "Offline German neural model ready: "+germanReady.vocabularySize());
                if(caseReady!=null)android.util.Log.i("KontextTastatur",
                    "Offline German context casing ready: "+caseReady.wordCount());
            });
        });
        refreshSettings();
    }
    @Override public void onDestroy() {
        searchRevision++; handler.removeCallbacksAndMessages(null);
        spaceHold=null;spaceSwipe=null;
        network.shutdownNow();
        searchWorker.shutdownNow();
        sendWorker.shutdownNow();
        languageWorker.shutdownNow();
        neuralWorker.shutdownNow();
        neuralModel=null;germanNeuralModel=null;germanCaseModel=null;
        if(database!=null) database.close();
        if(active==this) active=null;
        super.onDestroy();
    }
    public static void refreshSettings() {
        if(active!=null) {
            active.privateMode=active.getSharedPreferences("keyboard_settings",0).getBoolean("incognito",false);
            active.refreshSuggestions();
        }
    }
    public static void clearLearning(Context c) {
        if(active!=null) {
            active.predictions=new PredictionModel();
            active.predictions.setBaselineNgrams(active.baselineNgrams);
            active.reseed();
            active.recentEvent=null;
            active.database.clearLearning();
            active.refreshSuggestions();
        } else {
            new LearningDatabase(c).clearLearning();
        }
    }
    private void reseed() {
        loadLexicon("de_lexicon.json","de");
        loadLexicon("en_lexicon.json","en");
    }
    private void loadLexicon(String name,String language) {
        try (java.io.InputStream in=getAssets().open(name)) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            JSONArray ws=new JSONObject(out.toString("UTF-8")).getJSONArray("words");
            for(int i=0;i<ws.length();i++)predictions.addBaseWord(ws.getString(i),i,language);
        } catch(Exception e) {android.util.Log.e("KontextTastatur","Lexicon load failed: "+language,e);}
    }
    private boolean editorWantsCapitalization() {
        if(info==null)return false;
        int flags=info.inputType & (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
            InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if(flags==0)return false;
        InputConnection ic=getCurrentInputConnection();
        return ic!=null && ic.getCursorCapsMode(flags)!=0;
    }
    /** Re-evaluate sentence auto-capitalization after an edit or cursor move. */
    private void updateAutoShift() {
        if(caps || manualShiftPending || !"letters".equals(mode) || info==null)return;
        InputConnection ic=getCurrentInputConnection();
        if(ic==null)return;
        CharSequence previous=ic.getTextBeforeCursor(200,0);
        if(previous==null)return;
        if(!TextContext.fromBeforeCursor(previous).prefix.isEmpty())return;
        boolean desired=editorWantsCapitalization() || WordCase.sentenceStart(previous.toString());
        if(desired!=shift) {
            shift=desired;
            if(keys!=null)renderKeyboard();
        }
    }
    private boolean neuralEnabled() {
        return getSharedPreferences("keyboard_settings",0).getBoolean("neural_predictions",true);
    }
    private Map<String,Double> germanNeuralScores(List<GermanNextWord.Candidate> candidates) {
        Map<String,Double> result=new HashMap<>();
        int limit=Math.min(15,candidates.size());
        for(int i=0;i<limit;i++) {
            String word=TextContext.normalize(candidates.get(i).word);
            if(!word.isEmpty() && Character.isLetter(word.charAt(0)))
                result.put(word,Math.max(1,60.0-3.2*i));
        }
        return result;
    }
    private Map<String,Double> neuralScores(List<ChimeEnglish.Candidate> candidates) {
        Map<String,Double> result=new HashMap<>();
        for(int i=0;i<candidates.size();i++) {
            String token=TextContext.normalize(candidates.get(i).word);
            if(!token.isEmpty() && Character.isLetter(token.charAt(0)))
                result.put(token,Math.max(0,43.0-3.5*i));
        }
        return result;
    }
    private int dp(float v) {return (int)(getResources().getDisplayMetrics().density*v+0.5f);}
    private GradientDrawable background(int color) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(6));return d;
    }

    @Override public View onCreateInputView() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(3),dp(4),dp(3),dp(6));root.setBackgroundColor(BG);
        suggestionRow=new LinearLayout(this);suggestionRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(suggestionRow,new LinearLayout.LayoutParams(-1,dp(44)));
        for(int i=0;i<3;i++) {
            final int pos=i;
            TextView v=button("",16,0xff22323d,42);
            v.setTextColor(i==1?0xffffd57b:TEXT); v.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);p.setMargins(dp(2),0,dp(2),0);
            suggestionRow.addView(v,p);suggestions[i]=v;
            v.setOnClickListener(w -> chooseSuggestion(pos));
        }
        caretIndicator=button("",13,0xff24313c,28);
        caretIndicator.setGravity(Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL);
        caretIndicator.setVisibility(View.GONE);
        caretIndicator.setOnClickListener(v -> {
            if(CursorNavigator.collapseToEnd(getCurrentInputConnection())) {
                if(Build.VERSION.SDK_INT>=31)getCurrentInputConnection().setImeConsumesInput(false);
                refreshSuggestions();
            }
        });
        root.addView(caretIndicator,new LinearLayout.LayoutParams(-1,dp(28)));
        toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER);
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(37)));
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setVisibility(View.GONE);
        root.addView(panel,new LinearLayout.LayoutParams(-1,-2));
        keys=new LinearLayout(this);keys.setOrientation(LinearLayout.VERTICAL);
        root.addView(keys,new LinearLayout.LayoutParams(-1,-2));
        renderToolbar();renderKeyboard();refreshSuggestions();
        return root;
    }
    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute,restarting);
        info=attribute;sensitive=isPrivateField(attribute);
        int cls = attribute == null ? 0 : attribute.inputType & InputType.TYPE_MASK_CLASS;
        mode = (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) ? "numbers" : "letters";
        shift=false;caps=false;manualShiftPending=false;gifQuery="";
        searchRevision++;predictionRevision++;recentEvent=null;
        if(root!=null){renderToolbar();renderKeyboard();refreshSuggestions();}
    }
    @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute,restarting);
        info=attribute;sensitive=isPrivateField(attribute);
        if(!caps){manualShiftPending=false;updateAutoShift();}
        InputConnection ic=getCurrentInputConnection();
        if(Build.VERSION.SDK_INT>=31 && ic!=null)ic.setImeConsumesInput(false);
        refreshSettings();refreshSuggestions();
    }
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd) {
        super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);
        if(root!=null && "letters".equals(mode)) {
            InputConnection ic=getCurrentInputConnection();
            if(Build.VERSION.SDK_INT>=31 && ic!=null)ic.setImeConsumesInput(false);
            updateAutoShift();
            refreshSuggestions();
        }
    }
    private boolean isPrivateField(EditorInfo e) {
        if(e==null)return true;
        int type=e.inputType;
        int cls=type & InputType.TYPE_MASK_CLASS;
        int var=type & InputType.TYPE_MASK_VARIATION;
        if((e.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0)return true;
        if(cls!=InputType.TYPE_CLASS_TEXT)return true;
        if((type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)!=0)return true;
        return var==InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            var==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            var==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            var==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            var==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            var==InputType.TYPE_TEXT_VARIATION_URI ||
            var==InputType.TYPE_TEXT_VARIATION_FILTER;
    }
    private TextView button(String text,int sp,int fill,int h) {
        TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(TEXT);
        v.setGravity(Gravity.CENTER);v.setBackground(background(fill));
        v.setMinHeight(dp(h));v.setContentDescription(text);
        return v;
    }
    private void renderToolbar() {
        if(toolbar==null)return;
        toolbar.removeAllViews();
        toolbarAction("📋","Zwischenablage",() -> showMode("clipboard"));
        toolbarAction("☻","Emoji",() -> showMode("emoji"));
        toolbarAction("GIF","GIF-Suche",() -> showMode("gif"));
        toolbarAction("←","Cursor nach links",() -> arrow(KeyEvent.KEYCODE_DPAD_LEFT));
        toolbarAction("→","Cursor nach rechts",() -> arrow(KeyEvent.KEYCODE_DPAD_RIGHT));
        toolbarAction(privateMode?"🔒":"◉","Privatmodus",() -> {
            privateMode=!privateMode;
            getSharedPreferences("keyboard_settings",0).edit().putBoolean("incognito",privateMode).apply();
            renderToolbar();refreshSuggestions();toast(privateMode?"Privatmodus an":"Privatmodus aus");
        });
        toolbarAction("⚙","Einstellungen",() -> {
            Intent i=new Intent(this,SettingsActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
    }
    private void toolbarAction(String label,String hint,Runnable handler) {
        TextView t=button(label,16,0xff24313c,33);t.setContentDescription(hint);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(33),1);p.setMargins(dp(2),0,dp(2),0);
        toolbar.addView(t,p);t.setOnClickListener(v->handler.run());
    }
    private void showMode(String requested) {
        if(requested.equals(mode)) requested="letters";
        mode=requested;
        if(!requested.equals("gif"))searchRevision++;
        if(requested.equals("gif")) {
            gifQuery="";gifEditing=true;gifOffset=0;gifGridCells=0;
            gifProvider=getSharedPreferences("keyboard_settings",0).getString("giphy_key","").isEmpty()
                ?"commons":"giphy";
            gifRating=getSharedPreferences("keyboard_settings",0).getString("giphy_rating","r");
        }
        renderKeyboard();refreshSuggestions();
    }
    private void refreshSuggestions() {
        final int revision=++predictionRevision;
        if(suggestions[0]==null)return;
        updateCaretIndicator();
        List<String> ranked=new ArrayList<>();
        String rawBefore=null,rawPrefix="",prev="",prev2="",language="de";
        boolean canPredict=!sensitive && !privateMode && "letters".equals(mode);
        if(canPredict) {
            InputConnection ic=getCurrentInputConnection();
            if(ic!=null) {
                CharSequence selection=ic.getSelectedText(0);
                CharSequence after=ic.getTextAfterCursor(1,0);
                boolean selectionActive=selection!=null && selection.length()>0;
                CharSequence before=ic.getTextBeforeCursor(350,0);
                String beforeStr=before==null?"":before.toString();
                boolean insideWord=after!=null && after.length()>0 &&
                    Character.isLetter(after.charAt(0)) &&
                    !TextContext.fromBeforeCursor(beforeStr).prefix.isEmpty();
                if(!selectionActive && !insideWord) {
                    TextContext c=TextContext.fromBeforeCursor(beforeStr);
                    rawBefore=beforeStr;rawPrefix=c.prefix;prev=c.previous;prev2=c.beforePrevious;
                    language=LanguageContext.detect(beforeStr);
                    ranked=predictions.predict(rawPrefix,prev,prev2,
                        java.util.Collections.emptyMap(),language);
                    updateSuggestionButtons(ranked,rawPrefix,rawBefore,editorWantsCapitalization());
                } else {
                    updateSuggestionButtons(ranked,"",beforeStr,false);
                }
            } else updateSuggestionButtons(ranked,"","",false);
        } else updateSuggestionButtons(ranked,"","",false);
        if(rawBefore==null || !neuralEnabled())return;
        final boolean english="en".equals(language);
        final ChimeEnglish englishModel=neuralModel;
        final GermanNextWord germanModel=germanNeuralModel;
        if((english && englishModel==null)||(!english && germanModel==null))return;
        final String snapshot=rawBefore;
        final String prefix=rawPrefix, a=prev, b=prev2, lang=language;
        final boolean capital=editorWantsCapitalization();
        neuralWorker.execute(() -> {
            if(revision!=predictionRevision || Thread.currentThread().isInterrupted())return;
            Map<String,Double> hints;
            try {
                if(english) hints=neuralScores(englishModel.predict(snapshot,10));
                else hints=germanNeuralScores(germanModel.predict(snapshot,15));
            } catch(Exception ex) {
                android.util.Log.w("KontextTastatur","Offline neural inference unavailable",ex);
                return;
            }
            if(revision!=predictionRevision)return;
            List<String> mixed=predictions.predict(prefix,a,b,hints,lang);
            handler.post(() -> {
                if(active==this && revision==predictionRevision && !sensitive && !privateMode &&
                    "letters".equals(mode))updateSuggestionButtons(mixed,prefix,snapshot,capital);
            });
        });
    }

    /** The middle slot remains highest ranked; apply case only at display time. */
    private void updateSuggestionButtons(List<String> ranked,String prefix,String before,boolean capital) {
        if(suggestions[0]==null)return;
        String language=LanguageContext.detect(before);
        GermanCaseModel casing=germanCaseModel;
        for(int position=0;position<3;position++) {
            int rankedPosition=position==1?0:position==0?1:2;
            if(rankedPosition>=ranked.size()) {
                visibleWords[position]="";continue;
            }
            String candidate=ranked.get(rankedPosition);
            visibleWords[position]="de".equals(language)&&casing!=null?
                casing.apply(candidate,prefix,before,false):
                WordCase.display(candidate,prefix,before,capital);
        }
        for(int i=0;i<3;i++) {
            String value=visibleWords[i];
            suggestions[i].setText(!value.isEmpty()?value:
                (i==1?(sensitive?"Privates Feld":privateMode?"Privatmodus":""):""));
            suggestions[i].setEnabled(!value.isEmpty());
        }
    }
    private void updateCaretIndicator() {
        if(caretIndicator==null)return;
        if(sensitive || !"letters".equals(mode)) {
            caretIndicator.setVisibility(View.GONE);
            return;
        }
        InputConnection ic=getCurrentInputConnection();
        if(ic==null){caretIndicator.setVisibility(View.GONE);return;}
        CharSequence selected=ic.getSelectedText(0);
        if(selected!=null && selected.length()>0) {
            caretIndicator.setText("Markierung: "+selected.length()+" Zeichen · Tippen: Cursor am Ende");
            caretIndicator.setVisibility(View.VISIBLE);
            return;
        }
        CharSequence left=ic.getTextBeforeCursor(16,0);
        CharSequence right=ic.getTextAfterCursor(16,0);
        String a=left==null?"":left.toString(),b=right==null?"":right.toString();
        if(a.isEmpty() && b.isEmpty()) {
            caretIndicator.setVisibility(View.GONE);
            return;
        }
        String leftExcerpt=(a.length()>=16?"…":"")+a;
        String rightExcerpt=b+(b.length()>=16?"…":"");
        caretIndicator.setText(leftExcerpt+"│"+rightExcerpt+
            "   ·   Leertaste halten: Cursor bewegen");
        caretIndicator.setVisibility(View.VISIBLE);
    }

    private void chooseSuggestion(int position) {
        String chosen=visibleWords[position];
        if(chosen==null||chosen.isEmpty()||privateMode||sensitive)return;
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        TextContext ctx=TextContext.fromBeforeCursor(ic.getTextBeforeCursor(500,0));
        CharSequence selection=ic.getSelectedText(0);
        ic.beginBatchEdit();
        if((selection==null||selection.length()==0)&&!ctx.prefix.isEmpty()) ic.deleteSurroundingText(ctx.prefix.length(),0);
        ic.commitText(chosen+" ",1);
        ic.endBatchEdit();
        PredictionModel.Event e=predictions.learnSelection(ctx.prefix,chosen,ctx.previous,ctx.beforePrevious);
        database.persist(e,chosen,1);
        recentEvent=e;recentSurface=chosen;recentAt=System.currentTimeMillis();
        manualShiftPending=false;
        shift=false;updateAutoShift();refreshSuggestions();
    }
    private long lastShiftAt=0;
    private void renderKeyboard() {
        if(keys==null || panel==null)return;
        keys.removeAllViews();panel.removeAllViews();panel.setVisibility(View.GONE);
        if(mode.equals("emoji")){showEmojiPanel();return;}
        if(mode.equals("clipboard")){showClipboardPanel();return;}
        if(mode.equals("gif"))showGifPanel();
        else keys.setVisibility(View.VISIBLE);
        if(mode.equals("numbers")) {
            row(new String[]{"1","2","3"},null);
            row(new String[]{"4","5","6"},null);
            row(new String[]{"7","8","9"},null);
            row(new String[]{"-","0","⌫"},null);
            row(new String[]{"ABC",",",".","↵"},new float[]{1.5f,1,1,1.5f});
            return;
        }
        if(mode.equals("symbols")) {
            row(new String[]{"1","2","3","4","5","6","7","8","9","0"},null);
            row(new String[]{"@","#","€","%","&","-","+","(",")","/"},null);
            row(new String[]{"=","*","\"","'",":",";","!","?","_","⌫"},null);
        } else {
            // Numeric shortcuts are permanently available without switching modes.
            row(new String[]{"1","2","3","4","5","6","7","8","9","0"},null);
            row(new String[]{"q","w","e","r","t","z","u","i","o","p","ü"},null);
            row(new String[]{"a","s","d","f","g","h","j","k","l","ö","ä"},null);
            row(new String[]{"⇧","y","x","c","v","b","n","m","ß","⌫"},
                new float[]{1.35f,1,1,1,1,1,1,1,1,1.35f});
        }
        row(new String[]{mode.equals("symbols")?"ABC":"?123",",","☻","Leerzeichen",".","↵"},
            new float[]{1.45f,0.8f,0.8f,4.2f,0.8f,1.35f});
    }
    private void row(String[] captions,float[] widths) {
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(49));
        rp.bottomMargin=dp(3);keys.addView(row,rp);
        for(int i=0;i<captions.length;i++) {
            final String original=captions[i];
            boolean character=original.length()==1 && Character.isLetter(original.charAt(0));
            String output=character && (shift||caps) ? (original.equals("ß")?"ẞ":original.toUpperCase(Locale.GERMAN)):original;
            TextView v=button(output,original.equals("Leerzeichen")?13:20,
                original.equals("⌫")||original.equals("⇧")||original.equals("↵")?PRESSED:KEY,45);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(45),widths==null?1:widths[i]);
            p.setMargins(dp(2),dp(1),dp(2),dp(1));row.addView(v,p);
            v.setOnClickListener(w -> press(original));
            if("Leerzeichen".equals(original)) {
                v.setContentDescription("Leerzeichen. Halten und links oder rechts schieben, um den Cursor zu bewegen.");
                v.setOnTouchListener(this::spaceTouch);
            }
            String favorite=KeyPriority.favorite(original,shift||caps);
            if(favorite!=null) {
                String[] choices=KeyPriority.alternatives(original,shift||caps);
                // Release after an extended hold inserts the preferred umlaut.
                // Drag UP while holding to access the less frequently used popup.
                v.setContentDescription(output+"; lange halten für "+favorite+
                    "; nach oben ziehen für weitere Sonderzeichen");
                v.setOnTouchListener(new View.OnTouchListener() {
                    long start;
                    float downX,downY;
                    boolean submenu;
                    @Override public boolean onTouch(View target,MotionEvent event) {
                        switch(event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                start=android.os.SystemClock.uptimeMillis();
                                downX=event.getX();downY=event.getY();submenu=false;
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                if(!submenu && downY-event.getY()>dp(36)) {
                                    submenu=true;
                                    PopupMenu popup=new PopupMenu(AdaptiveKeyboardService.this,v);
                                    for(String choice:choices)popup.getMenu().add(choice);
                                    popup.setOnMenuItemClickListener(item -> {
                                        enterLetter(item.getTitle().toString());return true;
                                    });
                                    popup.show();
                                    target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                }
                                return true;
                            case MotionEvent.ACTION_UP:
                                if(!submenu) {
                                    long held=android.os.SystemClock.uptimeMillis()-start;
                                    if(KeyPriority.longHold(held,Math.abs(event.getX()-downX),
                                        Math.max(0,downY-event.getY()))) {
                                        enterLetter(KeyPriority.favorite(original,shift||caps));
                                    } else press(original);
                                }
                                return true;
                            case MotionEvent.ACTION_CANCEL:return true;
                            default:return true;
                        }
                    }
                });
            } else {
                String[] alternates=alternates(original);
                if(alternates!=null) v.setOnLongClickListener(w -> {
                    PopupMenu popup=new PopupMenu(this,v);
                    for(String option:alternates)popup.getMenu().add(option);
                    popup.setOnMenuItemClickListener(item -> {
                        enterLetter(item.getTitle().toString());return true;
                    });
                    popup.show();return true;
                });
            }
            if(original.equals("⌫"))v.setOnTouchListener((view,event) -> {
                if(event.getAction()==MotionEvent.ACTION_DOWN) {
                    backspaceRepeat=new Runnable() {
                        @Override public void run() {backspace();handler.postDelayed(this,70);}
                    };
                    handler.postDelayed(backspaceRepeat,420);
                } else if(event.getAction()==MotionEvent.ACTION_UP || event.getAction()==MotionEvent.ACTION_CANCEL) {
                    if(backspaceRepeat!=null)handler.removeCallbacks(backspaceRepeat);
                }
                return false;
            });
        }
    }
    /** Short tap types one space. Hold then move changes only cursor selection. */
    private boolean spaceTouch(View view,MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            if(spaceHold!=null)handler.removeCallbacks(spaceHold);
            activeSpaceKey=(TextView)view;
            spaceDownX=event.getX();spaceLatestX=event.getX();
            spaceSwipe=new SpaceSwipe(spaceDownX);
            spaceHold=() -> {
                if(spaceSwipe==null || activeSpaceKey!=view || spaceSwipe.active())return;
                spaceSwipe.activate(spaceLatestX);
                activeSpaceKey.setText("◀ Cursor ▶");
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            };
            handler.postDelayed(spaceHold,330);
            return true;
        }
        if(spaceSwipe==null)return true;
        if(action==MotionEvent.ACTION_MOVE) {
            spaceLatestX=event.getX();
            if(!spaceSwipe.active() && Math.abs(spaceLatestX-spaceDownX)>dp(22)) {
                if(spaceHold!=null)handler.removeCallbacks(spaceHold);
                spaceSwipe.activate(spaceDownX);
                activeSpaceKey.setText("◀ Cursor ▶");
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            if(spaceSwipe.active()) {
                int delta=spaceSwipe.move(spaceLatestX,dp(12));
                if(delta!=0) {
                    InputConnection ic=getCurrentInputConnection();
                    if(CursorNavigator.move(ic,delta)) {
                        if(Build.VERSION.SDK_INT>=31)ic.setImeConsumesInput(false);
                        recentEvent=null;
                        refreshSuggestions();
                    }
                }
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
            if(spaceHold!=null)handler.removeCallbacks(spaceHold);
            boolean dragging=spaceSwipe.active();
            spaceSwipe=null;spaceHold=null;
            if(activeSpaceKey!=null)activeSpaceKey.setText("Leerzeichen");
            activeSpaceKey=null;
            if(!dragging && action==MotionEvent.ACTION_UP)enterDelimiter(" ");
            else refreshSuggestions();
            return true;
        }
        return true;
    }

    private String[] alternates(String c) {
        switch(c) {
            case "a": return new String[]{"ä","á","à","â","ã","å","æ"};
            case "o": return new String[]{"ö","ó","ò","ô","ø","œ"};
            case "u": return new String[]{"ü","ú","ù","û"};
            case "s": return new String[]{"ß","§","ś"};
            case "e": return new String[]{"é","è","ê","ë","€"};
            case "i": return new String[]{"í","ì","î","ï"};
            case "c": return new String[]{"ç","©"};
            case "n": return new String[]{"ñ"};
            case "-": return new String[]{"–","—","−"};
            case ".": return new String[]{"…","?","!"};
            case ",": return new String[]{";",":"};
            default: return null;
        }
    }
    private void press(String key) {
        switch(key) {
            case "⇧":
                if(System.currentTimeMillis()-lastShiftAt<420) {caps=!caps;shift=caps;}
                else {shift=!shift;caps=false;}
                manualShiftPending=true;
                lastShiftAt=System.currentTimeMillis();renderKeyboard();break;
            case "⌫":backspace();break;
            case "?123":mode="symbols";renderKeyboard();refreshSuggestions();break;
            case "ABC":mode="letters";renderKeyboard();refreshSuggestions();break;
            case "☻":showMode("emoji");break;
            case "Leerzeichen":enterDelimiter(" ");break;
            case "↵":enterDelimiter("\n");break;
            default:
                if("'".equals(key)||"’".equals(key)) enterLetter(key);
                else if(key.length()==1 && !Character.isLetter(key.charAt(0)))enterDelimiter(key);
                else enterLetter((shift||caps)?(key.equals("ß")?"ẞ":key.toUpperCase(Locale.GERMAN)):key);
        }
    }
    private void enterLetter(String letter) {
        if(mode.equals("gif")) {
            gifQuery+=letter.toLowerCase(Locale.GERMAN);
            updateGifStatus();return;
        }
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        // A visible completion that the next character contradicts is a weak
        // negative signal. Continuing to type NEVER counts as a selection.
        if(!privateMode&&!sensitive && mode.equals("letters") && letter.length()==1 &&
            (Character.isLetter(letter.charAt(0)) || letter.equals("'"))) {
            CharSequence before=ic.getTextBeforeCursor(400,0);
            TextContext context=TextContext.fromBeforeCursor(before);
            String newPrefix=TextContext.normalize(context.prefix+letter);
            if(context.prefix.length()>=2 &&
                (ic.getSelectedText(0)==null||ic.getSelectedText(0).length()==0)) {
                for(String suggestion:visibleWords) {
                    if(suggestion==null||suggestion.isEmpty())continue;
                    String old=TextContext.normalize(suggestion);
                    if(old.startsWith(TextContext.normalize(context.prefix)) && !old.startsWith(newPrefix)) {
                        PredictionModel.Rejection r=predictions.learnRejection(
                            context.prefix,suggestion,context.previous,context.beforePrevious);
                        database.persistRejection(r);
                    }
                }
            }
        }
        ic.commitText(letter,1);
        manualShiftPending=false;
        recentEvent=null;
        if(shift&&!caps && !letter.equals("'")) {shift=false;renderKeyboard();}
        refreshSuggestions();
    }
    private void enterDelimiter(String symbol) {
        if(mode.equals("gif")) {
            if(symbol.equals("\n"))searchGif();
            else {gifQuery+=symbol;updateGifStatus();}
            return;
        }
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        CharSequence before=ic.getTextBeforeCursor(500,0);
        TextContext c=TextContext.fromBeforeCursor(before);
        if(!sensitive && !privateMode && !c.prefix.isEmpty() && isAtWordEnd(ic) &&
            (ic.getSelectedText(0)==null || ic.getSelectedText(0).length()==0)) {
            PredictionModel.Event e=predictions.learnTyped(c.prefix,c.previous,c.beforePrevious);
            database.persist(e,c.prefix,1);
            recentEvent=e;recentSurface=c.prefix;recentAt=System.currentTimeMillis();
        }
        if(symbol.equals("\n")) {
            int action=info==null?0:(info.imeOptions & EditorInfo.IME_MASK_ACTION);
            boolean multiline=info!=null&&(info.inputType&InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0;
            if(action!=EditorInfo.IME_ACTION_NONE && action!=EditorInfo.IME_ACTION_UNSPECIFIED && !multiline) {
                ic.performEditorAction(action);
            } else ic.commitText("\n",1);
            manualShiftPending=false;
            updateAutoShift();
        } else {
            if(symbol.equals(" ") && before!=null && before.length()>0 && before.charAt(before.length()-1)==' ') {
                // No unexpected duplicate spaces after accepting a suggestion.
            } else {
                if(".,!?;:".contains(symbol) && before!=null && before.length()>0 && before.charAt(before.length()-1)==' ')
                    ic.deleteSurroundingText(1,0);
                ic.commitText(symbol,1);
            }
            manualShiftPending=false;
            // Use the newly committed editor contents, not the old cursor mode.
            updateAutoShift();
        }
        if(shift&&!caps)renderKeyboard();
        refreshSuggestions();
    }
    private boolean isAtWordEnd(InputConnection ic) {
        CharSequence after=ic.getTextAfterCursor(1,0);
        return after==null || after.length()==0 || !Character.isLetter(after.charAt(0));
    }
    private void backspace() {
        if(mode.equals("gif")) {
            if(!gifQuery.isEmpty())gifQuery=gifQuery.substring(0,gifQuery.offsetByCodePoints(gifQuery.length(),-1));
            updateGifStatus();return;
        }
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        CharSequence selection=ic.getSelectedText(0);
        CharSequence before=ic.getTextBeforeCursor(Math.max(2,recentSurface.length()+2),0);
        if(selection!=null && selection.length()>0) {
            ic.commitText("",1);
            recentEvent=null;
        } else if(before!=null && before.length()>0) {
            // Removing just the delimiter does NOT reject an accepted suggestion.
            // Retract learning only when the user actually starts removing that word.
            boolean deletingFromConfirmedWord = recentEvent!=null &&
                System.currentTimeMillis()-recentAt<3500 && !sensitive && !privateMode &&
                !recentSurface.isEmpty() && before.toString().endsWith(recentSurface);
            if(deletingFromConfirmedWord) {
                predictions.undo(recentEvent);
                database.persist(recentEvent,recentSurface,-1);
            }
            if (deletingFromConfirmedWord) recentEvent=null;
            ic.deleteSurroundingText(1,0);
        } else ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL));
        refreshSuggestions();
    }
    private void arrow(int keycode) {
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        int by=keycode==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;
        CursorNavigator.move(ic,by);
        if(Build.VERSION.SDK_INT>=31)ic.setImeConsumesInput(false);
        recentEvent=null;refreshSuggestions();
    }
    private void toast(String text) {Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private void panelHeader(String title,Runnable back) {
        panel.setVisibility(View.VISIBLE);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(row,new LinearLayout.LayoutParams(-1,dp(38)));
        TextView name=button(title,15,0xff24313c,36);
        row.addView(name,new LinearLayout.LayoutParams(0,dp(36),1));
        TextView close=button("ABC",16,KEY,36);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(66),dp(36));p.leftMargin=dp(4);
        row.addView(close,p);close.setOnClickListener(v->back.run());
    }
    private void showEmojiPanel() {
        panelHeader("Emojis",() -> showMode("letters"));
        String[] categories={"Gesichter","Herzen","Hände","Tiere","Essen","Symbole"};
        String[][] emoji={
            {"😀","😃","😄","😁","😆","😅","😂","🤣","😊","🙂","🙃","😉","😍","🥰","😘","😎","🤔","😐","😢","😭","😡","🤯","😴","🤢","🥳","🤩","😇","😏","🥺","🫠","🙄","😬","😋","🤗","😜","🤓"},
            {"❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💕","💞","💓","💗","💖","💘","💝","💔","❤️‍🔥","💯","💫","✨"},
            {"👍","👎","👌","✌️","🤞","🤟","🤘","👏","🙌","👋","🙏","💪","🤝","🤌","👉","👈","👆","👇","✍️","👀","🫶"},
            {"🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐦","🐧","🦄","🐝","🦋","🐢","🐙"},
            {"🍎","🍐","🍓","🍉","🍌","🥑","🥕","🥨","🍞","🧀","🍕","🍔","🍟","🌭","🌮","🍣","🍩","🍪","🍰","☕","🍺","🍷"},
            {"✅","❌","⚠️","❓","❗","🔥","⭐","🌟","🎉","🎂","🎁","🎶","💬","📱","💻","🚗","🏠","🌍","⏰","🔒","📌","💡"}
        };
        HorizontalScrollView tabs=new HorizontalScrollView(this);tabs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow=new LinearLayout(this);tabs.addView(tabRow);
        panel.addView(tabs,new LinearLayout.LayoutParams(-1,dp(40)));
        ScrollView scroller=new ScrollView(this);
        panel.addView(scroller,new LinearLayout.LayoutParams(-1,dp(220)));
        GridLayout grid=new GridLayout(this);grid.setColumnCount(7);scroller.addView(grid);
        for(int i=0;i<categories.length;i++) {
            final int idx=i;
            TextView tab=button(categories[i],13,KEY,34);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(77),dp(34));p.setMargins(dp(2),0,dp(2),0);
            tabRow.addView(tab,p);
            tab.setOnClickListener(v->fillEmojiGrid(grid,emoji[idx]));
        }
        fillEmojiGrid(grid,emoji[0]);
    }
    private void fillEmojiGrid(GridLayout grid,String[] icons) {
        grid.removeAllViews();
        for(String emoji:icons) {
            TextView v=button(emoji,23,KEY,44);
            GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=dp(45);p.height=dp(45);
            p.setMargins(dp(2),dp(2),dp(2),dp(2));grid.addView(v,p);
            v.setOnClickListener(w -> {
                InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.commitText(emoji,1);
            });
        }
    }
    private void showClipboardPanel() {
        panelHeader("Zwischenablage (lokal angeheftet)",() -> showMode("letters"));
        LinearLayout actions=new LinearLayout(this);panel.addView(actions,new LinearLayout.LayoutParams(-1,dp(43)));
        clipboardAction(actions,"Kopieren",() -> contextAction(android.R.id.copy));
        clipboardAction(actions,"Einfügen",() -> contextAction(android.R.id.paste));
        clipboardAction(actions,"Ausschneiden",() -> contextAction(android.R.id.cut));
        if(sensitive) {
            TextView note=button("In diesem Feld ist der Verlauf ausgeblendet",14,BG,58);
            panel.addView(note);return;
        }
        clipboardAction(actions,"Anheften",() -> {
            ClipboardManager mgr=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            ClipData clip=mgr==null?null:mgr.getPrimaryClip();
            if(clip!=null&&clip.getItemCount()>0) {
                CharSequence text=clip.getItemAt(0).coerceToText(this);
                if(text!=null&&!text.toString().isEmpty()) {
                    database.saveClip(text.toString());toast("In der App-Zwischenablage gespeichert");
                    handler.postDelayed(this::renderKeyboard,100);
                }
            } else toast("Zwischenablage ist leer");
        });
        ScrollView history=new ScrollView(this);panel.addView(history,new LinearLayout.LayoutParams(-1,dp(180)));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);history.addView(list);
        List<String> entries=database.clipboard();
        if(entries.isEmpty()) {
            TextView empty=button("Noch nichts angeheftet. Erst kopieren, dann Anheften drücken.",13,BG,50);
            list.addView(empty,new LinearLayout.LayoutParams(-1,dp(50)));
        }
        for(String clip:entries) {
            String caption=clip.length()>65?clip.substring(0,65)+"…":clip;
            TextView row=button(caption,14,KEY,44);
            row.setMaxLines(2);row.setPadding(dp(8),0,dp(8),0);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(44));p.bottomMargin=dp(3);
            list.addView(row,p);
            row.setOnClickListener(v -> {InputConnection ic=getCurrentInputConnection();
                if(ic!=null)ic.commitText(clip,1);showMode("letters");});
        }
        TextView clear=button("Angeheftete Einträge löschen",12,0xff6f3540,36);
        list.addView(clear,new LinearLayout.LayoutParams(-1,dp(36)));
        clear.setOnClickListener(v -> {database.clearClipboard();toast("Lokale Zwischenablage wird gelöscht");showMode("letters");});
    }
    private void clipboardAction(LinearLayout parent,String label,Runnable click) {
        TextView t=button(label,12,KEY,38);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),1);p.setMargins(dp(2),0,dp(2),0);
        parent.addView(t,p);t.setOnClickListener(v->click.run());
    }
    private void contextAction(int action) {
        InputConnection ic=getCurrentInputConnection();
        if(ic!=null)ic.performContextMenuAction(action);
        if(action==android.R.id.paste)showMode("letters");
    }
    private void gifToolbarButton(LinearLayout row,String name,boolean selected,Runnable action) {
        TextView button=button(name,12,selected?PRESSED:KEY,36);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(36),1);
        params.setMargins(dp(2),0,dp(2),0);
        row.addView(button,params);
        button.setOnClickListener(v->action.run());
    }
    private void showGifPanel() {
        panelHeader(gifProvider.equals("giphy")?"GIF-Suche · Powered By GIPHY":
            "GIF-Suche · Quellen auswählen",() -> showMode("letters"));
        LinearLayout sources=new LinearLayout(this);
        panel.addView(sources,new LinearLayout.LayoutParams(-1,dp(38)));
        gifToolbarButton(sources,"GIPHY",gifProvider.equals("giphy"),() -> {
            String api=getSharedPreferences("keyboard_settings",0).getString("giphy_key","");
            if(api.isEmpty()) {
                gifStatus.setText("GIPHY braucht einen kostenlosen API-Schlüssel. Einstellungen öffnen.");
                Intent intent=new Intent(this,SettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                requestHideSelf(0);
                startActivity(intent);
                return;
            }
            setGifProvider("giphy");
        });
        gifToolbarButton(sources,"Wikimedia",gifProvider.equals("commons"),() -> setGifProvider("commons"));
        gifToolbarButton(sources,"Tenor-Web",false,this::openTenorWeb);
        LinearLayout queryRow=new LinearLayout(this);
        panel.addView(queryRow,new LinearLayout.LayoutParams(-1,dp(34)));
        gifQueryDisplay=button("",14,KEY,33);
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(0,dp(33),1);
        qp.setMargins(dp(2),0,dp(2),0);queryRow.addView(gifQueryDisplay,qp);
        gifQueryDisplay.setOnClickListener(v->setGifEditing(true));
        TextView clear=button("×",20,PRESSED,33);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(36),dp(33));
        cp.setMargins(dp(2),0,dp(2),0);queryRow.addView(clear,cp);
        clear.setOnClickListener(v->{
            gifQuery="";searchRevision++;gifOffset=0;gifGridCells=0;
            gifResults.removeAllViews();
            updateGifStatus();setGifEditing(true);
        });
        gifStatus=button("",12,BG,36);
        panel.addView(gifStatus,new LinearLayout.LayoutParams(-1,dp(36)));
        gifScroll=new ScrollView(this);
        gifScroll.setFillViewport(false);
        panel.addView(gifScroll,new LinearLayout.LayoutParams(-1,dp(130)));
        gifResults=new LinearLayout(this);
        gifResults.setOrientation(LinearLayout.VERTICAL);
        gifScroll.addView(gifResults,new ScrollView.LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(this);
        panel.addView(actions,new LinearLayout.LayoutParams(-1,dp(38)));
        gifToolbarButton(actions,"Suchen / Trends",false,()->searchGif(false));
        gifToolbarButton(actions,"Mehr GIFs",false,()->searchGif(true));
        gifToolbarButton(actions,"Tastatur",false,()->setGifEditing(true));
        updateGifStatus();
        updateGifLayout();
    }
    private void setGifProvider(String value) {
        if(value.equals(gifProvider))return;
        gifProvider=value;
        searchRevision++;
        gifOffset=0;gifGridCells=0;
        gifEditing=true;
        renderKeyboard();
        refreshSuggestions();
    }
    private void openTenorWeb() {
        String phrase=gifQuery.trim();
        // Tenor's website search remains accessible, while its third-party
        // programming API has been shut down. A browser, not the IME, handles it.
        String slug=phrase.isEmpty()?"happy":phrase.replaceAll("\s+","-");
        Uri uri=new Uri.Builder().scheme("https").authority("tenor.com")
            .appendPath("search").appendPath(slug+"-gifs").build();
        Intent intent=new Intent(Intent.ACTION_VIEW,uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {requestHideSelf(0);startActivity(intent);}
        catch(Exception e) {gifStatus.setText("Tenor konnte im Browser nicht geöffnet werden.");}
    }
    private void setGifEditing(boolean editing) {
        gifEditing=editing;
        updateGifLayout();
    }
    private void updateGifLayout() {
        if(keys==null || gifScroll==null || !mode.equals("gif"))return;
        keys.setVisibility(gifEditing?View.VISIBLE:View.GONE);
        LinearLayout.LayoutParams scrollParams=(LinearLayout.LayoutParams)gifScroll.getLayoutParams();
        scrollParams.height=dp(gifEditing?125:315);
        gifScroll.setLayoutParams(scrollParams);
    }
    private void updateGifStatus() {
        if(!mode.equals("gif"))return;
        if(gifQueryDisplay!=null)
            gifQueryDisplay.setText(gifQuery.isEmpty()?"Suchbegriff eingeben / Trends":"Suche: "+gifQuery);
        if(gifStatus!=null && gifResults!=null && gifGridCells==0) {
            String api=getSharedPreferences("keyboard_settings",0).getString("giphy_key","");
            gifStatus.setText(gifProvider.equals("giphy")?
                (api.isEmpty()?"GIPHY-Schlüssel fehlt":"GIPHY · Filter: "+gifRating.toUpperCase(Locale.ROOT)):
                "Wikimedia: freie Bilder, kaum Reaktions-GIFs");
        }
    }
    private void searchGif() {searchGif(false);}
    private void searchGif(boolean more) {
        if(!mode.equals("gif"))return;
        final String query=gifQuery.trim();
        final String provider=gifProvider;
        final String key=getSharedPreferences("keyboard_settings",0).getString("giphy_key","");
        final String rating=gifRating;
        if(provider.equals("commons") && query.isEmpty()) {
            gifStatus.setText("Wikimedia benötigt einen Suchbegriff. Für Reaktions-GIFs GIPHY verwenden.");
            return;
        }
        if(provider.equals("giphy") && key.isEmpty()) {
            gifStatus.setText("GIPHY: API-Schlüssel in Einstellungen eintragen.");
            return;
        }
        if(more && !provider.equals("giphy")) {
            gifStatus.setText("Weitere Seiten nur mit GIPHY verfügbar.");
            return;
        }
        final int offset=more?gifOffset+24:0;
        final int request=++searchRevision;
        gifStatus.setText(more?"Weitere GIFs werden geladen":"GIF-Suche läuft: "+query);
        if(!more){gifResults.removeAllViews();gifGridCells=0;gifOffset=0;}
        gifPreviewsExpected=0;gifPreviewsLoaded=0;gifPreviewsFailed=0;
        searchWorker.execute(() -> {
            try {
                List<GiphySearch.Gif> results=provider.equals("giphy")?
                    GiphySearch.searchGiphy(key,query,rating,offset):GiphySearch.searchOpenverse(query);
                handler.post(() -> {
                    if(request!=searchRevision||!mode.equals("gif"))return;
                    gifOffset=offset;
                    gifPreviewsExpected=results.size();
                    gifStatus.setText(results.isEmpty()?"Keine Treffer in "+
                        (provider.equals("giphy")?"GIPHY":"Wikimedia"):
                        "GIFs: "+results.size()+" geladen · Vorschau läuft");
                    for(GiphySearch.Gif gif:results)addGifPreview(gif,request);
                    if(!results.isEmpty())setGifEditing(false);
                });
            } catch(Exception error) {
                handler.post(() -> {if(request==searchRevision&&mode.equals("gif"))
                    gifStatus.setText(GiphySearch.userErrorMessage(error));});
            }
        });
    }
    private void updateGifPreviewProgress(int request, boolean success) {
        if(request!=searchRevision || !mode.equals("gif") || gifStatus==null)return;
        if(success)gifPreviewsLoaded++;else gifPreviewsFailed++;
        String status=gifStatus.getText().toString();
        // Image loading may continue after an explicit share/copy action;
        // it must not overwrite the more important share/copy result.
        if(!status.startsWith("GIFs:") && !status.startsWith("Vorschau"))return;
        int finished=gifPreviewsLoaded+gifPreviewsFailed;
        if(finished==gifPreviewsExpected && gifPreviewsLoaded==0)
            gifStatus.setText("GIFs gefunden, Vorschau nicht verfügbar. Titel antippen oder erneut suchen.");
        else gifStatus.setText("Vorschau "+finished+"/"+gifPreviewsExpected+
            (gifPreviewsFailed>0?" · "+gifPreviewsFailed+" nicht geladen":""));
    }

    private void addGifPreview(GiphySearch.Gif gif,int request) {
        FrameLayout tile=new FrameLayout(this);
        tile.setBackground(background(KEY));
        tile.setContentDescription(gif.title+" · "+gif.attribution);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(105),1);
        p.setMargins(dp(2),dp(2),dp(2),dp(2));
        LinearLayout row;
        if(gifGridCells%3==0) {
            row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            gifResults.addView(row,new LinearLayout.LayoutParams(-1,dp(109)));
        } else row=(LinearLayout)gifResults.getChildAt(gifResults.getChildCount()-1);
        row.addView(tile,p);
        gifGridCells++;
        ImageView imageView=new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setImageResource(android.R.drawable.ic_menu_gallery);
        tile.addView(imageView,new FrameLayout.LayoutParams(-1,-1));
        TextView name=button(gif.title,10,0xd522323d,23);
        name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(23),Gravity.BOTTOM);
        tile.addView(name,lp);
        tile.setOnClickListener(w->sendGif(gif));
        tile.setOnLongClickListener(w -> {
            PopupMenu popup=new PopupMenu(this,tile);
            popup.getMenu().add("GIF als Datei teilen").setOnMenuItemClickListener(item -> {
                sendGif(gif,true);return true;
            });
            popup.getMenu().add("GIF in Zwischenablage kopieren").setOnMenuItemClickListener(item -> {
                copyGif(gif);return true;
            });
            popup.show();return true;
        });
        network.execute(() -> {
            if(request!=searchRevision || !mode.equals("gif"))return;
            byte[] preview=previewCache.get(gif.thumbnail);
            if(preview==null) {
                try {
                    preview=GiphySearch.requestPreview(gif.thumbnail,1_500_000);
                } catch(Exception thumbFailure) {
                    try {preview=GiphySearch.requestPreview(gif.original,1_800_000);}
                    catch(Exception sourceFailure) {
                        android.util.Log.w("KontextTastatur","GIF preview unavailable: "+
                            GiphySearch.userErrorMessage(sourceFailure));
                    }
                }
                if(preview!=null)previewCache.put(gif.thumbnail,preview);
            }
            final byte[] imageBytes=preview;
            handler.post(() -> {
                if(request!=searchRevision || !mode.equals("gif"))return;
                boolean success=false;
                if(imageBytes!=null) {
                    try {
                        if(Build.VERSION.SDK_INT>=28) {
                            Drawable image=ImageDecoder.decodeDrawable(
                                ImageDecoder.createSource(ByteBuffer.wrap(imageBytes)),
                                (decoder,info,source)-> {
                                    android.util.Size size=info.getSize();
                                    int longest=Math.max(size.getWidth(),size.getHeight());
                                    if(longest>256) {
                                        double factor=256.0/longest;
                                        decoder.setTargetSize(Math.max(1,(int)(size.getWidth()*factor)),
                                            Math.max(1,(int)(size.getHeight()*factor)));
                                    }
                                });
                            imageView.setImageDrawable(image);
                            if(image instanceof AnimatedImageDrawable)
                                ((AnimatedImageDrawable)image).start();
                        } else imageView.setImageBitmap(
                            android.graphics.BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.length));
                        success=true;
                    } catch(Exception invalid) {
                        android.util.Log.w("KontextTastatur","GIF decode failed",invalid);
                    }
                }
                updateGifPreviewProgress(request,success);
            });
        });
    }
    private void sendGif(GiphySearch.Gif gif) { sendGif(gif,false); }

    private void copyGif(GiphySearch.Gif gif) {
        gifStatus.setText("GIF wird für die Zwischenablage geladen …");
        sendWorker.execute(() -> {
            try {
                File file=GiphySearch.cacheGif(getCacheDir(),gif);
                handler.post(() -> {
                    try {
                        Uri uri=GifSharing.uriFor(this,file.getName());
                        GifSharing.copyToClipboard(this,uri,gif.title);
                        gifStatus.setText("GIF als Bild kopiert · Jetzt in der App einfügen");
                    } catch(Exception e) {
                        gifStatus.setText("GIF konnte nicht kopiert werden");
                        android.util.Log.w("KontextTastatur","GIF clipboard failed",e);
                    }
                });
            } catch(Exception e) {
                handler.post(() -> {if(gifStatus!=null)gifStatus.setText("GIF konnte nicht geladen werden");});
            }
        });
    }

    private void sendGif(GiphySearch.Gif gif, boolean forceShare) {
        if(gifStatus==null)return;
        gifStatus.setText("GIF-Datei wird heruntergeladen …");
        sendWorker.execute(() -> {
            try {
                File file=GiphySearch.cacheGif(getCacheDir(),gif);
                handler.post(() -> {
                    Uri uri=GifSharing.uriFor(this,file.getName());
                    InputConnection ic=getCurrentInputConnection();
                    boolean supported=!forceShare && ic!=null && info!=null &&
                        GifDeliveryPolicy.supportsDirectGif(info.contentMimeTypes);
                    boolean committed=false;
                    if(supported) {
                        try {
                            InputContentInfo content=new InputContentInfo(uri,
                                new ClipDescription(gif.title,new String[]{"image/gif"}),
                                gif.link.startsWith("https://")?Uri.parse(gif.link):null);
                            committed=ic.commitContent(content,
                                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null);
                        } catch(Exception ex) {
                            android.util.Log.w("KontextTastatur","GIF rich commit failed",ex);
                        }
                    }
                    if(GifDeliveryPolicy.route(committed)==GifDeliveryPolicy.Route.DIRECT_GIF) {
                        gifStatus.setText("GIF als Bild an die App übergeben");
                    } else {
                        try {
                            // A plain EditText does not support image/gif. A real binary
                            // sharesheet can still transfer the GIF; never insert the
                            // Wikimedia landing-page URL as if it were the image.
                            GifSharing.share(this,uri,gif.title);
                            gifStatus.setText("App unterstützt kein direktes GIF. Bild im Teilen-Menü auswählen.");
                        } catch(android.content.ActivityNotFoundException exception) {
                            gifStatus.setText("Keine App zum Teilen von GIFs gefunden");
                        } catch(Exception exception) {
                            gifStatus.setText("GIF-Teilen nicht möglich");
                            android.util.Log.e("KontextTastatur","GIF sharing failed",exception);
                        }
                    }
                });
            } catch(Exception e) {
                handler.post(() -> {if(gifStatus!=null)gifStatus.setText("GIF-Datei konnte nicht geladen werden");});
            }
        });
    }

}
