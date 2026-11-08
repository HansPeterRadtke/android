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
    private int lastCandidateRevision=-1,lastNeuralRevision=-1;
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
    private BaselineNgrams baselineNgrams;
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
    private String mode="letters",gifQuery="";
    private TextView gifStatus;
    private LinearLayout gifResults;
    private ScrollView gifScroll;
    private TextView gifQueryDisplay, gifMore;
    private String gifProvider="tenorweb", gifRating="r";
    private boolean gifEditing=true;
    private int gifOffset=0, gifGridCells=0;
    private int gifPreviewsExpected, gifPreviewsLoaded, gifPreviewsFailed;
    private boolean shift=true,caps=false,sensitive=false,privateMode=false;
    private EditorInfo info;
    private EditorPolicy.Kind editorKind=EditorPolicy.Kind.PROSE;
    private volatile TokenHistory tokenHistory=new TokenHistory();
    private final TokenObserver tokenObserver=new TokenObserver();
    private boolean tokenHistoryReady=false;
    private final java.util.ArrayList<String[]> tokenCatchup=new java.util.ArrayList<>();
    private int searchRevision=0;
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
            android.util.Log.i("KontextTastatur","Personal punctuation history ready");
        }));
        // Restore the owner's actual typing history first, without waiting for
        // the large generic dictionaries or neural model preparation.
        modelCatchupEnabled=true;
        PredictionModel ownerHistory=new PredictionModel();
        database.restoreAsync(ownerHistory,() -> handler.post(() -> {
            if(active!=this)return;
            for(java.util.function.Consumer<PredictionModel> event:modelCatchup)
                event.accept(ownerHistory);
            modelCatchup.clear();
            modelCatchupEnabled=false;
            predictions=ownerHistory;
            initialHistoryReady=true;
            personalHistoryGate.countDown();
            refreshSuggestions();
            android.util.Log.i("KontextTastatur","Personal next-word history ready: "+
                ownerHistory.size()+" confirmed tokens");
            if(pendingHeavyInstall!=null){
                Runnable install=pendingHeavyInstall;pendingHeavyInstall=null;
                install.run();
            }
        }));
        // Generic dictionaries load AFTER the owner's personal statistics;
        // otherwise decompression and lexicon indexing compete with the only
        // learning data the owner actually wants to see immediately.
        languageWorker.execute(() -> {
            try{personalHistoryGate.await();}
            catch(InterruptedException stop){Thread.currentThread().interrupt();return;}
            if(active!=this)return;
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
            // Publish the tiny local neural fallbacks *before* the larger
            // dictionary/index load. This makes a blank field useful early,
            // without delaying actual character input or uploading context.
            handler.post(() -> {
                if(active!=this)return;
                neuralModel=ready;
                germanNeuralModel=germanReady;
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
                        baselineNgrams=loaded;
                        neuralModel=ready;
                        germanNeuralModel=germanReady;
                        germanCaseModel=caseReady;
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
    }
    @Override public void onDestroy() {
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
        modelCatchup.clear();modelCatchupEnabled=false;
        tokenCatchup.clear();tokenHistoryReady=false;tokenObserver.reset();
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
            active.predictions.clearLearningData();
            active.tokenHistory=new TokenHistory();
            active.tokenObserver.reset();active.tokenCatchup.clear();
            if(active.modelCatchupEnabled)
                active.modelCatchup.add(PredictionModel::clearLearningData);
            active.recentEvent=null;
            active.database.clearLearning();
            active.refreshSuggestions();
        } else {
            new LearningDatabase(c).clearLearning();
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
        InputConnection ic=getCurrentInputConnection();
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
        int flags=info==null?0:info.inputType;
        boolean charCaps=(flags & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)!=0;
        boolean wordCaps=(flags & InputType.TYPE_TEXT_FLAG_CAP_WORDS)!=0 &&
            (before.isEmpty() || Character.isWhitespace(before.charAt(before.length()-1)));
        boolean sentenceCaps=TextContext.fromBeforeCursor(before).prefix.isEmpty() &&
            WordCase.sentenceStart(before);
        boolean desired=EditorPolicy.allowsAutoShift(editorKind) &&
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
            if(CursorNavigator.collapseToEnd(getCurrentInputConnection())) {
                if(Build.VERSION.SDK_INT>=31)getCurrentInputConnection().setImeConsumesInput(false);
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
        if(!sensitive && !privateMode) {
            updateSuggestionButtons(Collections.emptyList(),"","",false,predictionRevision);
            updatePhraseButtons(Collections.emptyList(),"",predictionRevision);
        }
        refreshSuggestions();
        return root;
    }
    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        cancelSpaceGesture();
        super.onStartInput(attribute,restarting);
        info=attribute;sensitive=isPrivateField(attribute);
        editorKind=EditorPolicy.classify(attribute==null?0:attribute.inputType,
            attribute==null?0:attribute.imeOptions);
        tokenObserver.reset();
        int cls = attribute == null ? 0 : attribute.inputType & InputType.TYPE_MASK_CLASS;
        mode = (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) ? "numbers" : "letters";
        shiftController.reset(false);
        shift=false;caps=false;manualShiftPending=false;gifQuery="";
        lastRankedSuggestionWords=Collections.emptyList();
        lastRawPhraseCandidates=Collections.emptyList();
        searchRevision++;predictionRevision++;recentEvent=null;
        lastPhraseRevision=-1;phraseSource="";
        clearPhrases();
        // No suggestion from the previous editor may flash on a fresh field.
        lastCandidateRevision=-1;lastNeuralRevision=-1;
        for(int i=0;i<3;i++) {
            visibleWords[i]="";wordActions[i]=null;
            if(suggestions[i]!=null){suggestions[i].setText("");suggestions[i].setEnabled(false);}
        }
        if(root!=null){
            renderToolbar();renderKeyboard();
            if(!sensitive && !privateMode) {
                updateSuggestionButtons(Collections.emptyList(),"","",false,predictionRevision);
                if(editorKind==EditorPolicy.Kind.URI || editorKind==EditorPolicy.Kind.EMAIL)
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
        if(Build.VERSION.SDK_INT>=31 && ic!=null)ic.setImeConsumesInput(false);
        refreshSettings();refreshSuggestions();
    }
    @Override public void onFinishInputView(boolean finishingInput) {
        cancelSpaceGesture();
        super.onFinishInputView(finishingInput);
    }

    /** A gesture from a previous field must never move a new field's cursor. */
    private void cancelSpaceGesture() {
        if(spaceHold!=null)handler.removeCallbacks(spaceHold);
        spaceHold=null;spaceSwipe=null;spaceCursor=null;
        spacePointerId=MotionEvent.INVALID_POINTER_ID;
        if(activeSpaceKey!=null)activeSpaceKey.setText("Leerzeichen");
        activeSpaceKey=null;
        tokenObserver.reset();
    }
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd) {
        super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);
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
    /** Prediction surfaces have no place in clipboard/GIF/emoji or private panels. */
    private void updatePredictionRowVisibility() {
        boolean enabled="letters".equals(mode) && !sensitive && !privateMode;
        if(suggestionRow!=null)suggestionRow.setVisibility(enabled?View.VISIBLE:View.GONE);
        if(detailFrame!=null)detailFrame.setVisibility(enabled?View.VISIBLE:View.GONE);
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
        if(requested.equals(mode)) requested="letters";
        mode=requested;
        if(!requested.equals("gif"))searchRevision++;
        if(requested.equals("gif")) {
            gifQuery="";gifEditing=true;gifOffset=0;gifGridCells=0;
            gifProvider=GifSources.initialProvider(!getSharedPreferences("keyboard_settings",0)
                .getString("giphy_key","").isEmpty());
            gifRating=getSharedPreferences("keyboard_settings",0).getString("giphy_rating","r");
        }
        if(!"letters".equals(mode))clearPhrases();
        renderKeyboard();refreshSuggestions();
    }
    private static final class EditorSnapshot {
        final String before,after;
        final boolean selection;
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
        return new EditorSnapshot(b==null?"":b.toString(),a==null?"":a.toString(),
            selected!=null && selected.length()>0);
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
        String[] choices=EditorPolicy.shortcuts(editorKind);
        lastPhraseRevision=-1;
        for(int i=0;i<phraseViews.length;i++) {
            final int idx=i;
            String value=i<choices.length?choices[i]:"";
            TextView button=phraseViews[i];
            if(button==null)continue;
            button.setText(value);button.setEnabled(!value.isEmpty());
            button.setContentDescription("URL/E-Mail Zeichen: "+value);
            button.setOnClickListener(v -> {
                if(idx>=choices.length || !EditorPolicy.shortcuts(editorKind)[idx].equals(value))return;
                InputConnection ic=getCurrentInputConnection();
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
                sensitive || privateMode ||
                editorKind==EditorPolicy.Kind.URI || editorKind==EditorPolicy.Kind.EMAIL)return;
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
        if(editorKind==EditorPolicy.Kind.URI || editorKind==EditorPolicy.Kind.EMAIL) {
            if(caretIndicator!=null)caretIndicator.setVisibility(View.INVISIBLE);
            updateSuggestionButtons(Collections.emptyList(),"","",false,revision);
            showEditorShortcuts(revision);
            return;
        }
        if(sensitive || privateMode || !EditorPolicy.predictsWords(editorKind) ||
            !"letters".equals(mode)) {
            if(caretIndicator!=null)caretIndicator.setVisibility(View.INVISIBLE);
            clearPhrases();
            updateSuggestionButtons(Collections.emptyList(),"","",false,revision);
            return;
        }
        // Capture the connection reference on the UI thread, then read its
        // surrounding text on a dedicated worker. Remote editors can stall a
        // read for hundreds of milliseconds; never make typing wait for it.
        final InputConnection ic=getCurrentInputConnection();
        if(ic==null)return;
        fastPredictWorker.execute(() -> {
            if(revision!=predictionRevision)return;
            long started=android.os.SystemClock.elapsedRealtimeNanos();
            EditorSnapshot snapshot;
            try {snapshot=captureEditor(ic);}
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
            String language=LanguageContext.detect(before);
            String tokenPrefix=GrammarTokens.activeWord(before);
            String tokenPrev=personalPreviousToken(before);
            List<String> tokens=tokenHistory.predict(tokenPrev,tokenPrefix,3);
            final int personalCount=SuggestionMixer.merge(tokens,
                predictions.predictPersonal(context.prefix,context.previous,
                    context.beforePrevious,language),3,context.prefix).size();
            // An observed punctuation token occupies a high-priority slot, not
            // the *entire* row. Use model words for the other positions.
            List<String> ranked=(snapshot.selection||insideWord)?Collections.emptyList():
                SuggestionMixer.merge(tokens,
                    predictions.predict(context.prefix,context.previous,
                        context.beforePrevious,Collections.emptyMap(),language),3,tokenPrefix);
            // Paint the single-word results before doing any phrase generation.
            // Phrase predictions must never delay either typing or the top row.
            handler.post(() -> {
                if(active!=this || revision!=predictionRevision)return;
                uiSnapshotCount++;uiSnapshotTotalNs+=took;
                uiSnapshotMaxNs=Math.max(uiSnapshotMaxNs,took);
                updateCaretIndicator(snapshot.before,snapshot.after,snapshot.selection);
                updateAutoShiftFromText(snapshot.before);
                restorePhraseClicks();
                updateSuggestionButtons(ranked,context.prefix,before,false,revision);
                if(snapshot.selection||insideWord)
                    updatePhraseButtons(Collections.emptyList(),before,revision);
            });
            List<String> quickPhrases=Collections.emptyList();
            // Quick phrases must start with words ACTUALLY used after this
            // context. Cold generic fillers (e.g. "I" after "Let's") may be
            // acceptable as individual fallback words but cannot be stitched
            // into arbitrary unrelated phrases in the second row.
            List<String> quickFirst=SuggestionMixer.merge(tokens,
                predictions.predictPersonal(context.prefix,context.previous,
                    context.beforePrevious,language),3,context.prefix);
            if(!snapshot.selection && !insideWord && !quickFirst.isEmpty()) {
                final String lang=language;
                quickPhrases=PhrasePredictor.complete(before,quickFirst,
                    (seed,limit) -> {
                        if(revision!=predictionRevision)return Collections.emptyList();
                        TextContext following=TextContext.fromBeforeCursor(seed);
                        // Quick phrases should be grounded in actual observed
                        // adjacent words. Neural phrases may arrive later.
                        return predictions.hasPersonalNext("",following.previous)?
                            predictions.predictPersonal("",following.previous,
                                following.beforePrevious,lang):Collections.emptyList();
                    },(word,prefix,seed) -> formatCandidate(word,prefix,seed,lang),3);
            }
            final List<String> earlyPhrases=quickPhrases;
            if(!snapshot.selection && !insideWord)handler.post(() -> {
                if(active==this && revision==predictionRevision && lastNeuralRevision!=revision)
                    updatePhraseButtons(earlyPhrases,before,revision);
            });
            // Neural completions fill only missing places, even when one or
            // two real personal continuations already occupy other slots.
            if(snapshot.selection||insideWord||!neuralEnabled() ||
                (personalCount>=3 && earlyPhrases.size()>=3))return;
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
                        List<String> mixed=SuggestionMixer.merge(tokens,
                            predictions.predict(context.prefix,context.previous,
                                context.beforePrevious,scores,language),3,tokenPrefix);
                        handler.post(() -> {
                            if(active==this&&revision==predictionRevision&&!sensitive&&!privateMode &&
                                "letters".equals(mode)) {
                                lastNeuralRevision=revision;
                                updateSuggestionButtons(mixed,context.prefix,before,false,revision);
                            }
                        });
                        if(revision!=predictionRevision)return;
                        PhrasePredictor.Continuations follow=(seed,limit) -> {
                            if(revision!=predictionRevision)return Collections.emptyList();
                            TextContext next=TextContext.fromBeforeCursor(seed);
                            if(predictions.hasPersonalNext("",next.previous)) {
                                return predictions.predictPersonal("",next.previous,
                                    next.beforePrevious,language);
                            }
                            try {
                                List<String> words=new ArrayList<>(limit);
                                if("en".equals(language)) {
                                    for(ChimeEnglish.Candidate candidate:en.predict(seed,limit))
                                        words.add(candidate.word);
                                } else {
                                    for(GermanNextWord.Candidate candidate:de.predict(seed,limit))
                                        words.add(candidate.word);
                                }
                                return words;
                            } catch(Exception e) {return Collections.emptyList();}
                        };
                        List<String> multi=PhrasePredictor.complete(before,mixed,follow,
                            (word,prefix,seed)->formatCandidate(word,prefix,seed,language),3);
                        // Keep the user's actual phrase choices ahead of
                        // neural phrases, and never show duplicates.
                        multi=SuggestionMixer.merge(earlyPhrases,multi,3,"");
                        final List<String> resolvedPhrases=multi;
                        if(revision!=predictionRevision)return;
                        handler.post(() -> {
                            if(active==this && revision==predictionRevision && !sensitive && !privateMode)
                                updatePhraseButtons(resolvedPhrases,before,revision);
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
        String language=LanguageContext.detect(before);
        GermanCaseModel casing=germanCaseModel;
        TextContext context=TextContext.fromBeforeCursor(before);
        boolean confirmedNext=predictions.hasPersonalNext(prefix,context.previous);
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
            } else if(!sensitive && !privateMode && "letters".equals(mode)) {
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
        if(sensitive || !"letters".equals(mode) || (!selected && !inside)) {
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
        if(chosen==null||chosen.isEmpty()||privateMode||sensitive ||
            lastCandidateRevision!=predictionRevision)return;
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        CharSequence beforeChars=ic.getTextBeforeCursor(500,0);
        String beforeText=beforeChars==null?"":beforeChars.toString();
        TextContext ctx=TextContext.fromBeforeCursor(beforeChars);
        CharSequence selection=ic.getSelectedText(0);
        // A selected punctuation token is not an English/German word. Insert
        // the *actual string* without creating a fake word in the model.
        if(!PhrasePredictor.validWord(chosen)) {
            if(selection!=null&&selection.length()>0)return;
            ic.commitText(chosen+" ",1);
            if(EditorPolicy.learns(editorKind) && !privateMode && !sensitive)
                rememberToken(personalPreviousToken(beforeText),chosen);
            tokenObserver.reset();recentEvent=null;
            refreshSuggestions();return;
        }
        ic.beginBatchEdit();
        if((selection==null||selection.length()==0)&&!ctx.prefix.isEmpty()) ic.deleteSurroundingText(ctx.prefix.length(),0);
        ic.commitText(chosen+" ",1);
        ic.endBatchEdit();
        PredictionModel.Event e=predictions.learnSelection(ctx.prefix,chosen,ctx.previous,ctx.beforePrevious);
        database.persist(e,chosen,1);
        catchupChoice(e,chosen);
        if(EditorPolicy.learns(editorKind))rememberToken(personalPreviousToken(beforeText),chosen);
        tokenObserver.reset();
        recentEvent=e;recentSurface=chosen;recentAt=System.currentTimeMillis();
        shiftController.consumeSuggestion();
        synchronizeShift();
        updateAutoShiftFromText(beforeText+chosen+" ");
        refreshSuggestions();
    }
    private void choosePhrase(int position) {
        if(position<0 || position>=visiblePhrases.length ||
            privateMode||sensitive ||lastPhraseRevision!=predictionRevision)return;
        String selected=visiblePhrases[position];
        String[] parts=PhrasePredictor.words(selected);
        if(parts.length<2)return;
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
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
        ic.beginBatchEdit();
        if(!context.prefix.isEmpty())ic.deleteSurroundingText(context.prefix.length(),0);
        ic.commitText(selected+" ",1);
        ic.endBatchEdit();
        String prev=context.previous,prev2=context.beforePrevious;
        String priorToken=personalPreviousToken(before);
        PredictionModel.Event last=null;
        for(int index=0;index<parts.length;index++) {
            String word=parts[index];
            PredictionModel.Event event=predictions.learnSelection(
                index==0?context.prefix:"",word,prev,prev2);
            database.persist(event,word,1);
            catchupChoice(event,word);
            if(EditorPolicy.learns(editorKind)) {
                rememberToken(priorToken,word);
                priorToken=word;
            }
            prev2=prev;prev=word;
            last=event;
        }
        recentEvent=last;recentSurface=parts[parts.length-1];
        recentAt=System.currentTimeMillis();
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
    /** Capture the editor only once when space becomes a cursor trackpad. */
    private void activateSpaceCursor(View view) {
        if(spaceSwipe==null || activeSpaceKey!=view || spaceSwipe.active())return;
        // Keep downX as the origin. Using the *latest* touch position here
        // silently lost movement made during the long-press delay.
        spaceSwipe.activate(spaceDownX);
        InputConnection connection=getCurrentInputConnection();
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
                    InputConnection connection=getCurrentInputConnection();
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
                if("'".equals(key)||"’".equals(key)) enterLetter(key);
                else if(key.length()==1 && !Character.isLetter(key.charAt(0)))enterDelimiter(key);
                else enterLetter((shift||caps)?(key.equals("ß")?"ẞ":key.toUpperCase(Locale.GERMAN)):key);
        }
    }
    /** The input path commits a character immediately; prediction is deferred. */
    private void enterLetter(String letter) {
        if(mode.equals("gif")) {
            gifQuery+=letter.toLowerCase(Locale.GERMAN);
            updateGifStatus();return;
        }
        long startedNs=android.os.SystemClock.elapsedRealtimeNanos();
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        ic.commitText(letter,1);
        long committedNs=android.os.SystemClock.elapsedRealtimeNanos()-startedNs;
        uiCommitTotalNs+=committedNs;uiCommitMaxNs=Math.max(uiCommitMaxNs,committedNs);
        recentEvent=null;
        if(!letter.equals("'")&&!letter.equals("’")) {
            shiftController.consumeLetter();
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
        if(mode.equals("gif")) {
            if(symbol.equals("\n"))searchGif();
            else {gifQuery+=symbol;updateGifStatus();}
            return;
        }
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        CharSequence before=ic.getTextBeforeCursor(500,0);
        TextContext c=TextContext.fromBeforeCursor(before);
        if(!sensitive && !privateMode && EditorPolicy.learns(editorKind) &&
            !c.prefix.isEmpty() && isAtWordEnd(ic) &&
            (ic.getSelectedText(0)==null || ic.getSelectedText(0).length()==0)) {
            PredictionModel.Event e=predictions.learnTyped(c.prefix,c.previous,c.beforePrevious);
            database.persist(e,c.prefix,1);
            catchupTyped(e,c.prefix);
            recentEvent=e;recentSurface=c.prefix;recentAt=System.currentTimeMillis();
        }
        if(!sensitive && !privateMode && EditorPolicy.learns(editorKind) &&
            before!=null && !symbol.equals("\n")) {
            for(String[] pair:tokenObserver.observe(before.toString(),symbol)) {
                TokenHistory.Event event=tokenHistory.learn(pair[0],pair[1]);
                database.persistToken(event);
                if(!tokenHistoryReady)tokenCatchup.add(new String[]{pair[0],pair[1]});
            }
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
                if(".,!?;:".contains(symbol) && before!=null && before.length()>0 && before.charAt(before.length()-1)==' ')
                    ic.deleteSurroundingText(1,0);
                ic.commitText(symbol,1);
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
        if(mode.equals("gif")) {
            if(!gifQuery.isEmpty())gifQuery=gifQuery.substring(0,gifQuery.offsetByCodePoints(gifQuery.length(),-1));
            updateGifStatus();return;
        }
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        CharSequence selection=ic.getSelectedText(0);
        CharSequence before=ic.getTextBeforeCursor(Math.max(2,recentSurface.length()+2),0);
        tokenObserver.reset();
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
                catchupUndo(recentEvent);
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
        tokenObserver.reset();
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
            gifProvider.equals("commons")?"Wikimedia · GIF-Archiv (keine Reaktionen)":
            "GIF-Reaktionen · Websuche ohne API-Schlüssel",() -> showMode("letters"));
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
        gifToolbarButton(sources,"Tenor-Web",gifProvider.equals("tenorweb"),() -> {
            setGifProvider("tenorweb");
            openTenorWeb();
        });
        gifToolbarButton(sources,"GIPHY-Web",false,this::openGiphyWeb);
        gifToolbarButton(sources,"Archiv",gifProvider.equals("commons"),() -> setGifProvider("commons"));
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
        gifToolbarButton(actions,gifProvider.equals("giphy")?"Suchen / Trends":
            gifProvider.equals("commons")?"Archiv durchsuchen":"Im Browser suchen",
            false,()->searchGif(false));
        gifToolbarButton(actions,gifProvider.equals("giphy")?"Mehr GIFs":
            gifProvider.equals("tenorweb")?"GIPHY-Web":"Tenor-Web",false,()-> {
                if(gifProvider.equals("giphy"))searchGif(true);
                else if(gifProvider.equals("tenorweb"))openGiphyWeb();
                else openTenorWeb();
            });
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
    private void openTenorWeb() {openGifWebsite("tenorweb");}
    private void openGiphyWeb() {openGifWebsite("giphyweb");}
    private void openGifWebsite(String provider) {
        // No API key or scraping. Explicitly open the provider's normal public
        // search page; media selection and browser sharing happen on the site.
        String slug=GifSources.slug(gifQuery);
        Uri.Builder uri=new Uri.Builder().scheme("https")
            .authority(GifSources.websiteHost(provider)).appendPath("search");
        uri.appendPath(provider.equals("giphyweb")?slug:slug+"-gifs");
        Intent intent=new Intent(Intent.ACTION_VIEW,uri.build());
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {requestHideSelf(0);startActivity(intent);}
        catch(Exception e) {gifStatus.setText("GIF-Website konnte im Browser nicht geöffnet werden.");}
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
                gifProvider.equals("commons")?
                    "Wikimedia ist ein Sacharchiv, keine gute Reaktions-GIF-Suche":
                    "Ohne API-Schlüssel: Reaktions-GIFs im Browser auf Tenor/GIPHY suchen");
        }
    }
    private void searchGif() {searchGif(false);}
    private void searchGif(boolean more) {
        if(!mode.equals("gif"))return;
        final String query=gifQuery.trim();
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
