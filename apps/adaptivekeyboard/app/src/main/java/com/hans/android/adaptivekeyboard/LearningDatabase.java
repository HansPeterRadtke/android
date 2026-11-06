package com.hans.android.adaptivekeyboard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private, durable, transactional counts. No full messages or text-field content stored. */
public final class LearningDatabase extends SQLiteOpenHelper {
    private final ExecutorService disk = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "keyboard-local-database"); t.setDaemon(true); return t;
    });
    public LearningDatabase(Context c) { super(c, "learned.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE words(token TEXT PRIMARY KEY, surface TEXT NOT NULL, typed INTEGER NOT NULL DEFAULT 0, selected INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE pairs(prev TEXT NOT NULL, word TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev,word))");
        d.execSQL("CREATE TABLE triples(prev2 TEXT NOT NULL, prev TEXT NOT NULL, word TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev2,prev,word))");
        d.execSQL("CREATE TABLE choices(prev2 TEXT NOT NULL, prev TEXT NOT NULL, prefix TEXT NOT NULL, word TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev2,prev,prefix,word))");
        d.execSQL("CREATE TABLE clipboard(value TEXT PRIMARY KEY, inserted INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException("Migration required; personal learning may not be discarded");
    }

    public void restore(PredictionModel model) {
        SQLiteDatabase d = getReadableDatabase();
        try (Cursor c = d.rawQuery("SELECT surface,typed,selected FROM words",null)) {
            while(c.moveToNext()) model.restoreWord(c.getString(0),c.getInt(1),c.getInt(2));
        }
        try (Cursor c = d.rawQuery("SELECT prev,word,cnt FROM pairs WHERE cnt>0",null)) {
            while(c.moveToNext()) model.restorePair(c.getString(0),c.getString(1),c.getInt(2));
        }
        try (Cursor c = d.rawQuery("SELECT prev2,prev,word,cnt FROM triples WHERE cnt>0",null)) {
            while(c.moveToNext()) model.restoreTriple(c.getString(0),c.getString(1),c.getString(2),c.getInt(3));
        }
        try (Cursor c = d.rawQuery("SELECT prev2,prev,prefix,word,cnt FROM choices WHERE cnt>0",null)) {
            while(c.moveToNext()) model.restoreChoice(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4));
        }
    }

    public void persist(PredictionModel.Event e, String surface, int delta) {
        if (e == null) return;
        disk.execute(() -> {
            SQLiteDatabase d = getWritableDatabase();
            d.beginTransaction();
            try {
                d.execSQL("INSERT OR IGNORE INTO words(token,surface,typed,selected) VALUES (?,?,0,0)",new Object[]{e.word,surface});
                d.execSQL("UPDATE words SET surface=?, typed=max(0,typed+?),selected=max(0,selected+?) WHERE token=?",
                    new Object[]{surface,delta,e.selected ? delta : 0,e.word});
                if (!e.previous.isEmpty()) increment(d,"pairs",new String[]{"prev","word"},new String[]{e.previous,e.word},delta);
                if (!e.beforePrevious.isEmpty() && !e.previous.isEmpty())
                    increment(d,"triples",new String[]{"prev2","prev","word"},new String[]{e.beforePrevious,e.previous,e.word},delta);
                if (e.selected && !e.prefix.isEmpty()) {
                    if (!e.beforePrevious.isEmpty() || !e.previous.isEmpty())
                        increment(d,"choices",new String[]{"prev2","prev","prefix","word"},
                            new String[]{e.beforePrevious,e.previous,e.prefix,e.word},delta);
                    increment(d,"choices",new String[]{"prev2","prev","prefix","word"},
                        new String[]{"","",e.prefix,e.word},delta);
                }
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
        });
    }
    private static void increment(SQLiteDatabase d, String table, String[] cols, String[] vals, int delta) {
        StringBuilder names = new StringBuilder(), placeholders = new StringBuilder(), where = new StringBuilder();
        Object[] insert = new Object[vals.length+1];
        for (int i=0;i<vals.length;i++) {
            if (i>0) {names.append(',');placeholders.append(',');where.append(" AND ");}
            names.append(cols[i]); placeholders.append('?'); where.append(cols[i]).append("=?");insert[i] = vals[i];
        }
        insert[vals.length] = 0;
        d.execSQL("INSERT OR IGNORE INTO "+table+"("+names+",cnt) VALUES ("+placeholders+",?)",insert);
        Object[] update = new Object[vals.length+1];
        update[0] = delta;
        System.arraycopy(vals,0,update,1,vals.length);
        d.execSQL("UPDATE "+table+" SET cnt=max(0,cnt+?) WHERE "+where,update);
    }

    /** Called by the settings UI and IME only after an explicit confirmation. */
    public void clearLearning() {
        disk.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (String table : new String[]{"words","pairs","triples","choices"}) db.delete(table,null,null);
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
        });
    }

    public void saveClip(String value) {
        if (value == null || value.isBlank()) return;
        String clip = value.length() > 3000 ? value.substring(0,3000) : value;
        disk.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("INSERT OR REPLACE INTO clipboard(value,inserted) VALUES (?,?)",new Object[]{clip,System.currentTimeMillis()});
            db.execSQL("DELETE FROM clipboard WHERE value NOT IN (SELECT value FROM clipboard ORDER BY inserted DESC LIMIT 20)");
        });
    }
    public List<String> clipboard() {
        ArrayList<String> values = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM clipboard ORDER BY inserted DESC LIMIT 20",null)) {
            while(c.moveToNext()) values.add(c.getString(0));
        }
        return values;
    }
    public void clearClipboard() {
        disk.execute(() -> getWritableDatabase().delete("clipboard",null,null));
    }
    @Override public synchronized void close() { disk.shutdown(); super.close(); }
}
