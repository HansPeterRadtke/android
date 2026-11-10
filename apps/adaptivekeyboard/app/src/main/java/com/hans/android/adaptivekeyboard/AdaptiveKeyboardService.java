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
import android.view.inputmethod.SurroundingText;
import android.widget.EditText;
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
import java.util.Collections;

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
    // Cheap word ranking must never run on the UI/input thread either.
    private final ThreadPoolExecutor fastPredictWorker=new ThreadPoolExecutor(1,1,0L,
        TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r -> {
            Thread t=new Thread(r,"keyboard-personal-predict");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.DiscardOldestPolicy());
    private Runnable scheduledSnapshot,scheduledNeural,scheduledPendingControls;
    private final java.util.ArrayList<TextView> letterViews=new java.util.ArrayList<>(40);
    private final java.util.ArrayList<String> letterNames=new java.util.ArrayList<>(40);
    private volatile int observedRevision=0;
    private long uiKeyTotalNs,uiKeyMaxNs,uiCommitTotalNs,uiCommitMaxNs;
    private int uiKeyCount,uiSelectionUpdates,uiSnapshotCount;
    private int lastCandidateRevision=-1;
    private long uiSnapshotTotalNs,uiSnapshotMaxNs;
    private static final int REFRESH_DELAY_MS=55,NEURAL_DELAY_MS=140;
    private volatile ChimeEnglish neuralModel;
    private volatile GermanNextWord germanNeuralModel;
    private volatile GermanCaseModel germanCaseModel;
    private volatile int predictionRevision=0;
    private boolean manualShiftPending=false;
    private final ShiftController shiftController=new ShiftController();
    private TextView shiftKeyView;
    private List<String> lastRankedSuggestionWords=Collections.emptyList();
    private String lastSuggestionPrefix="",lastSuggestionBefore="";
    private List<String> lastRawPhraseCandidates=Collections.emptyList();
    private String lastPhraseBefore="";
    private final android.util.LruCache<String,byte[]> previewCache =
        new android.util.LruCache<String,byte[]>(5_000_000) {
            @Override protected int sizeOf(String key,byte[] value) {return value.length;}
        };
    private volatile PredictionModel predictions;
    private volatile PhraseTable englishPhrases,germanPhrases;
    // Preserve user events occurring while the private DB snapshot is loading.
    private boolean modelCatchupEnabled=false;
    private boolean initialHistoryReady=false;
    private final java.util.concurrent.CountDownLatch personalHistoryGate=
        new java.util.concurrent.CountDownLatch(1);
    private Runnable pendingHeavyInstall;
    private final java.util.ArrayList<java.util.function.Consumer<PredictionModel>>
        modelCatchup=new java.util.ArrayList<>();
    private LearningDatabase database;
    private LinearLayout root, suggestionRow, toolbar, keys, panel, phraseRow;
    private FrameLayout detailFrame;
    private TextView caretIndicator;
    private final TextView[] phraseViews=new TextView[3];
    private final String[] visiblePhrases=new String[3];
    private final String[] phraseActions=new String[3];
    private int lastPhraseRevision=-1;
    private String phraseSource="";
    private TextView activeSpaceKey;
    private SpaceSwipe spaceSwipe;
    private CursorNavigator.Gesture spaceCursor;
    private Runnable spaceHold;
    private float spaceDownX, spaceLatestX;
    private int spacePointerId=MotionEvent.INVALID_POINTER_ID;
    private int spaceMovedLeft,spaceMovedRight;
    private long spaceCaptureNs,spaceMoveTotalNs,spaceMoveMaxNs;
    private int spaceMoveCount;
    private TextView[] suggestions = new TextView[3];
    private String[] visibleWords = new String[3];
    private final String[] wordActions=new String[3];
    private String mode="letters",layoutBeforeGif="letters";
    private LinearLayout gifPane;
    private EditText gifField;
    private InputConnection gifConnection,gifHost;
    private TextView providerButton;
    private ClipboardManager clipboardManager;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener=this::clipboardChanged;
    private String lastClipboardText;
    private long lastClipboardTime=-1,skippedClipboardTime=-1;
    private int clipboardRevision;
    private TextView gifStatus;
    private LinearLayout gifResults;
    private ScrollView gifScroll;
    private String gifProvider="reactions", gifRating="r";
    private int gifOffset=0, gifGridCells=0;
    private int gifPreviewsExpected, gifPreviewsLoaded, gifPreviewsFailed;
    private boolean shift=true,caps=false,sensitive=false,privateMode=false;
    private EditorInfo info;
    private EditorPolicy.Kind editorKind=EditorPolicy.Kind.PROSE;
    private volatile TokenHistory tokenHistory=new TokenHistory();
    private boolean tokenHistoryReady=false;
    private final java.util.ArrayList<String[]> tokenCatchup=new java.util.ArrayList<>();
    private int searchRevision=0;
    private final TypedWordDraft draft=new TypedWordDraft();
    private TextContext savedDraftContext;
    private PredictionModel.Event savedDraftEvent;
    private PredictionModel.Event recentEvent;
    private String recentSurface="";
    private long recentAt=0;
    private Runnable backspaceRepeat;

    @Override public void onCreate() {
        super.onCreate();active=this;
        predictions=new PredictionModel();database=new LearningDatabase(this);
        TokenHistory loadedTokens=new TokenHistory();
        database.restoreTokensAsync(loadedTokens,() -> handler.post(() -> {
            if(active!=this)return;
            for(String[] observed:tokenCatchup)loadedTokens.learn(observed[0],observed[1]);
            tokenCatchup.clear();
            tokenHistory=loadedTokens;tokenHistoryReady=true;
            refreshSuggestions();
            android.util.Log.i("KontextTastatur","Personal word transitions ready");
        }));
        // Restore the owner's actual typing history first, without waiting for
        // the large generic dictionaries or neural model preparation.
        modelCatchupEnabled=true;
        PredictionModel ownerHistory=new PredictionModel();
        database.restoreAsync(ownerHistory,() -> {
            CommonLexiconCase.seed(ownerHistory);
            handler.post(() -> {
                if(active!=this)return;
                for(java.util.function.Consumer<PredictionModel> event:modelCatchup)
                    event.accept(ownerHistory);
                modelCatchup.clear();
                modelCatchupEnabled=false;
                predictions=ownerHistory;
                initialHistoryReady=true;
                personalHistoryGate.countDown();
                refreshSuggestions();
                android.util.Log.i("KontextTastatur","Personal history and starter vocabulary ready");
                if(pendingHeavyInstall!=null){
                    Runnable install=pendingHeavyInstall;pendingHeavyInstall=null;
                    install.run();
                }
            });
        });
        // Generic dictionaries load AFTER the owner's personal statistics;
        // otherwise decompression and lexicon indexing compete with the only
        // learning data the owner actually wants to see immediately.
        languageWorker.execute(() -> {
            try{personalHistoryGate.await();}
            catch(InterruptedException stop){Thread.currentThread().interrupt();return;}
            if(active!=this)return;
            englishPhrases=loadPhraseTable("en");
            germanPhrases=loadPhraseTable("de");
            handler.post(() -> {if(active==this)refreshSuggestions();});
            ChimeEnglish englishModel=null;
            GermanNextWord germanModel=null;
            GermanCaseModel caseModel=null;
            try(java.io.InputStream modelStream=getAssets().open("chime_en.ltc")) {
                englishModel=new ChimeEnglish(modelStream);
            } catch(Exception ex) {
                android.util.Log.e("KontextTastatur","Optional offline English neural model not available",ex);
            }
            final ChimeEnglish ready=englishModel;
            if (ready != null) handler.post(() -> {
                if (active != this) return;
                neuralModel = ready;
                refreshSuggestions();
            });
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
            final GermanCaseModel caseReady=caseModel;
            final GermanNextWord germanReady=germanModel;
            // The same German candidate can be a noun or a verb. Apply its
            // contextual spelling from the first neural result onward.
            handler.post(() -> {
                if (active != this) return;
                germanCaseModel = caseReady;
                germanNeuralModel = germanReady;
                refreshSuggestions();
            });
            PredictionModel readyPredictor=new PredictionModel();
            loadLexicon(readyPredictor,"de_lexicon.json","de");
            loadLexicon(readyPredictor,"en_lexicon.json","en");
            readyPredictor.prepareForUse();
            BaselineNgrams loaded=new BaselineNgrams();
            for(String language:new String[]{"de","en"}) {
                if(Thread.currentThread().isInterrupted())return;
                try(java.io.InputStream stream=getAssets().open(language+"_next_words.ngram")) {
                    loaded.load(stream,language);
                } catch(Exception ex) {
                    android.util.Log.e("KontextTastatur","Offline next-word baseline load failed "+language,ex);
                }
            }
            readyPredictor.setBaselineNgrams(loaded);
            handler.post(() -> {
                if(active!=this)return;
                Runnable install=() -> {
                    if(active!=this)return;
                    // All earlier writes precede this read on the disk executor.
                    // Events after this marker are replayed exactly once.
                    modelCatchupEnabled=true;
                    modelCatchup.clear();
                    database.restoreAsync(readyPredictor,() -> handler.post(() -> {
                        if(active!=this)return;
                        for(java.util.function.Consumer<PredictionModel> action:modelCatchup)
                            action.accept(readyPredictor);
                        modelCatchup.clear();modelCatchupEnabled=false;
                        predictions=readyPredictor;
                        refreshSuggestions();
                        android.util.Log.i("KontextTastatur","Offline language model ready: "+
                            loaded.size()+" ngrams, "+readyPredictor.size()+" words");
                        if(ready!=null)android.util.Log.i("KontextTastatur",
                            "Offline English Chime neural model ready: "+ready.vocabularySize());
                        if(germanReady!=null)android.util.Log.i("KontextTastatur",
                            "Offline German neural model ready: "+germanReady.vocabularySize());
                        if(caseReady!=null)android.util.Log.i("KontextTastatur",
                            "Offline German context casing ready: "+caseReady.wordCount());
                    }));
                };
                if(initialHistoryReady)install.run();
                else pendingHeavyInstall=install;
            });
        });
        refreshSettings();
        clipboardManager=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboardManager!=null)clipboardManager.addPrimaryClipChangedListener(clipListener);
    }
    @Override public void onDestroy() {
        finishTypedDraft();
        if(clipboardManager!=null)clipboardManager.removePrimaryClipChangedListener(clipListener);
        closeGifField();
        searchRevision++; handler.removeCallbacksAndMessages(null);
        cancelSpaceGesture();
        network.shutdownNow();
        searchWorker.shutdownNow();
        sendWorker.shutdownNow();
        personalHistoryGate.countDown();
        languageWorker.shutdownNow();
        neuralWorker.shutdownNow();
        fastPredictWorker.shutdownNow();
        neuralModel=null;germanNeuralModel=null;germanCaseModel=null;
        englishPhrases=null;germanPhrases=null;
        modelCatchup.clear();modelCatchupEnabled=false;
        tokenCatchup.clear();tokenHistoryReady=false;
        pendingHeavyInstall=null;initialHistoryReady=false;
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
            active.draft.reset();active.clearSavedDraft();
            active.predictions.clearLearningData();
            active.tokenHistory=new TokenHistory();
            active.tokenCatchup.clear();
            if(active.modelCatchupEnabled)
                active.modelCatchup.add(PredictionModel::clearLearningData);
            active.recentEvent=null;
            active.database.clearLearning();
            active.refreshSuggestions();
        } else {
            new LearningDatabase(c).clearLearning();
        }
    }
    private PhraseTable loadPhraseTable(String language) {
        try(java.io.InputStream in=getAssets().open(language+"_phrase_pairs.ngram")) {
            return new PhraseTable(in,language);
        } catch(java.io.IOException ex) {
            android.util.Log.e("KontextTastatur","Phrase table unavailable: "+language,ex);
            return null;
        }
    }
    private void loadLexicon(PredictionModel target,String name,String language) {
        try (java.io.InputStream in=getAssets().open(name)) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            JSONArray ws=new JSONObject(out.toString("UTF-8")).getJSONArray("words");
            for(int i=0;i<ws.length();i++)target.addBaseWord(ws.getString(i),i,language);
        } catch(Exception e) {android.util.Log.e("KontextTastatur","Lexicon load failed: "+language,e);}
    }
    private boolean editorWantsCapitalization() {
        if(info==null)return false;
        int flags=info.inputType & (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
            InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if(flags==0)return false;
        InputConnection ic=typingConnection();
        return ic!=null && ic.getCursorCapsMode(flags)!=0;
    }
    private void synchronizeShift() {
        shift=shiftController.active();caps=shiftController.locked();
        manualShiftPending=shiftController.manual();
        updateShiftLabels();
    }
    /** Update only letter labels, never rebuild the complete keyboard on shift. */
    private void updateShiftLabels() {
        for(int i=0;i<letterViews.size();i++) {
            String original=letterNames.get(i);
            String label=(shift||caps)?(original.equals("ß")?"ẞ":
                original.toUpperCase(Locale.GERMAN)):original;
            TextView key=letterViews.get(i);
            if(!label.contentEquals(key.getText()))key.setText(label);
            String favorite=KeyPriority.favorite(original,shift||caps);
            if(favorite!=null)key.setContentDescription(label+
                "; lange halten für "+favorite+"; nach oben für weitere Sonderzeichen");
        }
        if(shiftKeyView!=null) {
            shiftKeyView.setText(caps?"⇪":"⇧");
            shiftKeyView.setBackground(background(shift?PRESSED:KEY));
            shiftKeyView.setContentDescription(caps?"Feststelltaste an; erneut tippen zum Ausschalten":
                shift?"Umschalttaste aktiv; nächstes Wort groß":"Umschalttaste; zweimal für Feststelltaste");
        }
    }
    private void restyleCurrentPredictions() {
        if(!"letters".equals(mode))return;
        int rev=predictionRevision;
        if(lastCandidateRevision==rev)
            updateSuggestionButtons(lastRankedSuggestionWords,lastSuggestionPrefix,
                lastSuggestionBefore,false,rev);
        if(lastPhraseRevision==rev)
            updatePhraseButtons(lastRawPhraseCandidates,lastPhraseBefore,rev);
    }
    /** Uses already available surrounding text; no editor IPC round trip. */
    private void updateAutoShiftFromText(String before) {
        if(!"letters".equals(mode))return;
        int flags=gifField!=null || info==null?0:info.inputType;
        boolean charCaps=(flags & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)!=0;
        boolean wordCaps=(flags & InputType.TYPE_TEXT_FLAG_CAP_WORDS)!=0 &&
            (before.isEmpty() || Character.isWhitespace(before.charAt(before.length()-1)));
        boolean sentenceCaps=TextContext.fromBeforeCursor(before).prefix.isEmpty() &&
            WordCase.sentenceStart(before);
        boolean desired=EditorPolicy.allowsAutoShift(typingKind()) &&
            (charCaps || wordCaps || sentenceCaps);
        ShiftController.Mode old=shiftController.mode();
        shiftController.automatic(desired);
        if(old!=shiftController.mode()) {
            synchronizeShift();
            restyleCurrentPredictions();
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
        closeGifField();
        gifPane=new LinearLayout(this);
        gifPane.setOrientation(LinearLayout.VERTICAL);
        gifPane.setVisibility(View.GONE);
        root.addView(gifPane,new LinearLayout.LayoutParams(-1,-2));
        suggestionRow=new LinearLayout(this);suggestionRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(suggestionRow,new LinearLayout.LayoutParams(-1,dp(44)));
        for(int i=0;i<3;i++) {
            final int pos=i;
            TextView v=button("",16,0xff22323d,42);
            v.setTextColor(i==1?0xffffd57b:TEXT); v.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);p.setMargins(dp(2),0,dp(2),0);
            suggestionRow.addView(v,p);suggestions[i]=v;
            v.setOnClickListener(w -> clickWordSlot(pos));
        }
        // The second prediction row shows actual multiword completions.
        // A compact caret preview replaces it only when editing *inside* a word.
        detailFrame=new FrameLayout(this);
        phraseRow=new LinearLayout(this);
        phraseRow.setGravity(Gravity.CENTER_VERTICAL);
        detailFrame.addView(phraseRow,new FrameLayout.LayoutParams(-1,dp(38)));
        for(int index=0;index<3;index++) {
            final int slot=index;
            TextView choice=button("",13,0xff24313c,36);
            choice.setSingleLine(true);
            choice.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(36),1);
            params.setMargins(dp(2),0,dp(2),0);
            phraseRow.addView(choice,params);
            phraseViews[index]=choice;
            choice.setOnClickListener(v -> clickPhraseSlot(slot));
        }
        caretIndicator=button("",13,0xff24313c,38);
        caretIndicator.setSingleLine(true);
        caretIndicator.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        caretIndicator.setVisibility(View.INVISIBLE);
        detailFrame.addView(caretIndicator,new FrameLayout.LayoutParams(-1,dp(38)));
        caretIndicator.setOnClickListener(v -> {
            if(CursorNavigator.collapseToEnd(typingConnection())) {
                if(Build.VERSION.SDK_INT>=31)typingConnection().setImeConsumesInput(false);
                refreshSuggestions();
            }
        });
        root.addView(detailFrame,new LinearLayout.LayoutParams(-1,dp(38)));
        toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER);
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(37)));
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setVisibility(View.GONE);
        root.addView(panel,new LinearLayout.LayoutParams(-1,-2));
        keys=new LinearLayout(this);keys.setOrientation(LinearLayout.VERTICAL);
        root.addView(keys,new LinearLayout.LayoutParams(-1,-2));
        renderToolbar();renderKeyboard();
        if(!typingSensitive() && !privateMode) {
            updateSuggestionButtons(Collections.emptyList(),"","",false,predictionRevision);
            updatePhraseButtons(Collections.emptyList(),"",predictionRevision);
        }
        refreshSuggestions();
        return root;
    }
    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute,restarting);
        // Android can replace the connection to the same focused editor while
        // an IME-owned search field or clipboard list is in use.
        boolean sameIdentity=info!=null && attribute!=null && info.fieldId==attribute.fieldId &&
            java.util.Objects.equals(info.fieldName,attribute.fieldName) &&
            java.util.Objects.equals(info.packageName,attribute.packageName);
        boolean nextSensitive=isPrivateField(attribute);
        EditorPolicy.Kind nextKind=EditorPolicy.classify(attribute==null?0:attribute.inputType,
            attribute==null?0:attribute.imeOptions);
        boolean sameField=restarting && sameIdentity && info.inputType==attribute.inputType &&
            editorKind==nextKind && sensitive==nextSensitive;
        if(!sameField) {
            if(!sameIdentity || !nextSensitive)finishTypedDraft();
            draft.reset();
            if(!sameIdentity || nextSensitive)clearSavedDraft();
        }
        cancelSpaceGesture();
        info=attribute;sensitive=nextSensitive;editorKind=nextKind;
        recentEvent=null;
        if(sameField) {
            if(gifField!=null)gifHost=getCurrentInputConnection();
            refreshSuggestions();
            return;
        }
        closeGifField();
        clipboardRevision++;
        int cls = attribute == null ? 0 : attribute.inputType & InputType.TYPE_MASK_CLASS;
        mode = (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) ? "numbers" : "letters";
        boolean initialCaps=attribute!=null && EditorPolicy.allowsAutoShift(editorKind) &&
            (attribute.initialCapsMode!=0 ||
             (attribute.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)!=0);
        shiftController.reset(initialCaps);
        shift=shiftController.active();caps=false;manualShiftPending=false;
        lastRankedSuggestionWords=Collections.emptyList();
        lastRawPhraseCandidates=Collections.emptyList();
        searchRevision++;predictionRevision++;recentEvent=null;
        lastPhraseRevision=-1;phraseSource="";
        clearPhrases();
        // No suggestion from the previous editor may flash on a fresh field.
        lastCandidateRevision=-1;
        for(int i=0;i<3;i++) {
            visibleWords[i]="";wordActions[i]=null;
            if(suggestions[i]!=null){suggestions[i].setText("");suggestions[i].setEnabled(false);}
        }
        if(root!=null){
            renderToolbar();renderKeyboard();
            if(!typingSensitive() && !privateMode) {
                updateSuggestionButtons(Collections.emptyList(),"","",false,predictionRevision);
                if(typingKind()==EditorPolicy.Kind.URI || typingKind()==EditorPolicy.Kind.EMAIL)
                    showEditorShortcuts(predictionRevision);
                else {
                    restorePhraseClicks();
                    updatePhraseButtons(Collections.emptyList(),"",predictionRevision);
                }
            }
            refreshSuggestions();
        }
    }
    @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute,restarting);
        info=attribute;sensitive=isPrivateField(attribute);
        editorKind=EditorPolicy.classify(attribute==null?0:attribute.inputType,
            attribute==null?0:attribute.imeOptions);
        // EditorInfo and the snapshot determine automatic Shift. Do not erase
        // explicit one-time Shift while the keyboard view is reopened.
        InputConnection ic=getCurrentInputConnection();
        if(Build.VERSION.SDK_INT>=31 && ic!=null)ic.setImeConsumesInput(gifField!=null);
        refreshSettings();captureClipboard();refreshSuggestions();
    }
    @Override public void onFinishInput() {
        finishTypedDraft();
        super.onFinishInput();
    }
    @Override public void onFinishInputView(boolean finishingInput) {
        finishTypedDraft();
        cancelSpaceGesture();
        closeGifField();
        clipboardRevision++;
        super.onFinishInputView(finishingInput);
    }

    /** A gesture from a previous field must never move a new field's cursor. */
    private void cancelSpaceGesture() {
        if(spaceHold!=null)handler.removeCallbacks(spaceHold);
        spaceHold=null;spaceSwipe=null;spaceCursor=null;
        spacePointerId=MotionEvent.INVALID_POINTER_ID;
        if(activeSpaceKey!=null)activeSpaceKey.setText("Leerzeichen");
        activeSpaceKey=null;
    }
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd) {
        super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);
        if(learnsMessageText() && draft.hasInput() && oldEnd>0 && newStart==0 && newEnd==0) {
            InputConnection ic=getCurrentInputConnection();
            if(ic!=null) {
                CharSequence left=ic.getTextBeforeCursor(1,0),right=ic.getTextAfterCursor(1,0);
                if(left!=null && left.length()==0 && right!=null && right.length()==0)
                    editorCleared();
            }
        }
        if(root!=null && "letters".equals(mode)) {
            uiSelectionUpdates++;
            if(spaceSwipe!=null && spaceSwipe.active())return;
            // A held spacebar can produce many selection notifications.
            // Repaint only after the drag ends, never for each cursor step.
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
    private InputConnection typingConnection() {
        return gifField!=null?gifConnection:getCurrentInputConnection();
    }
    private EditorPolicy.Kind typingKind() {
        return gifField!=null?EditorPolicy.Kind.PROSE:editorKind;
    }
    private boolean typingSensitive() {return gifField==null && sensitive;}
    private void clearSavedDraft() {
        savedDraftEvent=null;savedDraftContext=null;
    }
    private PredictionModel.Event saveTyped(TextContext word) {
        PredictionModel.Event event=predictions.learnTyped(word.prefix,word.previous,word.beforePrevious);
        database.persist(event,word.prefix,1);
        catchupTyped(event,word.prefix);
        return event;
    }
    private void finishTypedDraft() {
        TextContext word=draft.finish();
        if(word!=null && learnsMessageText()) {
            savedDraftEvent=saveTyped(word);
            savedDraftContext=word;
        }
    }
    private void editorCleared() {
        finishTypedDraft();draft.reset();clearSavedDraft();
    }
    private void reopenSavedDraft(InputConnection ic) {
        if(savedDraftEvent==null)return;
        // Only when returning to an already counted draft, verify the caret
        // before retracting its old spelling. Normal key taps do no editor read.
        CharSequence left=ic.getTextBeforeCursor(350,0);
        if(TypedWordDraft.same(savedDraftContext,TextContext.fromBeforeCursor(left))) {
            predictions.undo(savedDraftEvent);
            catchupUndo(savedDraftEvent);
            database.persist(savedDraftEvent,savedDraftContext.prefix,-1);
            draft.resume(left.toString(),savedDraftContext);
        }
        clearSavedDraft();
    }
    private boolean learnsMessageText() {
        return gifField==null && !sensitive && !privateMode && EditorPolicy.learns(editorKind);
    }

    private void updatePredictionRowVisibility() {
        boolean enabled="letters".equals(mode) && !typingSensitive() && !privateMode;
        if(suggestionRow!=null)suggestionRow.setVisibility(enabled?View.VISIBLE:View.GONE);
        boolean address="letters".equals(mode) && !privateMode &&
            (typingKind()==EditorPolicy.Kind.URI || typingKind()==EditorPolicy.Kind.EMAIL);
        if(detailFrame!=null)detailFrame.setVisibility(enabled||address?View.VISIBLE:View.GONE);
    }
    private void renderToolbar() {
        if(toolbar==null)return;
        toolbar.removeAllViews();
        if("clipboard".equals(mode)) {
            TextView back=button("‹",22,KEY,33);
            back.setContentDescription("Zurück zur Tastatur");
            back.setOnClickListener(v->showMode("letters"));
            toolbar.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            toolbar.addView(back,new LinearLayout.LayoutParams(dp(44),dp(33)));
            return;
        }
        toolbar.setGravity(Gravity.CENTER);
        toolbarAction("📋","Zwischenablage",() -> showMode("clipboard"));
        toolbarAction("☻","Emoji",() -> showMode("emoji"));
        toolbarAction("GIF","GIF-Suche",() -> showMode("gif"));
        toolbarAction("←","Cursor nach links",() -> arrow(KeyEvent.KEYCODE_DPAD_LEFT));
        toolbarAction("→","Cursor nach rechts",() -> arrow(KeyEvent.KEYCODE_DPAD_RIGHT));
        toolbarAction(privateMode?"🔒":"◉","Privatmodus",() -> {
            draft.reset();clearSavedDraft();
            privateMode=!privateMode;
            getSharedPreferences("keyboard_settings",0).edit().putBoolean("incognito",privateMode).apply();
            renderToolbar();updatePredictionRowVisibility();refreshSuggestions();
            toast(privateMode?"Privatmodus an":"Privatmodus aus");
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
        if("gif".equals(requested)) {
            if(gifField==null)finishTypedDraft();
            recentEvent=null;
            if(gifField==null) {
                layoutBeforeGif=mode.equals("numbers") || mode.equals("symbols")?mode:"letters";
                if(!mode.equals("letters") && !mode.equals("symbols")) {
                    mode="letters";renderToolbar();renderKeyboard();
                }
                showGifPanel();
            } else {
                closeGifField();
                mode=layoutBeforeGif;
                renderToolbar();renderKeyboard();
            }
        } else {
            mode=requested.equals(mode)?"letters":requested;
            renderToolbar();renderKeyboard();
        }
        refreshSuggestions();
    }
    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if(gifField!=null && isInputViewShown() && !event.isSystem() &&
            ("letters".equals(mode) || "symbols".equals(mode))) {
            if(code==KeyEvent.KEYCODE_ENTER || code==KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if(event.getRepeatCount()==0)searchGif();
            } else if(!event.isCtrlPressed() || !gifField.onKeyShortcut(code,event)) {
                gifField.onKeyDown(code,event);
            }
            return true;
        }
        return super.onKeyDown(code,event);
    }
    @Override public boolean onKeyUp(int code,KeyEvent event) {
        if(gifField!=null && isInputViewShown() && !event.isSystem() &&
            ("letters".equals(mode) || "symbols".equals(mode))) {
            if(code!=KeyEvent.KEYCODE_ENTER && code!=KeyEvent.KEYCODE_NUMPAD_ENTER)
                gifField.onKeyUp(code,event);
            return true;
        }
        return super.onKeyUp(code,event);
    }

    private void closeGifField() {
        searchRevision++;
        gifField=null;
        if(gifConnection!=null)gifConnection.closeConnection();
        gifConnection=null;gifResults=null;
        if(Build.VERSION.SDK_INT>=31 && gifHost!=null)gifHost.setImeConsumesInput(false);
        gifHost=null;
        if(gifPane!=null){gifPane.removeAllViews();gifPane.setVisibility(View.GONE);}
    }

    private static final class EditorSnapshot {
        final String before,after;
        final boolean selection;
        boolean hasSurroundingText=true;
        EditorSnapshot(String before,String after,boolean selection) {
            this.before=before;this.after=after;this.selection=selection;
        }
    }

    /** Single editor transaction instead of three to seven IPC calls. */
    private EditorSnapshot captureEditor(InputConnection ic) {
        if(Build.VERSION.SDK_INT>=31) {
            SurroundingText s=ic.getSurroundingText(350,24,0);
            if(s!=null && s.getText()!=null) {
                String content=s.getText().toString();
                int start=Math.max(0,Math.min(content.length(),s.getSelectionStart()));
                int end=Math.max(0,Math.min(content.length(),s.getSelectionEnd()));
                return new EditorSnapshot(content.substring(0,Math.min(start,end)),
                    content.substring(Math.max(start,end)),start!=end);
            }
        }
        // Compatibility with old or custom InputConnections.
        CharSequence b=ic.getTextBeforeCursor(350,0);
        CharSequence a=ic.getTextAfterCursor(24,0);
        CharSequence selected=ic.getSelectedText(0);
        EditorSnapshot snapshot=new EditorSnapshot(b==null?"":b.toString(),a==null?"":a.toString(),
            selected!=null && selected.length()>0);
        snapshot.hasSurroundingText=b!=null && a!=null;
        return snapshot;
    }

    /** Debounced scheduling only: never rank words or query editor on keypress. */
    private String personalPreviousToken(String before) {
        List<GrammarTokens.Token> pieces=GrammarTokens.tokenize(before);
        if(pieces.isEmpty())return TokenHistory.START;
        String active=GrammarTokens.activeWord(before);
        int index=active.isEmpty()?pieces.size()-1:pieces.size()-2;
        return index<0?TokenHistory.START:pieces.get(index).text;
    }
    private void rememberToken(String preceding,String next) {
        TokenHistory.Event event=tokenHistory.learn(preceding,next);
        if(event==null)return;
        database.persistToken(event);
        if(!tokenHistoryReady)tokenCatchup.add(new String[]{preceding,next});
    }
    private void showEditorShortcuts(int revision) {
        if(phraseRow==null || revision!=predictionRevision)return;
        final EditorPolicy.Kind kind=typingKind();
        String[] choices=EditorPolicy.shortcuts(kind);
        lastPhraseRevision=-1;
        for(int i=0;i<phraseViews.length;i++) {
            final int idx=i;
            String value=i<choices.length?choices[i]:"";
            TextView button=phraseViews[i];
            if(button==null)continue;
            button.setText(value);button.setEnabled(!value.isEmpty());
            button.setContentDescription("URL/E-Mail Zeichen: "+value);
            button.setOnClickListener(v -> {
                if(typingKind()!=kind || !"letters".equals(mode) || privateMode || idx>=choices.length)return;
                InputConnection ic=typingConnection();
                if(ic!=null){ic.commitText(value,1);refreshSuggestions();}
            });
        }
        phraseRow.setVisibility(View.VISIBLE);
    }
    private void restorePhraseClicks() {
        for(int i=0;i<phraseViews.length;i++)if(phraseViews[i]!=null) {
            final int idx=i;
            phraseViews[i].setOnClickListener(v -> clickPhraseSlot(idx));
        }
    }
    private void clickPhraseSlot(int position) {
        if(position<0 || position>=phraseActions.length)return;
        String action=phraseActions[position];
        if("clipboard".equals(action) || "emoji".equals(action) || "gif".equals(action)) {
            showMode(action);
            return;
        }
        choosePhrase(position);
    }
    private void refreshSuggestions() {
        final int revision=++predictionRevision;
        if(scheduledSnapshot!=null)handler.removeCallbacks(scheduledSnapshot);
        if(scheduledNeural!=null)handler.removeCallbacks(scheduledNeural);
        if(scheduledPendingControls!=null)handler.removeCallbacks(scheduledPendingControls);
        scheduledSnapshot=() -> calculateSuggestions(revision);
        handler.postDelayed(scheduledSnapshot,REFRESH_DELAY_MS);
        // Slow editors and neural inference can take seconds. Never leave
        // clickable suggestions that belong to the previous cursor context.
        // Show genuine controls after a short grace period instead of flicker
        // on every fast keystroke. No editor text or model IPC is required.
        scheduledPendingControls=() -> {
            if(revision!=predictionRevision || !"letters".equals(mode) ||
                typingSensitive() || privateMode ||
                typingKind()==EditorPolicy.Kind.URI || typingKind()==EditorPolicy.Kind.EMAIL)return;
            if(lastCandidateRevision!=revision)showPendingWordControls(revision);
            if(lastPhraseRevision!=revision)
                updatePhraseButtons(Collections.emptyList(),"",revision);
        };
        handler.postDelayed(scheduledPendingControls,105);
    }
    private void showPendingWordControls(int revision) {
        if(revision!=predictionRevision)return;
        lastCandidateRevision=revision;
        for(int i=0;i<3;i++) {
            visibleWords[i]="";
            wordActions[i]=i==0?"left":i==1?"clipboard":"right";
            TextView button=suggestions[i];
            if(button==null)continue;
            button.setText(i==0?"←":i==1?"📋":"→");
            button.setContentDescription(i==0?"Cursor nach links":
                i==1?"Zwischenablage":"Cursor nach rechts");
            button.setEnabled(true);
        }
    }

    /** No blocking editor IPC is allowed on the keyboard input/UI thread. */
    private void calculateSuggestions(int revision) {
        if(revision!=predictionRevision || active!=this || suggestions[0]==null)return;
        if(typingKind()==EditorPolicy.Kind.URI || typingKind()==EditorPolicy.Kind.EMAIL) {
            if(caretIndicator!=null)caretIndicator.setVisibility(View.INVISIBLE);
            updateSuggestionButtons(Collections.emptyList(),"","",false,revision);
            showEditorShortcuts(revision);
            return;
        }
        if(typingSensitive() || privateMode || !EditorPolicy.predictsWords(typingKind()) ||
            !"letters".equals(mode)) {
            if(caretIndicator!=null)caretIndicator.setVisibility(View.INVISIBLE);
            clearPhrases();
            updateSuggestionButtons(Collections.emptyList(),"","",false,revision);
            return;
        }
        // Capture the connection reference on the UI thread, then read its
        // surrounding text on a dedicated worker. Remote editors can stall a
        // read for hundreds of milliseconds; never make typing wait for it.
        final InputConnection ic=typingConnection();
        if(ic==null)return;
        // A local EditText belongs to the UI thread. Workers consume an immutable
        // snapshot, just as they do after reading a remote app editor.
        final EditorSnapshot local=gifField==null?null:captureEditor(ic);
        fastPredictWorker.execute(() -> {
            if(revision!=predictionRevision)return;
            long started=android.os.SystemClock.elapsedRealtimeNanos();
            EditorSnapshot snapshot;
            try {snapshot=local!=null?local:captureEditor(ic);}
            catch(Exception ex) {
                android.util.Log.w("KontextTastatur","Editor snapshot unavailable",ex);
                return;
            }
            long took=android.os.SystemClock.elapsedRealtimeNanos()-started;
            if(revision!=predictionRevision)return;
            String before=snapshot.before;
            boolean insideWord=!before.isEmpty()&&!snapshot.after.isEmpty() &&
                Character.isLetter(before.charAt(before.length()-1)) &&
                Character.isLetter(snapshot.after.charAt(0));
            TextContext context=TextContext.fromBeforeCursor(before);
            // A sentence opening has no previous word. Only real observed
            // first words and their confirmed continuations may be shown.
            final boolean opening=context.prefix.isEmpty() &&
                context.previous.isEmpty() && context.beforePrevious.isEmpty();
            String language=LanguageContext.detect(before,predictions);
            String tokenPrefix=GrammarTokens.activeWord(before);
            String tokenPrev=personalPreviousToken(before);
            List<String> tokens=tokenHistory.predict(tokenPrev,tokenPrefix,3);
            // The primary word ranker has more context for an ordinary predecessor.
            if(!context.previous.isEmpty() &&
                TextContext.normalize(tokenPrev).equals(TextContext.normalize(context.previous)))
                tokens=Collections.emptyList();
            final List<String> tokenChoices=tokens;
            List<String> personalWords=predictions.predictPersonal(context.prefix,context.previous,
                context.beforePrevious,language);
            List<String> quickFirst=SuggestionMixer.merge(tokenChoices,personalWords,3,tokenPrefix);
            final int personalCount=quickFirst.size();
            List<String> ranked=(snapshot.selection||insideWord)?Collections.emptyList():
                SuggestionMixer.merge(tokenChoices,
                    predictions.predict(context.prefix,context.previous,
                        context.beforePrevious,Collections.emptyMap(),language),3,tokenPrefix);
            // Paint the single-word results before doing any phrase generation.
            // Phrase predictions must never delay either typing or the top row.
            handler.post(() -> {
                if(active!=this || revision!=predictionRevision)return;
                uiSnapshotCount++;uiSnapshotTotalNs+=took;
                uiSnapshotMaxNs=Math.max(uiSnapshotMaxNs,took);
                if(learnsMessageText() && snapshot.hasSurroundingText) {
                    if(snapshot.before.isEmpty() && snapshot.after.isEmpty() && draft.hasInput())
                        editorCleared();
                    else draft.observe(snapshot.before,snapshot.after,snapshot.selection);
                }
                updateCaretIndicator(snapshot.before,snapshot.after,snapshot.selection);
                updateAutoShiftFromText(snapshot.before);
                restorePhraseClicks();
                updateSuggestionButtons(ranked,context.prefix,before,false,revision);
                if(snapshot.selection||insideWord)
                    updatePhraseButtons(Collections.emptyList(),before,revision);
            });
            if(snapshot.selection || insideWord || revision!=predictionRevision)return;
            PhrasePredictor.Casing casing=(word,prefix,seed)->formatCandidate(word,prefix,seed,language);
            List<String> learned=PhrasePredictor.complete(before,quickFirst,(seed,limit)->{
                TextContext next=TextContext.fromBeforeCursor(seed);
                return predictions.personalPhraseNext(next,LanguageContext.detect(seed,predictions));
            },casing,3);
            PhraseTable table="en".equals(language)?englishPhrases:germanPhrases;
            List<String> observed=Collections.emptyList();
            if(!opening && table!=null && learned.size()<3)
                observed=table.complete(before,ranked,casing,3);
            List<String> phrases=SuggestionMixer.merge(learned,observed,3,"");
            handler.post(() -> {
                if(active==this && revision==predictionRevision)
                    updatePhraseButtons(phrases,before,revision);
            });
            if(!neuralEnabled() || personalCount>=3)return;
            ChimeEnglish en=neuralModel;
            GermanNextWord de=germanNeuralModel;
            if(("en".equals(language)&&en==null)||("de".equals(language)&&de==null))return;
            handler.post(() -> {
                if(revision!=predictionRevision)return;
                scheduledNeural=() -> {
                    if(revision!=predictionRevision)return;
                    neuralWorker.execute(() -> {
                        if(revision!=predictionRevision)return;
                        Map<String,Double> scores;
                        try {
                            scores="en".equals(language)?neuralScores(en.predict(before,10)):
                                germanNeuralScores(de.predict(before,15));
                        } catch(Exception error) {
                            android.util.Log.w("KontextTastatur","Neural inference unavailable",error);
                            return;
                        }
                        if(revision!=predictionRevision)return;
                        List<String> mixed=SuggestionMixer.merge(tokenChoices,
                            predictions.predict(context.prefix,context.previous,
                                context.beforePrevious,scores,language),3,tokenPrefix);
                        handler.post(() -> {
                            if(active==this&&revision==predictionRevision&&!typingSensitive()&&!privateMode &&
                                "letters".equals(mode)) {
                                updateSuggestionButtons(mixed,context.prefix,before,false,revision);
                            }
                        });
                    });
                };
                handler.postDelayed(scheduledNeural,
                    context.prefix.isEmpty()?65:NEURAL_DELAY_MS);
            });
        });
    }

    /** Casing is resolved for each word against the exact preceding phrase. */
    private String formatCandidate(String word,String typedPrefix,String before,String language) {
        GermanCaseModel casing=germanCaseModel;
        String spelling=CommonLexiconCase.canonical(word,language);
        return "de".equals(language)&&casing!=null ?
            casing.apply(spelling,typedPrefix,before,false):
            WordCase.display(spelling,typedPrefix,before,false);
    }
    private void updatePhraseButtons(List<String> phrases,String before,int revision) {
        if(phraseRow==null||revision!=predictionRevision)return;
        lastPhraseRevision=revision;
        phraseSource=before;
        lastRawPhraseCandidates=phrases==null?Collections.emptyList():new ArrayList<>(phrases);
        lastPhraseBefore=before;
        // Where no genuine phrase can be generated, useful keyboard functions
        // occupy the vacant surfaces. They are clearly actions, not fake text.
        String[] actionKeys={"clipboard","emoji","gif"};
        String[] actionLabels={"📋", "☻", "GIF"};
        int actionIndex=0;
        for(int i=0;i<3;i++) {
            String phrase=phrases!=null&&i<phrases.size()?phrases.get(i):"";
            if(!phrase.isEmpty())phrase=shiftController.applyPhrase(phrase);
            TextView view=phraseViews[i];
            if(!phrase.isEmpty()) {
                visiblePhrases[i]=phrase;
                phraseActions[i]=null;
                if(view!=null) {
                    view.setText(phrase);
                    view.setContentDescription("Wortfolge: "+phrase);
                    view.setEnabled(true);
                }
            } else {
                visiblePhrases[i]="";
                phraseActions[i]=actionKeys[actionIndex];
                if(view!=null) {
                    view.setText(actionLabels[actionIndex]);
                    view.setContentDescription("Funktion: "+actionKeys[actionIndex]);
                    view.setEnabled(true);
                }
                actionIndex++;
            }
        }
    }
    private void clearPhrases() {
        lastPhraseRevision=-1;
        phraseSource="";
        for(int i=0;i<3;i++) {
            visiblePhrases[i]="";
            phraseActions[i]=null;
            if(phraseViews[i]!=null) {
                phraseViews[i].setText("");phraseViews[i].setEnabled(false);
            }
        }
    }

    /** The middle slot remains highest ranked; apply case only at display time. */
    private void updateSuggestionButtons(List<String> ranked,String prefix,String before,
                                         boolean capital,int revision) {
        lastCandidateRevision=revision;
        if(suggestions[0]==null)return;
        lastRankedSuggestionWords=new ArrayList<>(ranked);
        lastSuggestionPrefix=prefix;lastSuggestionBefore=before;
        String language=LanguageContext.detect(before,predictions);
        GermanCaseModel casing=germanCaseModel;
        for(int position=0;position<3;position++) {
            int rankedPosition=position==1?0:position==0?1:2;
            if(rankedPosition>=ranked.size()) {
                visibleWords[position]="";continue;
            }
            String candidate=CommonLexiconCase.canonical(ranked.get(rankedPosition),language);
            // Personal frequency decides WHICH word, not HOW the word is
            // capitalized in the current sentence. The user's manually typed
            // prefix still wins; otherwise use language and sentence context.
            // German noun/verb spelling uses the contextual casing corpus.
            // Unknown proper nouns retain their observed capitalization.
            String natural="de".equals(language)&&casing!=null?
                casing.apply(candidate,prefix,before,false):
                WordCase.display(candidate,prefix,before,capital);
            // Manually tapping Shift overrides the displayed initial, even
            // if a lowercase prefix had already been typed. Caps Lock applies
            // to the entire suggestion. Never change the stored word counts.
            visibleWords[position]=shiftController.applyWord(natural);
        }
        for(int i=0;i<3;i++) {
            String value=visibleWords[i];
            if(!value.isEmpty()) {
                wordActions[i]=null;
                suggestions[i].setText(value);
                suggestions[i].setContentDescription("Wortvorschlag: "+value);
                suggestions[i].setEnabled(true);
            } else if(!typingSensitive() && !privateMode && "letters".equals(mode)) {
                wordActions[i]=i==0?"left":i==1?"clipboard":"right";
                String label=i==0?"←":i==1?"📋":"→";
                suggestions[i].setText(label);
                suggestions[i].setContentDescription(i==0?"Cursor nach links":
                    i==1?"Zwischenablage öffnen":"Cursor nach rechts");
                suggestions[i].setEnabled(true);
            } else {
                wordActions[i]=null;
                suggestions[i].setText(i==1?(sensitive?"Privates Feld":
                    privateMode?"Privatmodus":""):"");
                suggestions[i].setEnabled(false);
            }
        }
    }
    /** Display a caret only for midword editing or selected text. */
    private void updateCaretIndicator(String before,String after,boolean selected) {
        if(caretIndicator==null)return;
        boolean inside=!before.isEmpty() && !after.isEmpty() &&
            Character.isLetter(before.charAt(before.length()-1)) &&
            Character.isLetter(after.charAt(0));
        if(typingSensitive() || !"letters".equals(mode) || (!selected && !inside)) {
            caretIndicator.setVisibility(View.INVISIBLE);
            if(phraseRow!=null)phraseRow.setVisibility(View.VISIBLE);
            return;
        }
        if(selected)caretIndicator.setText("Markierter Text │ Cursor setzen");
        else {
            String a=before.substring(Math.max(0,before.length()-16));
            String b=after.substring(0,Math.min(after.length(),16));
            caretIndicator.setText((before.length()>16?"…":"")+a+"│"+b+
                (after.length()>16?"…":""));
        }
        if(phraseRow!=null)phraseRow.setVisibility(View.INVISIBLE);
        caretIndicator.setVisibility(View.VISIBLE);
    }

    /** Empty prediction slots remain useful controls, not empty or fake text. */
    private void clickWordSlot(int position) {
        if(position<0 || position>=wordActions.length)return;
        String action=wordActions[position];
        if("left".equals(action)) {arrow(KeyEvent.KEYCODE_DPAD_LEFT);return;}
        if("right".equals(action)) {arrow(KeyEvent.KEYCODE_DPAD_RIGHT);return;}
        if("clipboard".equals(action)) {showMode("clipboard");return;}
        chooseSuggestion(position);
    }
    private void chooseSuggestion(int position) {
        String chosen=visibleWords[position];
        // Never commit a suggestion from an obsolete cursor or word prefix.
        if(chosen==null||chosen.isEmpty()||privateMode||typingSensitive() ||
            lastCandidateRevision!=predictionRevision)return;
        InputConnection ic=typingConnection();if(ic==null)return;
        CharSequence beforeChars=ic.getTextBeforeCursor(500,0);
        String beforeText=beforeChars==null?"":beforeChars.toString();
        TextContext ctx=TextContext.fromBeforeCursor(beforeChars);
        CharSequence selection=ic.getSelectedText(0);
        if((selection!=null && selection.length()>0) || !PersonalNextWords.valid(chosen))return;
        if(learnsMessageText()){reopenSavedDraft(ic);draft.reset();}
        if(!insertCompletion(ic,beforeText,ctx.prefix,chosen,ic.getTextAfterCursor(1,0)))return;
        recentEvent=null;
        if(learnsMessageText()) {
            PredictionModel.Event event=predictions.learnSelection(ctx.prefix,chosen,ctx.previous,ctx.beforePrevious);
            database.persist(event,chosen,1);
            catchupChoice(event,chosen);
            rememberToken(personalPreviousToken(beforeText),chosen);
            recentEvent=event;recentSurface=chosen;recentAt=System.currentTimeMillis();
        }
        shiftController.consumeSuggestion();
        synchronizeShift();
        updateAutoShiftFromText(beforeText+chosen+" ");
        refreshSuggestions();
    }
    private boolean insertCompletion(InputConnection ic,String before,String prefix,String value,CharSequence after) {
        String left=before.substring(0,before.length()-prefix.length());
        String insert=ProseSpacing.completion(left,!prefix.isEmpty(),value,after);
        int consumeSpace=after!=null && after.length()>0 && after.charAt(0)==' '?1:0;
        ic.beginBatchEdit();
        try {
            if((!prefix.isEmpty() || consumeSpace>0) &&
               !ic.deleteSurroundingText(prefix.length(),consumeSpace))return false;
            return ic.commitText(insert,1);
        } finally {ic.endBatchEdit();}
    }
    private void choosePhrase(int position) {
        if(position<0 || position>=visiblePhrases.length ||
            privateMode||typingSensitive() ||lastPhraseRevision!=predictionRevision)return;
        String selected=visiblePhrases[position];
        String[] parts=PhrasePredictor.words(selected);
        if(parts.length<2)return;
        InputConnection ic=typingConnection();if(ic==null)return;
        CharSequence beforeChars=ic.getTextBeforeCursor(350,0);
        String before=beforeChars==null?"":beforeChars.toString();
        // A phrase must come from the actual current cursor context.
        if(!before.equals(phraseSource))return;
        CharSequence selection=ic.getSelectedText(0);
        if(selection!=null && selection.length()>0)return;
        CharSequence after=ic.getTextAfterCursor(1,0);
        TextContext context=TextContext.fromBeforeCursor(before);
        if(after!=null && after.length()>0 && Character.isLetter(after.charAt(0)) &&
            !context.prefix.isEmpty())return;
        if(learnsMessageText()){reopenSavedDraft(ic);draft.reset();}
        if(!insertCompletion(ic,before,context.prefix,selected,after))return;
        recentEvent=null;
        if(learnsMessageText()) {
            String prev=context.previous,prev2=context.beforePrevious;
            String priorToken=personalPreviousToken(before);
            for(int index=0;index<parts.length;index++) {
                String word=parts[index];
                PredictionModel.Event event=predictions.learnSelection(
                    index==0?context.prefix:"",word,prev,prev2);
                database.persist(event,word,1);
                catchupChoice(event,word);
                rememberToken(priorToken,word);
                priorToken=word;
                prev2=prev;prev=word;
                recentEvent=event;
            }
            recentSurface=parts[parts.length-1];recentAt=System.currentTimeMillis();
        }
        shiftController.consumeSuggestion();
        synchronizeShift();
        updateAutoShiftFromText(before+selected+" ");
        refreshSuggestions();
    }
    private void renderKeyboard() {
        if(keys==null || panel==null)return;
        updatePredictionRowVisibility();
        cancelSpaceGesture();
        keys.removeAllViews();letterViews.clear();letterNames.clear();
        shiftKeyView=null;
        panel.removeAllViews();panel.setVisibility(View.GONE);
        boolean textLayout=mode.equals("letters") || mode.equals("symbols");
        gifPane.setVisibility(gifField!=null && textLayout?View.VISIBLE:View.GONE);
        keys.setVisibility(View.VISIBLE);
        if(mode.equals("emoji")){showEmojiPanel();return;}
        if(mode.equals("clipboard")){showClipboardPanel();return;}
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
        updateShiftLabels();
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
            if(character) {letterViews.add(v);letterNames.add(original);}
            if("⇧".equals(original))shiftKeyView=v;
            if("↵".equals(original) && gifField!=null)v.setContentDescription("GIF-Suche starten");
            v.setOnClickListener(w -> press(original));
            if("Leerzeichen".equals(original)) {
                v.setContentDescription("Leerzeichen. Halten und links oder rechts schieben, um den Cursor zu bewegen.");
                v.setOnTouchListener(this::spaceTouch);
            }
            String favorite=KeyPriority.favorite(original,false);
            if(favorite!=null) {
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
                                    for(String choice:KeyPriority.alternatives(original,shift||caps))
                                        popup.getMenu().add(choice);
                                    popup.setOnMenuItemClickListener(item -> {
                                        enterCharacter(item.getTitle().toString());return true;
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
                        enterCharacter(item.getTitle().toString());return true;
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
    /** Capture the editor only once when space becomes a cursor trackpad. */
    private void activateSpaceCursor(View view) {
        if(spaceSwipe==null || activeSpaceKey!=view || spaceSwipe.active())return;
        // Keep downX as the origin. Using the *latest* touch position here
        // silently lost movement made during the long-press delay.
        spaceSwipe.activate(spaceDownX);
        InputConnection connection=typingConnection();
        long started=android.os.SystemClock.elapsedRealtimeNanos();
        spaceCursor=CursorNavigator.begin(connection);
        spaceCaptureNs=android.os.SystemClock.elapsedRealtimeNanos()-started;
        spaceMoveTotalNs=spaceMoveMaxNs=0;spaceMoveCount=0;
        spaceMovedLeft=0;spaceMovedRight=0;
        activeSpaceKey.setText("◀ Cursor ▶");
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    /** Short tap types one space; hold or swipe left/right moves the caret. */
    private boolean spaceTouch(View view,MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            if(spaceHold!=null)handler.removeCallbacks(spaceHold);
            activeSpaceKey=(TextView)view;
            spacePointerId=event.getPointerId(0);
            spaceDownX=event.getX(0);spaceLatestX=spaceDownX;
            spaceSwipe=new SpaceSwipe(spaceDownX);
            spaceCursor=null;
            spaceHold=() -> activateSpaceCursor(view);
            handler.postDelayed(spaceHold,330);
            // A keyboard may be inside a scrolling parent. It must not steal
            // the horizontal gesture after ACTION_DOWN.
            if(view.getParent()!=null)view.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if(spaceSwipe==null)return true;
        if(action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_POINTER_UP) {
            return true; // Keep tracking the original finger only.
        }
        if(action==MotionEvent.ACTION_MOVE) {
            int pointer=event.findPointerIndex(spacePointerId);
            if(pointer<0)return true;
            spaceLatestX=event.getX(pointer);
            if(!spaceSwipe.active() && Math.abs(spaceLatestX-spaceDownX)>dp(20))
                activateSpaceCursor(view);
            if(spaceSwipe.active()) {
                int delta=spaceSwipe.move(spaceLatestX,dp(12));
                if(delta!=0) {
                    InputConnection connection=typingConnection();
                    long started=android.os.SystemClock.elapsedRealtimeNanos();
                    boolean moved=CursorNavigator.move(connection,spaceCursor,delta);
                    long took=android.os.SystemClock.elapsedRealtimeNanos()-started;
                    spaceMoveTotalNs+=took;
                    spaceMoveMaxNs=Math.max(spaceMoveMaxNs,took);
                    spaceMoveCount++;
                    if(moved) {
                        if(delta<0)spaceMovedLeft-=delta;
                        else spaceMovedRight+=delta;
                        recentEvent=null;
                    }
                }
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
            if(spaceHold!=null)handler.removeCallbacks(spaceHold);
            boolean dragging=spaceSwipe.active();
            CursorNavigator.Gesture finished=spaceCursor;
            if(view.getParent()!=null)view.getParent().requestDisallowInterceptTouchEvent(false);
            spaceSwipe=null;spaceHold=null;spaceCursor=null;
            spacePointerId=MotionEvent.INVALID_POINTER_ID;
            if(activeSpaceKey!=null)activeSpaceKey.setText("Leerzeichen");
            activeSpaceKey=null;
            if(!dragging && action==MotionEvent.ACTION_UP)enterDelimiter(" ");
            else {
                android.util.Log.i("KeyboardCursor","drag completed: left="+spaceMovedLeft+
                    " right="+spaceMovedRight+" session="+(finished!=null&&finished.hasSnapshot()?"indexed":"dpads")+
                    " finalOffset="+(finished==null?-1:finished.position())+
                    " initialSnapshotMs="+(spaceCaptureNs/1_000_000.0)+
                    " maxMotionMs="+(spaceMoveMaxNs/1_000_000.0)+
                    " avgMotionMs="+(spaceMoveCount==0?0:spaceMoveTotalNs/spaceMoveCount/1_000_000.0));
                refreshSuggestions();
            }
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
                shiftController.press(android.os.SystemClock.uptimeMillis());
                synchronizeShift();
                restyleCurrentPredictions();
                break;
            case "⌫":backspace();break;
            case "?123":mode="symbols";renderKeyboard();refreshSuggestions();break;
            case "ABC":mode="letters";renderKeyboard();refreshSuggestions();break;
            case "☻":showMode("emoji");break;
            case "Leerzeichen":enterDelimiter(" ");break;
            case "↵":enterDelimiter("\n");break;
            default:
                enterCharacter((shift||caps)?(key.equals("ß")?"ẞ":key.toUpperCase(Locale.GERMAN)):key);
        }
    }
    private void enterCharacter(String text) {
        if(Character.isLetter(text.codePointAt(0)) || "'".equals(text) || "’".equals(text))
            enterLetter(text);
        else enterDelimiter(text);
    }
    /** The input path commits a character immediately; prediction is deferred. */
    private void enterLetter(String letter) {
        long startedNs=android.os.SystemClock.elapsedRealtimeNanos();
        InputConnection ic=typingConnection();if(ic==null)return;
        boolean learn=learnsMessageText();
        if(learn)reopenSavedDraft(ic);
        boolean inserted=ic.commitText(letter,1);
        if(inserted && learn)draft.append(letter);
        long committedNs=android.os.SystemClock.elapsedRealtimeNanos()-startedNs;
        uiCommitTotalNs+=committedNs;uiCommitMaxNs=Math.max(uiCommitMaxNs,committedNs);
        recentEvent=null;
        if(!letter.equals("'")&&!letter.equals("’")) {
            shiftController.consumeLetter();
            if(gifField==null && info!=null && EditorPolicy.allowsAutoShift(editorKind) &&
                (info.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)!=0)
                shiftController.automatic(true);
            synchronizeShift();
        }
        refreshSuggestions();
        recordInputTime(startedNs);
    }
    private void catchupTyped(PredictionModel.Event event,String surface) {
        if(modelCatchupEnabled && event!=null)
            modelCatchup.add(model -> model.learnTyped(surface,event.previous,event.beforePrevious));
    }
    private void catchupChoice(PredictionModel.Event event,String surface) {
        if(modelCatchupEnabled && event!=null)
            modelCatchup.add(model -> model.learnSelection(event.prefix,surface,
                event.previous,event.beforePrevious));
    }
    private void catchupUndo(PredictionModel.Event event) {
        if(modelCatchupEnabled && event!=null)
            modelCatchup.add(model -> model.undo(event));
    }
    private void recordInputTime(long startedNs) {
        long nanos=android.os.SystemClock.elapsedRealtimeNanos()-startedNs;
        uiKeyTotalNs+=nanos;uiKeyMaxNs=Math.max(uiKeyMaxNs,nanos);uiKeyCount++;
        if(uiKeyCount%25==0)android.util.Log.i("KeyboardPerf",
            "keys="+uiKeyCount+" mean_input_ms="+
                (uiKeyTotalNs/uiKeyCount/1_000_000.0)+" max_input_ms="+
                (uiKeyMaxNs/1_000_000.0)+" mean_commit_ms="+
                (uiCommitTotalNs/uiKeyCount/1_000_000.0)+" max_commit_ms="+
                (uiCommitMaxNs/1_000_000.0)+" selection_events="+uiSelectionUpdates+
                " snapshots="+uiSnapshotCount+" mean_snapshot_ms="+
                (uiSnapshotCount==0?0:uiSnapshotTotalNs/uiSnapshotCount/1_000_000.0)+
                " max_snapshot_ms="+(uiSnapshotMaxNs/1_000_000.0));
    }
    private void enterDelimiter(String symbol) {
        if(gifField!=null && symbol.equals("\n")) {searchGif();return;}
        InputConnection ic=typingConnection();if(ic==null)return;
        CharSequence before=ic.getTextBeforeCursor(500,0);
        TextContext c=TextContext.fromBeforeCursor(before);
        if(learnsMessageText()) {
            TextContext authored=draft.finish();
            if((before==null || before.length()==0) && authored!=null)c=authored;
            CharSequence selected=ic.getSelectedText(0);
            if(!c.prefix.isEmpty() && isAtWordEnd(ic) && (selected==null || selected.length()==0) &&
                !TypedWordDraft.same(savedDraftContext,c)) {
                recentEvent=saveTyped(c);recentSurface=c.prefix;recentAt=System.currentTimeMillis();
                if(recentEvent!=null && before!=null)rememberToken(personalPreviousToken(before.toString()),c.prefix);
            }
            clearSavedDraft();draft.separator(before,symbol);
        }
        if(symbol.equals("\n")) {
            int action=info==null?0:(info.imeOptions & EditorInfo.IME_MASK_ACTION);
            boolean multiline=info!=null&&(info.inputType&InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0;
            if(action!=EditorInfo.IME_ACTION_NONE && action!=EditorInfo.IME_ACTION_UNSPECIFIED && !multiline) {
                ic.performEditorAction(action);
            } else ic.commitText("\n",1);
            updateAutoShiftFromText((before==null?"":before.toString())+"\n");
        } else {
            if(symbol.equals(" ") && before!=null && before.length()>0 && before.charAt(before.length()-1)==' ') {
                // No unexpected duplicate spaces after accepting a suggestion.
            } else {
                CharSequence selected=ic.getSelectedText(0);
                int trim=(selected==null || selected.length()==0) && EditorPolicy.predictsWords(typingKind())
                    ?ProseSpacing.spacesBeforeMark(before,symbol):0;
                if(trim>0)ic.beginBatchEdit();
                try {
                    if(trim>0)ic.deleteSurroundingText(trim,0);
                    ic.commitText(symbol,1);
                } finally {if(trim>0)ic.endBatchEdit();}
            }
            // Use the newly committed editor contents, not the old cursor mode.
            updateAutoShiftFromText((before==null?"":before.toString())+symbol);
        }
        refreshSuggestions();
    }
    private boolean isAtWordEnd(InputConnection ic) {
        CharSequence after=ic.getTextAfterCursor(1,0);
        return after==null || after.length()==0 || !Character.isLetter(after.charAt(0));
    }
    private void backspace() {
        InputConnection ic=typingConnection();if(ic==null)return;
        CharSequence selection=ic.getSelectedText(0);
        if(learnsMessageText()) {
            if(selection==null || selection.length()==0)reopenSavedDraft(ic);
            else clearSavedDraft();
            draft.backspace(selection!=null && selection.length()>0);
        }
        CharSequence before=ic.getTextBeforeCursor(Math.max(2,recentSurface.length()+2),0);
        if(selection!=null && selection.length()>0) {
            ic.commitText("",1);
            recentEvent=null;
        } else if(before!=null && before.length()>0) {
            // Removing just the delimiter does NOT reject an accepted suggestion.
            // Retract learning only when the user actually starts removing that word.
            boolean deletingFromConfirmedWord = recentEvent!=null &&
                System.currentTimeMillis()-recentAt<3500 && !typingSensitive() && !privateMode &&
                !recentSurface.isEmpty() && before.toString().endsWith(recentSurface);
            if(deletingFromConfirmedWord) {
                predictions.undo(recentEvent);
                catchupUndo(recentEvent);
                database.persist(recentEvent,recentSurface,-1);
            }
            if (deletingFromConfirmedWord) recentEvent=null;
            ic.deleteSurroundingText(1,0);
        } else if(gifField==null)ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL));
        refreshSuggestions();
    }
    private void arrow(int keycode) {
        InputConnection ic=typingConnection();if(ic==null)return;
        int by=keycode==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;
        if(learnsMessageText())finishTypedDraft();
        draft.reset();
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
                InputConnection ic=typingConnection();if(ic!=null)ic.commitText(emoji,1);
            });
        }
    }
    private void clipboardChanged() {
        if("clipboard".equals(mode) && panel!=null)showClipboardPanel();
        else captureClipboard();
    }

    private void captureClipboard() {
        if(clipboardManager==null)return;
        try {
            ClipDescription description=clipboardManager.getPrimaryClipDescription();
            if(description==null)return;
            if(privateMode) {
                skippedClipboardTime=description.getTimestamp();
                return;
            }
            if(description.getTimestamp()==skippedClipboardTime || !description.hasMimeType("text/*") ||
                (description.getExtras()!=null &&
                 description.getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false)))return;
            ClipData data=clipboardManager.getPrimaryClip();
            if(data==null || data.getItemCount()==0)return;
            ClipDescription actual=data.getDescription();
            if(actual.getExtras()!=null &&
                actual.getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false))return;
            StringBuilder combined=new StringBuilder();
            for(int i=0;i<data.getItemCount();i++) {
                CharSequence value=data.getItemAt(i).getText();
                if(value==null || value.length()==0)continue;
                if(combined.length()>0)combined.append('\n');
                combined.append(value);
            }
            if(combined.length()==0)return;
            String text=combined.toString();
            if(actual.getTimestamp()==lastClipboardTime && text.equals(lastClipboardText))return;
            lastClipboardTime=actual.getTimestamp();lastClipboardText=text;
            database.saveClip(text);
        } catch(SecurityException unavailable) {
            // The currently selected IME may read the clipboard when Android allows it.
        }
    }

    private void showClipboardPanel() {
        panel.setVisibility(View.VISIBLE);
        captureClipboard();
        final int revision=++clipboardRevision;
        database.readClipboard(entries -> handler.post(() -> {
            if(active!=this || revision!=clipboardRevision || !"clipboard".equals(mode))return;
            panel.removeAllViews();
            ScrollView history=new ScrollView(this);
            panel.addView(history,new LinearLayout.LayoutParams(-1,dp(220)));
            LinearLayout list=new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);history.addView(list);
            if(entries.isEmpty())list.addView(button("Noch kein kopierter Text",14,BG,44));
            for(String clip:entries) {
                String preview=clip.length()>300?clip.substring(0,300)+"…":clip;
                TextView row=button(preview,15,KEY,44);
                row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
                row.setMaxLines(2);row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setPadding(dp(10),0,dp(10),0);
                LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,dp(48));
                size.bottomMargin=dp(2);list.addView(row,size);
                row.setOnClickListener(v -> {
                    InputConnection target=typingConnection();
                    if(target!=null && target.commitText(clip,1)) {
                        draft.reset();clearSavedDraft();
                        recentEvent=null;showMode("letters");
                    }
                });
            }
        }));
    }

    private void showGifPanel() {
        gifProvider=GifSources.initialProvider(!getSharedPreferences("keyboard_settings",0)
            .getString("giphy_key","").isEmpty());
        gifRating=getSharedPreferences("keyboard_settings",0).getString("giphy_rating","r");
        gifPane.removeAllViews();
        gifPane.setVisibility(View.VISIBLE);
        LinearLayout row=new LinearLayout(this);
        gifPane.addView(row,new LinearLayout.LayoutParams(-1,dp(40)));
        gifField=new EditText(this) {
            @Override protected void onSelectionChanged(int start,int end) {
                super.onSelectionChanged(start,end);
                if(this==gifField)refreshSuggestions();
            }
            @Override protected void onTextChanged(CharSequence text,int start,int before,int count) {
                super.onTextChanged(text,start,before,count);
                if(this==gifField && gifResults!=null) {
                    clearGifResults();
                    refreshSuggestions();
                }
            }
        };
        gifField.setSingleLine();
        gifField.setTextSize(16);gifField.setTextColor(TEXT);gifField.setHintTextColor(0xffc7d1dc);
        gifField.setHint("GIFs suchen");gifField.setPadding(dp(10),0,dp(6),0);
        gifField.setBackground(background(KEY));gifField.setShowSoftInputOnFocus(false);
        gifField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        gifField.setOnEditorActionListener((field,id,event)->{searchGif();return true;});
        row.addView(gifField,new LinearLayout.LayoutParams(0,-1,1));
        providerButton=button(providerLabel()+" ▾",12,KEY,38);
        providerButton.setContentDescription("GIF-Anbieter wählen");
        row.addView(providerButton,new LinearLayout.LayoutParams(-2,-1));
        providerButton.setPadding(dp(8),0,dp(8),0);
        providerButton.setOnClickListener(v -> {
            PopupMenu menu=new PopupMenu(this,providerButton);
            String[] names={"Reaktionen","GIPHY","Tenor-Web","GIPHY-Web","Archiv"};
            String[] providers={"reactions","giphy","tenorweb","giphyweb","commons"};
            for(int i=0;i<names.length;i++)menu.getMenu().add(0,i,0,names[i]);
            menu.setOnMenuItemClickListener(item -> {
                gifProvider=providers[item.getItemId()];
                providerButton.setText(providerLabel()+" ▾");clearGifResults();return true;
            });
            menu.show();
        });
        TextView search=button("⌕",21,KEY,38);search.setContentDescription("GIFs suchen");
        row.addView(search,new LinearLayout.LayoutParams(dp(44),-1));
        search.setOnClickListener(v -> searchGif());
        gifStatus=button("",12,BG,22);
        gifStatus.setVisibility(View.GONE);
        gifPane.addView(gifStatus,new LinearLayout.LayoutParams(-1,dp(24)));
        gifScroll=new ScrollView(this);
        gifScroll.setVisibility(View.GONE);
        gifPane.addView(gifScroll,new LinearLayout.LayoutParams(-1,dp(109)));
        gifResults=new LinearLayout(this);gifResults.setOrientation(LinearLayout.VERTICAL);
        gifScroll.addView(gifResults,new ScrollView.LayoutParams(-1,-2));
        gifScroll.setOnScrollChangeListener((v,x,y,oldX,oldY)-> {
            if(y>oldY && !v.canScrollVertically(1) && "giphy".equals(gifProvider) && gifHasMore)
                searchGif(true);
        });
        gifField.requestFocus();
        gifConnection=gifField.onCreateInputConnection(new EditorInfo());
        gifHost=getCurrentInputConnection();
        if(Build.VERSION.SDK_INT>=31 && gifHost!=null)gifHost.setImeConsumesInput(true);
        clearGifResults();
        updateAutoShiftFromText("");
        updatePredictionRowVisibility();
    }

    private boolean gifHasMore;
    private String providerLabel() {
        switch(gifProvider) {
            case "giphy":return "Powered by GIPHY";
            case "commons":return "Archiv";
            case "tenorweb":return "Tenor-Web";
            case "giphyweb":return "GIPHY-Web";
            default:return "Reaktionen";
        }
    }
    private void clearGifResults() {
        searchRevision++;gifOffset=0;gifGridCells=0;gifHasMore=false;
        gifResults.removeAllViews();gifScroll.scrollTo(0,0);
        gifScroll.setVisibility(View.GONE);gifStatus.setText("");gifStatus.setVisibility(View.GONE);
    }
    private void openTenorWeb() {openGifWebsite("tenorweb");}
    private void openGiphyWeb() {openGifWebsite("giphyweb");}
    private void openGifWebsite(String provider) {
        // No API key or scraping. Explicitly open the provider's normal public
        // search page; media selection and browser sharing happen on the site.
        String slug=GifSources.slug(gifField.getText().toString());
        Uri.Builder uri=new Uri.Builder().scheme("https")
            .authority(GifSources.websiteHost(provider)).appendPath("search");
        uri.appendPath(provider.equals("giphyweb")?slug:slug+"-gifs");
        Intent intent=new Intent(Intent.ACTION_VIEW,uri.build());
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {requestHideSelf(0);startActivity(intent);}
        catch(Exception e) {gifStatus.setText("GIF-Website konnte im Browser nicht geöffnet werden.");}
    }
    private void searchGif() {searchGif(false);}
    private void searchGif(boolean more) {
        if(gifField==null)return;
        final String query=gifField.getText().toString().trim();
        gifStatus.setVisibility(View.VISIBLE);gifHasMore=false;
        final String provider=gifProvider;
        if(provider.equals("tenorweb")) {
            if(more)gifStatus.setText("Mehr Ergebnisse gibt es auf der Tenor-Webseite.");
            else openTenorWeb();
            return;
        }
        if(provider.equals("giphyweb")) {
            if(more)gifStatus.setText("Mehr Ergebnisse gibt es auf der GIPHY-Webseite.");
            else openGiphyWeb();
            return;
        }
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
                    GiphySearch.searchGiphy(key,query,rating,offset):provider.equals("reactions")?
                    ReactionGifFeed.search(query):GiphySearch.searchOpenverse(query);
                handler.post(() -> {
                    if(request!=searchRevision||gifField==null)return;
                    gifOffset=offset;
                    gifPreviewsExpected=results.size();
                    gifStatus.setText(results.isEmpty()?"Keine Treffer in "+
                        (provider.equals("giphy")?"GIPHY":provider.equals("reactions")?"ReactionGIFs":"Wikimedia"):
                        "GIFs: "+results.size()+" geladen · Vorschau läuft");
                    for(GiphySearch.Gif gif:results)addGifPreview(gif,request);
                    gifHasMore=provider.equals("giphy") && results.size()==24;
                    gifScroll.setVisibility(gifGridCells>0?View.VISIBLE:View.GONE);
                });
            } catch(Exception error) {
                handler.post(() -> {if(request==searchRevision&&gifField!=null)
                    gifStatus.setText(GiphySearch.userErrorMessage(error));});
            }
        });
    }
    private void updateGifPreviewProgress(int request, boolean success) {
        if(request!=searchRevision || gifField==null || gifStatus==null)return;
        if(success)gifPreviewsLoaded++;else gifPreviewsFailed++;
        String status=gifStatus.getText().toString();
        // Image loading may continue after an explicit share/copy action;
        // it must not overwrite the more important share/copy result.
        if(!status.startsWith("GIFs:") && !status.startsWith("Vorschau"))return;
        int finished=gifPreviewsLoaded+gifPreviewsFailed;
        if(finished==gifPreviewsExpected && gifPreviewsLoaded==0)
            gifStatus.setText("GIFs gefunden, Vorschau nicht verfügbar. Titel antippen oder erneut suchen.");
        else if(finished==gifPreviewsExpected && gifPreviewsFailed==0)
            gifStatus.setVisibility(View.GONE);
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
            if(request!=searchRevision || gifField==null)return;
            byte[] preview=previewCache.get(gif.thumbnail);
            if(preview==null) {
                try {
                    int limit=gif.thumbnail.equals(gif.original)?8_000_000:1_500_000;
                    preview=GiphySearch.requestPreview(gif.thumbnail,limit);
                } catch(Exception thumbFailure) {
                    try {if(!gif.thumbnail.equals(gif.original))
                        preview=GiphySearch.requestPreview(gif.original,1_800_000);}
                    catch(Exception sourceFailure) {
                        android.util.Log.w("KontextTastatur","GIF preview unavailable: "+
                            GiphySearch.userErrorMessage(sourceFailure));
                    }
                }
                if(preview!=null)previewCache.put(gif.thumbnail,preview);
            }
            final byte[] imageBytes=preview;
            handler.post(() -> {
                if(request!=searchRevision || gifField==null)return;
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
        gifStatus.setVisibility(View.VISIBLE);
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
        if(gifField==null)return;
        final int request=searchRevision;
        gifStatus.setVisibility(View.VISIBLE);
        gifStatus.setText("GIF-Datei wird heruntergeladen …");
        sendWorker.execute(() -> {
            try {
                File file=GiphySearch.cacheGif(getCacheDir(),gif);
                handler.post(() -> {
                    // A true editor switch invalidates the query revision. A
                    // same-field restart may legitimately supply a new connection.
                    if(gifField==null || searchRevision!=request)return;
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
