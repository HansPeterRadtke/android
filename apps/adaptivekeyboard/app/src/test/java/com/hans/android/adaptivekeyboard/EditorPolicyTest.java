package com.hans.android.adaptivekeyboard;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import org.junit.Test;
import static org.junit.Assert.*;
public final class EditorPolicyTest {
    @Test public void browserUrlAndEmailUseSpecializedPolicies(){
        assertEquals(EditorPolicy.Kind.URI,EditorPolicy.classify(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_VARIATION_URI,EditorInfo.IME_ACTION_GO));
        assertEquals(EditorPolicy.Kind.EMAIL,EditorPolicy.classify(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,EditorInfo.IME_ACTION_NEXT));
        assertEquals(EditorPolicy.Kind.EMAIL,EditorPolicy.classify(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,EditorInfo.IME_ACTION_DONE));
        assertArrayEquals(new String[]{".com",".de","/"},EditorPolicy.shortcuts(EditorPolicy.Kind.URI));
        assertFalse(EditorPolicy.learns(EditorPolicy.Kind.URI));
        assertFalse(EditorPolicy.allowsAutoShift(EditorPolicy.Kind.URI));
    }
    @Test public void normalWebFormIsNotMistakenForUrl(){
        assertEquals(EditorPolicy.Kind.PROSE,EditorPolicy.classify(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,EditorInfo.IME_ACTION_NONE));
        assertEquals(EditorPolicy.Kind.SEARCH,EditorPolicy.classify(InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_SEARCH));
        assertEquals(EditorPolicy.Kind.PROSE,EditorPolicy.classify(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,EditorInfo.IME_ACTION_NONE));
    }
    @Test public void searchEditorsOfferCompletionsWithoutSavingSearchTerms() {
        EditorPolicy.Kind search=EditorPolicy.classify(InputType.TYPE_CLASS_TEXT,EditorInfo.IME_ACTION_SEARCH);
        assertTrue(EditorPolicy.predictsWords(search));
        assertFalse(EditorPolicy.learns(search));
    }
    @Test public void privateAndNumericFieldsNeverLearn(){
        assertEquals(EditorPolicy.Kind.PRIVATE,EditorPolicy.classify(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_VARIATION_PASSWORD,EditorInfo.IME_ACTION_DONE));
        assertEquals(EditorPolicy.Kind.PRIVATE,EditorPolicy.classify(InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING));
        assertEquals(EditorPolicy.Kind.NUMERIC,EditorPolicy.classify(InputType.TYPE_CLASS_NUMBER,
            EditorInfo.IME_ACTION_DONE));
        assertFalse(EditorPolicy.predictsWords(EditorPolicy.Kind.PRIVATE));
    }
}
