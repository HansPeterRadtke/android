package com.hans.android.audio.reliable;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated persisted limits shared by capture, diagnostics and networking. */
public final class RuntimePolicy {
    public static final String PREFERENCES = "voicebutton_runtime_policy";
    public static final class Spec {
        public final long defaultValue, minimum, maximum;
        public final String unit, purpose;
        Spec(long value, long minimum, long maximum, String unit, String purpose) {
            this.defaultValue=value; this.minimum=minimum; this.maximum=maximum;
            this.unit=unit; this.purpose=purpose;
        }
    }
    private static final Map<String,Spec> SPECS = new LinkedHashMap<>();
    private static volatile Map<String,Long> values = Collections.emptyMap();
    static {
        define("pcm_queue_blocks",600,20,1200,"50 ms blocks","Maximum buffered capture; next recording");
        define("pcm_sync_ms",1000,100,5000,"ms","Maximum normal unsynced capture interval; safety-critical");
        define("writer_drain_warning_ms",60000,5000,300000,"ms","Warn while draining; never discard audio on timeout");
        define("status_poll_ms",5000,1000,60000,"ms","Transcription status refresh interval");
        define("log_queue_events",256,16,4096,"events","Maximum admitted diagnostic tasks");
        define("log_pending_bytes",134217728,1048576,536870912,"bytes","Hard retained diagnostics limit including errors");
        define("log_corrupt_bytes",1048576,65536,8388608,"bytes","Malformed diagnostic record retention");
        define("trace_bytes",786432,65536,8388608,"bytes","Local trace retention");
        define("http_response_bytes",16777216,262144,67108864,"bytes","Maximum upload JSON response body");
        define("studio_response_bytes",2097152,65536,16777216,"bytes","Maximum Studio JSON response body");
        define("studio_timeout_ms",1800000,10000,1800000,"ms","Studio response wait; cancellation disconnects sooner");
        define("studio_cache_bytes",4294967296L,67108864,68719476736L,"bytes","Combined Studio cache budget");
        define("storage_reserve_bytes",536870912,67108864,4294967296L,"bytes","Free storage reserved before cache writes");
    }
    private RuntimePolicy() {}
    private static void define(String key,long value,long min,long max,String unit,String purpose) {
        SPECS.put(key,new Spec(value,min,max,unit,purpose));
    }
    public static Map<String,Spec> schema() { return Collections.unmodifiableMap(SPECS); }
    public static long value(String key) {
        Spec spec=SPECS.get(key);
        if(spec==null)throw new IllegalArgumentException("Unknown policy: "+key);
        Long found=values.get(key);return found==null?spec.defaultValue:found;
    }
    public static void load(Context context) {
        SharedPreferences p=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
        Map<String,Long> loaded=new LinkedHashMap<>();
        for(Map.Entry<String,Spec> e:SPECS.entrySet()) {
            Spec spec=e.getValue();long v=spec.defaultValue;
            try {v=p.getLong(e.getKey(),v);}catch(ClassCastException invalid){}
            if(v<spec.minimum||v>spec.maximum)v=spec.defaultValue;
            loaded.put(e.getKey(),v);
        }
        values=Collections.unmodifiableMap(loaded);
    }
    public static JSONObject currentJson() throws Exception {
        JSONObject out=new JSONObject();for(String key:SPECS.keySet())out.put(key,value(key));return out;
    }
    public static void apply(Context context,String json) throws Exception {
        JSONObject candidate=new JSONObject(json);
        java.util.Iterator<String> keys=candidate.keys();
        Map<String,Long> validated=new LinkedHashMap<>();
        while(keys.hasNext()) {
            String key=keys.next();Spec spec=SPECS.get(key);
            if(spec==null)throw new IllegalArgumentException("Unknown setting: "+key);
            Object raw=candidate.get(key);
            if(!(raw instanceof Number))throw new IllegalArgumentException(key+" must be an integer");
            double d=((Number)raw).doubleValue();long n=((Number)raw).longValue();
            if(Double.isNaN(d)||Double.isInfinite(d)||d!=n||n<spec.minimum||n>spec.maximum)
                throw new IllegalArgumentException(key+" must be "+spec.minimum+"–"+spec.maximum+" "+spec.unit);
            validated.put(key,n);
        }
        SharedPreferences.Editor editor=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit();
        for(Map.Entry<String,Long> e:validated.entrySet())editor.putLong(e.getKey(),e.getValue());
        if(!editor.commit())throw new java.io.IOException("Settings could not be saved");
        load(context);
    }
}
