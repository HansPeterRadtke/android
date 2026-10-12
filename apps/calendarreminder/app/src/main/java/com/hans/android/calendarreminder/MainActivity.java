package com.hans.android.calendarreminder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int INK=Color.rgb(26,47,62);
    private static final int MUTED=Color.rgb(88,111,128);
    private static final int ACCENT=Color.rgb(24,104,145);
    private static final int BACKGROUND=Color.rgb(246,249,252);
    private static final int RED=Color.rgb(169,51,39);
    private static final int GREEN=Color.rgb(27,112,80);
    private final SimpleDateFormat dateTime=new SimpleDateFormat("EEE, d MMM · HH:mm",Locale.getDefault());
    private final SimpleDateFormat dateOnly=new SimpleDateFormat("EEE, d MMM yyyy",Locale.getDefault());
    private CalendarStore store;
    private LinearLayout page;
    private boolean settingsVisible=false, syncing=false;
    private String feedback="";
    private String pendingServer="", pendingToken="";
    private int lastShownSuccess=0;
    private final BroadcastReceiver liveUpdate=new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if(!settingsVisible && !syncing) {
                feedback="Appointments updated automatically";
                draw();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        store=new CalendarStore(this);
        ReminderScheduler.ensureChannel(this);
        SyncJobService.ensurePeriodic(this);
        handleProvisioningIntent(getIntent());
        draw();
        // Continuous sync is started by onStart; manual interaction is not required.
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter updates=new IntentFilter(LiveSyncService.ACTION_SYNC_COMPLETE);
        if(Build.VERSION.SDK_INT>=33) {
            registerReceiver(liveUpdate,updates,Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(liveUpdate,updates);
        }
        if(store.configured() && store.liveEnabled()) {
            try {
                LiveSyncService.ensureRunning(this);
            } catch(RuntimeException exception) {
                store.setLiveStatus("Live connection restricted by Android; fallback sync enabled");
                SyncJobService.requestSync(this);
            }
        }
    }

    @Override protected void onStop() {
        try { unregisterReceiver(liveUpdate); } catch(IllegalArgumentException ignored) {}
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if(store!=null && page!=null) draw();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleProvisioningIntent(intent);
        draw();
    }

    private void handleProvisioningIntent(Intent intent) {
        if(intent==null || intent.getData()==null) return;
        android.net.Uri uri=intent.getData();
        if("raspicalendar".equals(uri.getScheme()) && "connect".equals(uri.getHost())) {
            pendingServer=uri.getQueryParameter("server");
            pendingToken=uri.getQueryParameter("token");
            if(pendingServer==null) pendingServer="";
            if(pendingToken==null) pendingToken="";
            settingsVisible=true;
        }
    }

    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int color, int border) {
        GradientDrawable background=new GradientDrawable();
        background.setColor(color);background.setCornerRadius(dp(16));
        if(border!=0) background.setStroke(dp(1),border);
        return background;
    }

    private TextView text(String value,int sp,int color,boolean bold) {
        TextView v=new TextView(this);
        v.setText(value);v.setTextColor(color);v.setTextSize(sp);
        if(bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setLineSpacing(dp(1),1.09f);
        return v;
    }

    private LinearLayout vertical() {
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void gap(LinearLayout parent,int height) {
        View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));
    }

    private Button button(String value,Runnable action,boolean primary) {
        Button b=new Button(this);
        b.setText(value);b.setTextSize(15);b.setAllCaps(false);
        b.setMinHeight(dp(52));
        b.setTextColor(primary?Color.WHITE:ACCENT);
        b.setBackground(shape(primary?ACCENT:Color.WHITE,primary?0:Color.rgb(206,225,237)));
        b.setOnClickListener(v->action.run());
        return b;
    }

    private LinearLayout card() {
        LinearLayout box=vertical();
        box.setBackground(shape(Color.WHITE,Color.rgb(225,235,243)));
        box.setPadding(dp(18),dp(17),dp(18),dp(17));
        return box;
    }

    private void draw() {
        ScrollView scroller=new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BACKGROUND);
        page=vertical();
        page.setPadding(dp(18),dp(17),dp(18),dp(28));
        scroller.addView(page);
        renderHeader();
        gap(page,16);
        if(settingsVisible) renderSettings(); else renderOverview();
        setContentView(scroller);
    }

    private void renderHeader() {
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout heading=vertical();
        TextView name=text("Raspi Calendar",25,INK,true);
        heading.addView(name);
        heading.addView(text("Appointments & reminders",13,MUTED,false));
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(64),1f));
        Button settings=button(settingsVisible?"Back":"Settings",()->{
            settingsVisible=!settingsVisible;feedback="";draw();
        },false);
        header.addView(settings,new LinearLayout.LayoutParams(dp(108),dp(50)));
        page.addView(header);
    }

    private void renderOverview() {
        if(!feedback.isEmpty()) notice(feedback,feedback.startsWith("Sync failed")?RED:ACCENT);
        if(!store.configured()) {
            notice("Not connected to Raspi. Connect once to receive private appointment reminders.",RED);
            page.addView(button("Connect to Raspi",()->{settingsVisible=true;draw();},true));
            gap(page,14);
        } else {
            String status;
            long age=System.currentTimeMillis()-store.lastSuccessMs();
            if(store.lastSuccessMs()==0) status="No successful synchronization yet";
            else if(age>60*60_000L) status="Calendar may be outdated · Last synced "+ago(age);
            else status="Last synced "+ago(age);
            if(!store.lastError().isEmpty())
                notice("Synchronization failed · "+store.lastError(),RED);
            LinearLayout line=card();
            line.addView(text(status,14,
                (age>60*60_000L || store.lastSuccessMs()==0)?RED:GREEN,false));
            String liveState=store.liveEnabled()?store.liveStatus():"Paused in Settings";
            boolean connected=store.liveEnabled() &&
                liveState.startsWith("Connected;") &&
                System.currentTimeMillis()-store.lastLiveContactMs()<90000;
            gap(line,7);
            line.addView(text(connected ? "Automatic updates connected"
                : "Automatic updates: "+liveState,14,connected?GREEN:RED,false));
            if(store.invalidCount()>0) {
                gap(line,8);
                line.addView(text("Some server events could not be processed.",13,RED,true));
            }
            gap(line,12);
            Button sync=button(syncing?"Checking…":"Check connection",this::syncNow,false);
            sync.setEnabled(!syncing);
            line.addView(sync);
            page.addView(line);
            gap(page,14);
        }

        boolean notificationsAllowed=Build.VERSION.SDK_INT<33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        boolean exact=ReminderScheduler.exactAvailable(this);
        if(!notificationsAllowed) {
            notice("Reminders are blocked until Android notifications are enabled.",RED);
            page.addView(button("Enable notifications",this::requestNotificationPermission,false));
            gap(page,13);
        }
        if(!exact) {
            notice("Precise alarms are not allowed. Notifications may arrive late.",RED);
            page.addView(button("Allow precise alarms",this::requestExactAlarmPermission,false));
            gap(page,13);
        }

        List<CalendarEvent> events=store.events();
        long now=System.currentTimeMillis();
        List<CalendarEvent> upcoming=new ArrayList<>();
        for(CalendarEvent e:events) if(e.isUpcoming(now)) upcoming.add(e);
        upcoming.sort(Comparator.comparingLong(e->e.startMs));
        if(upcoming.isEmpty()) {
            LinearLayout empty=card();
            empty.addView(text("Nothing coming up",23,INK,true));
            gap(empty,8);
            empty.addView(text(store.lastSuccessMs()==0?
                "Connect and sync to load appointments.":
                "No upcoming events in the connected Google calendar.",15,MUTED,false));
            page.addView(empty);
            return;
        }
        CalendarEvent first=upcoming.get(0);
        LinearLayout hero=card();
        hero.addView(text("NEXT APPOINTMENT",12,ACCENT,true));
        gap(hero,8);
        hero.addView(text(first.allDay?dateOnly.format(new Date(first.startMs)):dateTime.format(new Date(first.startMs)),
            23,INK,true));
        gap(hero,7);
        hero.addView(text(first.title,20,INK,true));
        if(!first.location.isEmpty()) {
            gap(hero,8);hero.addView(text(first.location,14,MUTED,false));
        }
        gap(hero,10);
        hero.addView(text(reminderLabel(first),13,GREEN,false));
        hero.setOnClickListener(v->showEvent(first));
        page.addView(hero);
        gap(page,20);
        page.addView(text("Upcoming",20,INK,true));
        gap(page,9);
        int shown=0;
        for(CalendarEvent e:upcoming) {
            if(shown>=40) break;
            if(shown>0) gap(page,10);
            LinearLayout row=card();
            row.addView(text(e.allDay?dateOnly.format(new Date(e.startMs)):dateTime.format(new Date(e.startMs)),
                13,ACCENT,true));
            gap(row,5);
            row.addView(text(e.title,17,INK,true));
            if(!e.location.isEmpty()) {
                gap(row,4);row.addView(text(e.location,13,MUTED,false));
            }
            row.setOnClickListener(v->showEvent(e));
            page.addView(row);
            shown++;
        }
    }

    private void notice(String message,int color) {
        LinearLayout box=vertical();
        box.setPadding(dp(14),dp(12),dp(14),dp(12));
        box.setBackground(shape(Color.rgb(251,253,255),color));
        box.addView(text(message,14,color,false));
        page.addView(box);
        gap(page,12);
    }

    private String reminderLabel(CalendarEvent e) {
        if(e.allDay) return "Reminder: 09:00 on the day";
        int mins=e.reminderMinutes(store.leadMinutes());
        if(mins==0) return "Reminder: when event starts";
        if(mins>=60 && mins%60==0) return "Reminder: "+(mins/60)+" hour(s) before";
        return "Reminder: "+mins+" minutes before";
    }

    private void showEvent(CalendarEvent event) {
        String detail=(event.allDay?dateOnly.format(new Date(event.startMs)):dateTime.format(new Date(event.startMs)))+
            "\n"+reminderLabel(event)+(event.location.isEmpty()?"":"\n\nLocation: "+event.location)+
            (event.description.isEmpty()?"":"\n\n"+event.description)+
            (event.chatgpt?"\n\nChatGPT automation is enabled for this event.":"");
        new AlertDialog.Builder(this).setTitle(event.title).setMessage(detail).setPositiveButton("Close",null).show();
    }

    private String ago(long ms) {
        if(ms<120000) return "just now";
        long min=ms/60000; if(min<60) return min+" min ago";
        long hours=min/60;if(hours<48) return hours+" h ago";
        return (hours/24)+" days ago";
    }

    private void renderSettings() {
        if(!feedback.isEmpty()) notice(feedback,feedback.startsWith("Pairing failed")?RED:ACCENT);
        page.addView(text("Connection",21,INK,true));
        gap(page,6);
        page.addView(text("Only your Raspi-hosted Google Calendar. This app never reads Etar or Android calendars.",14,MUTED,false));
        gap(page,14);
        LinearLayout box=card();
        box.addView(text("Raspi server",14,INK,true));
        gap(box,5);
        EditText serverField=new EditText(this);
        serverField.setSingleLine(true);
        serverField.setText(!pendingServer.isEmpty()?pendingServer:store.server());
        serverField.setTextSize(15);
        serverField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        serverField.setContentDescription("Raspi server HTTPS address");
        box.addView(serverField,new LinearLayout.LayoutParams(-1,dp(55)));
        gap(box,10);
        box.addView(text("Pairing code",14,INK,true));
        EditText secretField=new EditText(this);
        secretField.setSingleLine(true);
        secretField.setText(pendingToken);
        secretField.setTextSize(15);
        secretField.setHint(store.configured()?"Leave empty to keep saved token":"Enter pairing code from ChatGPT");
        secretField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secretField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        secretField.setContentDescription("One-time pairing code or existing private token");
        box.addView(secretField,new LinearLayout.LayoutParams(-1,dp(55)));
        gap(box,10);
        box.addView(text("Default reminder time",14,INK,true));
        Spinner lead=new Spinner(this);
        String[] choices={"At event time","5 minutes before","10 minutes before",
            "30 minutes before","1 hour before","1 day before"};
        int[] minutes={0,5,10,30,60,1440};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        lead.setAdapter(adapter);
        int current=store.leadMinutes();
        for(int i=0;i<minutes.length;i++) if(minutes[i]==current) lead.setSelection(i);
        box.addView(lead,new LinearLayout.LayoutParams(-1,dp(50)));
        gap(box,12);
        CheckBox automatic=new CheckBox(this);
        automatic.setText("Always-on automatic calendar updates");
        automatic.setTextColor(INK);
        automatic.setTextSize(15);
        automatic.setMinHeight(dp(55));
        automatic.setChecked(store.liveEnabled());
        automatic.setOnCheckedChangeListener((buttonView,enabled)-> {
            store.setLiveEnabled(enabled);
            if(enabled) {
                try { LiveSyncService.ensureRunning(this); }
                catch(RuntimeException error) {
                    store.setLiveStatus("Android blocked starting automatic updates");
                    SyncJobService.requestSync(this);
                }
            } else {
                LiveSyncService.stop(this);
                store.setLiveStatus("Paused in Settings");
            }
        });
        box.addView(automatic);
        gap(box,12);
        Button save=button("Save and synchronize",()->{
            try {
                String server=CalendarStore.validatedServer(serverField.getText().toString());
                String entered=secretField.getText().toString().trim();
                int chosenMinutes=minutes[lead.getSelectedItemPosition()];
                if(PairingClient.looksLikeCode(entered)) {
                    syncing=true;
                    feedback="Pairing with Raspi…";
                    pendingToken=entered;
                    pendingServer=server;
                    draw();
                    new Thread(()->{
                        try {
                            String deviceToken=PairingClient.redeem(server,entered);
                            runOnUiThread(()->{
                                if(isFinishing()||isDestroyed()) return;
                                try {
                                    finishConnection(server,deviceToken,chosenMinutes);
                                } catch(IllegalArgumentException ex) {
                                    syncing=false; feedback="Pairing failed: "+ex.getMessage(); draw();
                                }
                            });
                        } catch(Exception ex) {
                            String message=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
                            runOnUiThread(()->{
                                if(isFinishing()||isDestroyed()) return;
                                syncing=false;feedback="Pairing failed: "+message;draw();
                            });
                        }
                    },"raspi-calendar-pairing").start();
                    return;
                }
                String token=entered.isEmpty()?store.token():entered;
                finishConnection(server,token,chosenMinutes);
            } catch(IllegalArgumentException ex) {
                feedback=ex.getMessage();
                Toast.makeText(this,feedback,Toast.LENGTH_LONG).show();
            }
        },true);
        save.setEnabled(!syncing);
        box.addView(save);
        page.addView(box);
        gap(page,16);
        page.addView(text("Notification access",19,INK,true));
        gap(page,8);
        page.addView(button("Notification permission",
            this::requestNotificationPermission,false));
        gap(page,8);
        page.addView(button("Exact reminder permission",
            this::requestExactAlarmPermission,false));
        gap(page,8);
        page.addView(button("Test notification in 12 seconds",()->{
            ReminderScheduler.scheduleTest(this);
            Toast.makeText(this,"Test reminder scheduled",Toast.LENGTH_SHORT).show();
        },false));
        gap(page,11);
        page.addView(text("New and changed appointments reach your phone through a continuous Raspi connection. Reminders already received continue working offline. The ongoing notification keeps the connection alive.",13,MUTED,false));
    }

    private void finishConnection(String server,String token,int minutes) {
        List<CalendarEvent> previous=store.events();
        store.saveConnection(server,token);
        store.setLeadMinutes(minutes);
        ReminderScheduler.reschedule(this,previous,store.events());
        pendingToken="";
        pendingServer="";
        settingsVisible=false;
        syncing=false;
        feedback="Connected. Events synchronize automatically.";
        try {
            LiveSyncService.ensureRunning(this);
        } catch(RuntimeException failure) {
            store.setLiveStatus("Android restricted live updates; scheduled fallback active");
            SyncJobService.requestSync(this);
        }
        draw();
    }

    private void requestNotificationPermission() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);
        else Toast.makeText(this,"Notifications are already enabled.",Toast.LENGTH_SHORT).show();
    }

    private void requestExactAlarmPermission() {
        if(Build.VERSION.SDK_INT>=31 && !ReminderScheduler.exactAvailable(this)) {
            try {
                Intent i=new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                i.setData(android.net.Uri.parse("package:"+getPackageName()));
                startActivity(i);
            } catch(Exception e) {
                Toast.makeText(this,"Open Android Settings > Apps > Alarms & reminders.",Toast.LENGTH_LONG).show();
            }
        } else Toast.makeText(this,"Precise alarms are enabled.",Toast.LENGTH_SHORT).show();
    }

    private void syncNow() {
        if(syncing) return;
        if(!store.configured()) { settingsVisible=true;draw();return; }
        syncing=true;feedback="Synchronizing with Raspi…";draw();
        new Thread(()->{
            String outcome=SyncEngine.run(getApplicationContext());
            runOnUiThread(()->{
                if(isFinishing() || isDestroyed()) return;
                syncing=false;feedback=outcome;draw();
            });
        },"raspi-calendar-manual-sync").start();
    }

    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] result) {
        super.onRequestPermissionsResult(req,permissions,result);
        draw();
    }
}
