package com.hans.android.calendarreminder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CalendarEvent {
    public final String key, title, description, location, url, meetingUrl, organizer;
    public final long startMs, endMs, allDayAlertMs;
    public final int googleReminderMinutes;
    public final boolean allDay, chatgpt;
    public final List<String> attendees;

    private CalendarEvent(String key, String title, String description, String location,
                          String url, String meetingUrl, String organizer, List<String> attendees,
                          long startMs, long endMs, long allDayAlertMs,
                          int googleReminderMinutes, boolean allDay, boolean chatgpt) {
        this.key=key; this.title=title; this.description=description;
        this.location=location; this.url=url; this.meetingUrl=meetingUrl;
        this.organizer=organizer; this.attendees=Collections.unmodifiableList(new ArrayList<>(attendees));
        this.startMs=startMs; this.endMs=endMs; this.allDayAlertMs=allDayAlertMs;
        this.googleReminderMinutes=googleReminderMinutes; this.allDay=allDay; this.chatgpt=chatgpt;
    }

    public static CalendarEvent fromJson(JSONObject j) throws JSONException {
        String key=j.getString("key");
        long start=j.getLong("startEpochMs"), end=j.getLong("endEpochMs");
        if (key.length()<1 || key.length()>500 || start<100000000000L || end<start ||
            end>4102444800000L) throw new JSONException("Invalid event identity/date");
        int offset=j.isNull("reminderMinutes") ? -1 : j.optInt("reminderMinutes",-1);
        if (offset>40320 || offset < -1) throw new JSONException("Invalid reminder offset");
        JSONArray guests=j.optJSONArray("attendees");
        ArrayList<String> attendees=new ArrayList<>();
        if(guests!=null) {
            for(int i=0;i<Math.min(100,guests.length());i++) {
                JSONObject guest=guests.optJSONObject(i);
                if(guest==null) continue;
                String name=guest.optString("name","").trim();
                String email=guest.optString("email","").trim();
                String status=guest.optString("status","").trim();
                if(name.isEmpty() && email.isEmpty()) continue;
                String summary=name.isEmpty()?email:(email.isEmpty()?name:name+" ("+email+")");
                if(!status.isEmpty() && !"needsAction".equals(status)) summary+=" — "+status;
                attendees.add(summary);
            }
        }
        // The archived representation also stores already-rendered attendee strings.
        JSONArray oldGuests=j.optJSONArray("attendeeLabels");
        if(oldGuests!=null && guests==null) {
            for(int i=0;i<Math.min(100,oldGuests.length());i++) {
                String item=oldGuests.optString(i,"").trim();
                if(!item.isEmpty()) attendees.add(item);
            }
        }
        return new CalendarEvent(key,j.optString("title","(Untitled)"),
            j.optString("description",""),j.optString("location",""),j.optString("url",""),
            j.optString("meetingUrl",""),j.optString("organizer",""),attendees,
            start,end,j.optLong("allDayAlertEpochMs",0),offset,
            j.optBoolean("allDay",false),j.optBoolean("chatgpt",false));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject item=new JSONObject()
            .put("key",key).put("title",title).put("description",description)
            .put("location",location).put("url",url).put("meetingUrl",meetingUrl)
            .put("organizer",organizer)
            .put("startEpochMs",startMs).put("endEpochMs",endMs)
            .put("allDayAlertEpochMs",allDayAlertMs)
            .put("reminderMinutes",googleReminderMinutes<0?JSONObject.NULL:googleReminderMinutes)
            .put("allDay",allDay).put("chatgpt",chatgpt);
        JSONArray people=new JSONArray();
        for(String person:attendees) people.put(person);
        item.put("attendeeLabels",people);
        return item;
    }

    public int reminderMinutes(int fallback) {
        return googleReminderMinutes>=0 ? googleReminderMinutes : fallback;
    }

    public long notifyAt(int fallback) {
        if (allDay) return allDayAlertMs > 0 ? allDayAlertMs : startMs;
        return startMs - reminderMinutes(fallback)*60000L;
    }

    public String deliveryKey(int fallback) {
        return key+"|"+notifyAt(fallback);
    }

    public boolean isUpcoming(long now) {
        return startMs>=now || (allDay && endMs>now);
    }
}
