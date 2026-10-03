package com.hans.android.voicebutton;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hans.android.common_ui.AndroidUi;
import com.hans.android.audio.reliable.RuntimePolicy;

final class AppSettings {
    static final String NAME="voicebutton_behavior";
    private AppSettings() {}
    static SharedPreferences preferences(Context c) { return c.getSharedPreferences(NAME,Context.MODE_PRIVATE); }
    static boolean automaticUpload(Context c) { return preferences(c).getBoolean("automatic_upload",true); }
    static boolean uploadPermitted(Context c) { return automaticUpload(c)||preferences(c).getLong("manual_upload_before_ms",0L)>0; }
    static void requestUpload(Context c) { preferences(c).edit().putLong("manual_upload_before_ms",System.currentTimeMillis()).apply(); }
    static boolean automaticTranscription(Context c) { return preferences(c).getBoolean("automatic_transcription",true); }
    static boolean logAllowed(Context c,String level,String event) {
        SharedPreferences p=preferences(c);
        if(event!=null&&event.startsWith("ui.")&&!p.getBoolean("user_action_logging",true))return false;
        int verbosity=Math.max(0,Math.min(2,p.getInt("log_verbosity",1)));
        return "DEBUG".equals(level)?verbosity>=2:!"INFO".equals(level)||verbosity>=1;
    }
    static void show(Activity activity,Runnable changed) {
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);
        int pad=AndroidUi.dp(activity,16);box.setPadding(pad,pad,pad,pad);
        CheckBox upload=check(activity,box,"Upload recordings automatically",automaticUpload(activity));
        CheckBox transcribe=check(activity,box,"Transcribe new uploads automatically",automaticTranscription(activity));
        box.addView(AndroidUi.body(activity,"When automatic upload is off, recordings stay on this phone until you choose Send pending recordings. Already submitted server work is not recalled."));
        CheckBox waveform=check(activity,box,"Generate waveforms automatically on Thor",preferences(activity).getBoolean("automatic_waveform",true));
        CheckBox actions=check(activity,box,"Record user actions in diagnostics",preferences(activity).getBoolean("user_action_logging",true));
        box.addView(AndroidUi.body(activity,"Essential recovery and error events remain available when user-action logging is off."));
        Spinner verbosity=new Spinner(activity);verbosity.setMinimumHeight(AndroidUi.dp(activity,48));
        verbosity.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Warnings and errors","Normal diagnostics","Detailed diagnostics"}));
        verbosity.setSelection(Math.max(0,Math.min(2,preferences(activity).getInt("log_verbosity",1))));box.addView(verbosity);
        Button advanced=VoiceButtonMaterial.secondaryButton(activity,"Advanced limits");
        advanced.setOnClickListener(v->showLimits(activity));box.addView(advanced);
        ScrollView scroll=new ScrollView(activity);scroll.addView(box);
        new MaterialAlertDialogBuilder(activity).setTitle("Automation and privacy").setView(scroll)
                .setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
                    preferences(activity).edit().putBoolean("automatic_upload",upload.isChecked())
                            .putBoolean("automatic_transcription",transcribe.isChecked())
                            .putBoolean("automatic_waveform",waveform.isChecked())
                            .putBoolean("user_action_logging",actions.isChecked())
                            .putInt("log_verbosity",verbosity.getSelectedItemPosition())
                            .putLong("manual_upload_before_ms",0L).apply();
                    UploadWorkScheduler.initialize(activity);changed.run();
                }).show();
    }
    private static CheckBox check(Context c,LinearLayout box,String text,boolean checked) {
        CheckBox v=new CheckBox(c);v.setText(text);v.setChecked(checked);
        v.setMinHeight(AndroidUi.dp(c,48));box.addView(v);return v;
    }
    private static void showLimits(Activity activity) {
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);
        StringBuilder help=new StringBuilder("Queue capacity changes apply to the next recording.\n");
        for(java.util.Map.Entry<String,RuntimePolicy.Spec> e:RuntimePolicy.schema().entrySet()) {
            RuntimePolicy.Spec s=e.getValue();help.append('\n').append(e.getKey()).append(": ").append(s.minimum).append("–").append(s.maximum).append(' ').append(s.unit).append(". ").append(s.purpose);
        }
        EditText input=new EditText(activity);input.setSingleLine(false);input.setMinLines(8);
        try{input.setText(RuntimePolicy.currentJson().toString(2));}catch(Exception ignored){}
        box.addView(input);box.addView(AndroidUi.small(activity,help.toString()));
        ScrollView scroll=new ScrollView(activity);scroll.addView(box);
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(activity).setTitle("Advanced limits")
                .setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            final String candidate=input.getText().toString();dialog.getButton(-1).setEnabled(false);
            new Thread(()->{try{RuntimePolicy.apply(activity.getApplicationContext(),candidate);activity.runOnUiThread(dialog::dismiss);}
                catch(Exception failure){activity.runOnUiThread(()->{input.setError(failure.getMessage());dialog.getButton(-1).setEnabled(true);});}},"voicebutton-save-limits").start();
        }));dialog.show();
    }
}
