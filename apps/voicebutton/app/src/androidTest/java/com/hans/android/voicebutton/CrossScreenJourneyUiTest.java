package com.hans.android.voicebutton;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.hans.android.audio.reliable.ReliableSessionStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public class CrossScreenJourneyUiTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS);

    private Context context;

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.stopService(new Intent(context, RecordingService.class));
        context.stopService(new Intent(context, PlayerPlaybackService.class));
        new ReliableSessionStore(context).deleteAll();
        context.getSharedPreferences("voicebutton_gui_state", Context.MODE_PRIVATE)
                .edit().putBoolean("library_recordings_mode", true)
                .remove("library_root_uri").apply();
    }

    @After public void cleanup() throws Exception {
        context.stopService(new Intent(context, RecordingService.class));
        context.stopService(new Intent(context, PlayerPlaybackService.class));
        new ReliableSessionStore(context).deleteAll();
    }

    @Test public void recorderHasDirectOneTapLibraryNavigation() {
        try (ActivityScenario<MainActivity> main =
                     ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.voicebutton_library_shortcut))
                    .check(matches(isDisplayed())).perform(click());
            eventually(() -> onView(withId(R.id.voicebutton_library_primary_action))
                    .check(matches(isDisplayed())));
            onView(withId(R.id.voicebutton_library_path))
                    .check(matches(isDisplayed()));
            androidx.test.espresso.Espresso.pressBack();
        }
    }

    @Test public void libraryHasDiscoverableRowActionsAndFolderMenu()
            throws Exception {
        ReliableSessionStore seed = new ReliableSessionStore(context);
        seed.createFolder("QA sample", "");
        try (ActivityScenario<AudioLibraryActivity> scenario =
                     ActivityScenario.launch(AudioLibraryActivity.class)) {
            eventually(() -> scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.voicebutton_library_list);
                assertNotNull(list);
                assertTrue("Folder rows should load", list.getChildCount() > 0);
                View row = list.getChildAt(0);
                Button actions = row.findViewById(
                        R.id.voicebutton_library_item_actions);
                assertNotNull("Actions must be discoverable without long-press", actions);
                assertTrue(actions.isShown());
                assertTrue(actions.getHeight() >= 48);
            }));
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.voicebutton_library_list);
                View row = list.getChildAt(0);
                row.findViewById(R.id.voicebutton_library_item_actions).performClick();
            });
            onView(withText("Open this folder")).check(matches(isDisplayed()));
            onView(withText("Cancel")).perform(click());
        }
    }

    @Test public void folderCreationShowsCurrentLocationAndOpensTheCreatedFolder()
            throws Exception {
        try (ActivityScenario<AudioLibraryActivity> scenario =
                     ActivityScenario.launch(AudioLibraryActivity.class)) {
            eventually(() -> onView(withId(R.id.voicebutton_library_primary_action))
                    .check(matches(isDisplayed())));
            onView(withId(R.id.voicebutton_library_primary_action)).perform(click());
            onView(withText("Create folder in Recordings")).check(matches(isDisplayed()));
            onView(isAssignableFrom(android.widget.EditText.class))
                    .perform(typeText("QA child folder"), closeSoftKeyboard());
            onView(withText("Save")).perform(click());
            eventually(() -> scenario.onActivity(activity -> {
                TextView path = activity.findViewById(R.id.voicebutton_library_path);
                assertTrue(path.getText().toString(),
                        path.getText().toString().contains("QA child folder"));
            }));
        }
    }

    @Test public void fastSwitchToPhoneFilesCannotRestoreOldRecordingFolderResult() {
        try (ActivityScenario<AudioLibraryActivity> scenario =
                     ActivityScenario.launch(AudioLibraryActivity.class)) {
            eventually(() -> onView(withText("Phone files")).perform(click()));
            eventually(() -> scenario.onActivity(activity -> {
                TextView path = activity.findViewById(R.id.voicebutton_library_path);
                assertEquals("No phone folder selected", path.getText().toString());
                Button action = activity.findViewById(
                        R.id.voicebutton_library_primary_action);
                assertEquals("Choose folder", action.getText().toString());
            }));
        }
    }

    @Test public void folderRowTapOpensAndUpReturnsToRoot() throws Exception {
        new ReliableSessionStore(context).createFolder("QA directory", "");
        try (ActivityScenario<AudioLibraryActivity> scenario =
                     ActivityScenario.launch(AudioLibraryActivity.class)) {
            eventually(() -> scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.voicebutton_library_list);
                assertTrue(list.getChildCount() > 0);
            }));
            onData(anything()).inAdapterView(withId(R.id.voicebutton_library_list))
                    .atPosition(0).perform(click());
            eventually(() -> scenario.onActivity(activity -> {
                String path = ((TextView)activity.findViewById(
                        R.id.voicebutton_library_path)).getText().toString();
                assertTrue(path, path.startsWith("Recordings/"));
            }));
            // Android Back should climb the folder tree, not close Library.
            androidx.test.espresso.Espresso.pressBack();
            eventually(() -> scenario.onActivity(activity -> {
                String path = ((TextView)activity.findViewById(
                        R.id.voicebutton_library_path)).getText().toString();
                assertEquals("Recordings", path);
            }));
            onData(anything()).inAdapterView(withId(R.id.voicebutton_library_list))
                    .atPosition(0).perform(click());
            onView(withId(R.id.voicebutton_library_up)).perform(click());
            eventually(() -> scenario.onActivity(activity -> {
                String path = ((TextView)activity.findViewById(
                        R.id.voicebutton_library_path)).getText().toString();
                assertEquals("Recordings", path);
            }));
        }
    }

    @Test public void cancellingNewSubfolderDoesNotNavigate() throws Exception {
        new ReliableSessionStore(context).createFolder("QA directory", "");
        try (ActivityScenario<AudioLibraryActivity> scenario =
                     ActivityScenario.launch(AudioLibraryActivity.class)) {
            eventually(() -> scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.voicebutton_library_list);
                assertTrue(list.getChildCount() > 0);
            }));
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.voicebutton_library_list);
                list.getChildAt(0).findViewById(
                        R.id.voicebutton_library_item_actions).performClick();
            });
            onView(withText("Create subfolder")).perform(click());
            onView(withText("Cancel")).perform(click());
            scenario.onActivity(activity -> {
                String path = ((TextView)activity.findViewById(
                        R.id.voicebutton_library_path)).getText().toString();
                assertEquals("Recordings", path);
            });
        }
    }

    @Test public void playerWithoutSelectedFileExposesLibraryAndBlocksTransport() {
        try (ActivityScenario<PlayerActivity> scenario =
                     ActivityScenario.launch(PlayerActivity.class)) {
            eventually(() -> scenario.onActivity(activity -> {
                Button play = activity.findViewById(R.id.voicebutton_player_play);
                Button stop = activity.findViewById(R.id.voicebutton_player_stop);
                assertFalse("No file means Play must be blocked", play.isEnabled());
                assertFalse("No file means Stop must be blocked", stop.isEnabled());
                assertFalse(activity.findViewById(R.id.voicebutton_player_seek)
                        .isEnabled());
                assertTrue(activity.findViewById(
                        R.id.voicebutton_player_library).isShown());
                assertTrue(((TextView)activity.findViewById(
                        R.id.voicebutton_player_status)).getText().toString()
                        .contains("Choose a recording"));
            }));
        }
    }

    @Test public void playerRefusesMoveAfterFileSelectionChanged()
            throws Exception {
        try (ActivityScenario<PlayerActivity> scenario =
                     ActivityScenario.launch(PlayerActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    PlayerSource originallySelected = new PlayerSource(
                            Uri.parse("content://qa.example/first"), "First.mp3",
                            PlayerSource.KIND_DOCUMENT, 100L, "", "", null);
                    PlayerSource second = new PlayerSource(
                            Uri.parse("content://qa.example/second"), "Second.mp3",
                            PlayerSource.KIND_DOCUMENT, 100L, "", "", null);
                    Field current = PlayerActivity.class.getDeclaredField("originalSource");
                    current.setAccessible(true);
                    current.set(activity, second);
                    Field pending = PlayerActivity.class.getDeclaredField("pendingMoveSource");
                    pending.setAccessible(true);
                    pending.set(activity, originallySelected);
                    Intent result = new Intent().setData(
                            Uri.parse("content://qa.example/tree"));
                    activity.onActivityResult(7201, Activity.RESULT_OK, result);
                    assertNull(pending.get(activity));
                    String message = ((TextView)activity.findViewById(
                            R.id.voicebutton_player_status)).getText().toString();
                    assertTrue(message, message.contains("changed"));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
        }
    }

    private interface AssertionTask { void run() throws Exception; }
    private static void eventually(AssertionTask task) {
        AssertionError last = null;
        for (int i = 0; i < 60; i++) {
            try {
                task.run();
                return;
            } catch (AssertionError failure) {
                last = failure;
                try { Thread.sleep(100L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
        throw last == null ? new AssertionError("UI update timed out") : last;
    }
}
