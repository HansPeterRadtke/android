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
    public LearningDatabase(Context c) { super(c, "learned.db", null, 6); }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE words(token TEXT PRIMARY KEY, surface TEXT NOT NULL, typed INTEGER NOT NULL DEFAULT 0, selected INTEGER NOT NULL DEFAULT 0, last_used_ms INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE pairs(prev TEXT NOT NULL, word TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev,word))");
        d.execSQL("CREATE TABLE triples(prev2 TEXT NOT NULL, prev TEXT NOT NULL, word TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev2,prev,word))");
        d.execSQL("CREATE TABLE choices(prev2 TEXT NOT NULL, prev TEXT NOT NULL, prefix TEXT NOT NULL, word TEXT NOT NULL, cnt INTEGER NOT NULL, last_selected_ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(prev2,prev,prefix,word))");
        d.execSQL("CREATE TABLE clipboard(value TEXT PRIMARY KEY, inserted INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE rejections(prev2 TEXT NOT NULL, prev TEXT NOT NULL, prefix TEXT NOT NULL, word TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev2,prev,prefix,word))");
        d.execSQL("CREATE TABLE next_recent(prev TEXT PRIMARY KEY, history TEXT NOT NULL)");
        d.execSQL("CREATE TABLE token_pairs(prev TEXT NOT NULL, next TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev,next))");
        d.execSQL("CREATE TABLE token_recent(prev TEXT PRIMARY KEY, history TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from<1 || from>5 || to!=6)
            throw new IllegalStateException("Unsupported database version; personal history unchanged");
        if (from<=1) {
            db.execSQL("CREATE TABLE IF NOT EXISTS rejections(prev2 TEXT NOT NULL, prev TEXT NOT NULL, prefix TEXT NOT NULL, word TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev2,prev,prefix,word))");
        }
        if (from<=2) {
            // Add timestamps while preserving every old word/context/selection/clipboard row.
            db.execSQL("ALTER TABLE words ADD COLUMN last_used_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE choices ADD COLUMN last_selected_ms INTEGER NOT NULL DEFAULT 0");
        }
        if (from<=3) {
            // Legacy lifetime counts are preserved. True observation order
            // cannot be reconstructed; the recent window starts accumulating
            // from the user's next confirmed word transition.
            db.execSQL("CREATE TABLE IF NOT EXISTS next_recent(prev TEXT PRIMARY KEY, history TEXT NOT NULL)");
        }
        if (from<=4) {
            // Additive migration: preserve every older word, pair, clipboard,
            // rejection and personal recency record without modification.
            db.execSQL("CREATE TABLE IF NOT EXISTS token_pairs(prev TEXT NOT NULL, next TEXT NOT NULL, cnt INTEGER NOT NULL, PRIMARY KEY(prev,next))");
            db.execSQL("CREATE TABLE IF NOT EXISTS token_recent(prev TEXT PRIMARY KEY, history TEXT NOT NULL)");
        }
        if(from==5)removePunctuationHistory(db);
    }

    // SQLiteOpenHelper wraps the whole upgrade in a transaction. Read the old
    // tables without modifying them under a cursor, even across cursor windows.
    private static void removePunctuationHistory(SQLiteDatabase db) {
        db.execSQL("CREATE TEMP TABLE invalid_tokens(value TEXT PRIMARY KEY)");
        try(Cursor c=db.rawQuery("SELECT prev FROM token_pairs UNION SELECT next FROM token_pairs",null)) {
            while(c.moveToNext()) {
                String token=c.getString(0);
                if(!TokenHistory.START.equals(token) && !TokenHistory.valid(token))
                    db.execSQL("INSERT INTO invalid_tokens VALUES (?)",new Object[]{token});
            }
        }
        db.execSQL("DELETE FROM token_pairs WHERE next=? OR prev IN (SELECT value FROM invalid_tokens) "+
                   "OR next IN (SELECT value FROM invalid_tokens)",new Object[]{TokenHistory.START});
        db.execSQL("DROP TABLE invalid_tokens");

        db.execSQL("CREATE TEMP TABLE cleaned_recent(prev TEXT PRIMARY KEY, history TEXT NOT NULL)");
        try(Cursor c=db.rawQuery("SELECT prev,history FROM token_recent",null)) {
            while(c.moveToNext()) {
                String prev=c.getString(0);
                if(!TokenHistory.START.equals(prev) && !TokenHistory.valid(prev))continue;
                String history=TokenHistory.encode(TokenHistory.decode(c.getString(1)));
                if(!history.isEmpty())
                    db.execSQL("INSERT INTO cleaned_recent VALUES (?,?)",new Object[]{prev,history});
            }
        }
        db.execSQL("DELETE FROM token_recent");
        db.execSQL("INSERT INTO token_recent SELECT prev,history FROM cleaned_recent");
        db.execSQL("DROP TABLE cleaned_recent");
    }

    public void restore(PredictionModel model) {
        SQLiteDatabase d = getReadableDatabase();
        try (Cursor c = d.rawQuery("SELECT surface,typed,selected,last_used_ms FROM words WHERE typed>0 OR selected>0",null)) {
            while(c.moveToNext()) model.restoreWord(c.getString(0),c.getInt(1),c.getInt(2),c.getLong(3));
        }
        try (Cursor c = d.rawQuery("SELECT prev,word,cnt FROM pairs WHERE cnt>0",null)) {
            while(c.moveToNext()) model.restorePair(c.getString(0),c.getString(1),c.getInt(2));
        }
        try (Cursor c = d.rawQuery("SELECT prev,history FROM next_recent",null)) {
            while(c.moveToNext())model.restoreRecent(c.getString(0),c.getString(1));
        }
        try (Cursor c = d.rawQuery("SELECT prev2,prev,word,cnt FROM triples WHERE cnt>0",null)) {
            while(c.moveToNext()) model.restoreTriple(c.getString(0),c.getString(1),c.getString(2),c.getInt(3));
        }
        try (Cursor c = d.rawQuery("SELECT prev2,prev,prefix,word,cnt,last_selected_ms FROM choices WHERE cnt>0",null)) {
            while(c.moveToNext()) model.restoreChoice(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getLong(5));
        }
        try (Cursor c = d.rawQuery("SELECT prev2,prev,prefix,word,cnt FROM rejections WHERE cnt>0",null)) {
            while(c.moveToNext()) model.restoreRejection(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4));
        }
    }

    /** Restore confirmed word transitions in disk order. */
    public void restoreTokensAsync(TokenHistory target,Runnable finished) {
        disk.execute(() -> {
            try {
                SQLiteDatabase db=getReadableDatabase();
                try(Cursor c=db.rawQuery("SELECT prev,next,cnt FROM token_pairs WHERE cnt>0",null)) {
                    while(c.moveToNext())target.restore(c.getString(0),c.getString(1),c.getInt(2));
                }
                try(Cursor c=db.rawQuery("SELECT prev,history FROM token_recent",null)) {
                    while(c.moveToNext())target.restoreRecent(c.getString(0),c.getString(1));
                }
            } catch(Exception e) {android.util.Log.e("KontextTastatur","Token history restore failed",e);}
            finally {finished.run();}
        });
    }
    public void persistToken(TokenHistory.Event event) {
        if(event==null)return;
        disk.execute(() -> {
            SQLiteDatabase db=getWritableDatabase();
            db.beginTransaction();
            try {
                increment(db,"token_pairs",new String[]{"prev","next"},
                    new String[]{event.before,event.next},1);
                String prior="";
                try(Cursor c=db.rawQuery("SELECT history FROM token_recent WHERE prev=?",
                                         new String[]{event.before})) {
                    if(c.moveToFirst())prior=c.getString(0);
                }
                java.util.ArrayDeque<String> history=TokenHistory.decode(prior);
                if(history.size()>=48)history.removeFirst();
                history.addLast(event.next);
                db.execSQL("INSERT OR REPLACE INTO token_recent(prev,history) VALUES (?,?)",
                    new Object[]{event.before,TokenHistory.encode(history)});
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
        });
    }
    /** Serialized behind pending writes, then restores without blocking the keyboard. */
    public void restoreAsync(PredictionModel target, Runnable finished) {
        disk.execute(() -> {
            try {
                restore(target);
                target.prepareForUse();
            } catch(Exception error) {
                android.util.Log.e("KontextTastatur","Private learning restore failed",error);
            } finally {
                finished.run();
            }
        });
    }

    public void persist(PredictionModel.Event e, String surface, int delta) {
        if (e == null) return;
        disk.execute(() -> {
            SQLiteDatabase d = getWritableDatabase();
            d.beginTransaction();
            try {
                d.execSQL("INSERT OR IGNORE INTO words(token,surface,typed,selected) VALUES (?,?,0,0)",new Object[]{e.word,surface});
                d.execSQL("UPDATE words SET surface=?, typed=max(0,typed+?),selected=max(0,selected+?),last_used_ms=? WHERE token=?",
                    new Object[]{surface,delta,e.selected ? delta : 0,delta>0?e.timestamp:0,e.word});
                if (!e.previous.isEmpty()) {
                    increment(d,"pairs",new String[]{"prev","word"},new String[]{e.previous,e.word},delta);
                    updateRecent(d,e.previous,e.word,delta,e.oldestEvicted);
                }
                if (!e.beforePrevious.isEmpty() && !e.previous.isEmpty())
                    increment(d,"triples",new String[]{"prev2","prev","word"},new String[]{e.beforePrevious,e.previous,e.word},delta);
                if (e.selected && !e.prefix.isEmpty()) {
                    for (int length=1; length<=e.prefix.length();length++) {
                        String part=e.prefix.substring(0,length);
                        if (!e.beforePrevious.isEmpty() || !e.previous.isEmpty())
                            incrementChoice(d,new String[]{e.beforePrevious,e.previous,part,e.word},delta,e.timestamp);
                        incrementChoice(d,new String[]{"","",part,e.word},delta,e.timestamp);
                    }
                }
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
        });
    }
    private static void updateRecent(SQLiteDatabase db,String previous,String word,int delta,String evicted) {
        String stored="";
        try (Cursor c=db.rawQuery("SELECT history FROM next_recent WHERE prev=?",
                                   new String[]{previous})) {
            if(c.moveToFirst())stored=c.getString(0);
        }
        String next=PersonalNextWords.updated(stored,word,delta,evicted);
        if(next.isEmpty())db.delete("next_recent","prev=?",new String[]{previous});
        else db.execSQL("INSERT OR REPLACE INTO next_recent(prev,history) VALUES (?,?)",
                        new Object[]{previous,next});
    }
    private static void incrementChoice(SQLiteDatabase db,String[] values,int delta,long timestamp) {
        increment(db,"choices",new String[]{"prev2","prev","prefix","word"},values,delta);
        db.execSQL("UPDATE choices SET last_selected_ms=? WHERE prev2=? AND prev=? AND prefix=? AND word=?",
            new Object[]{delta>0?timestamp:0,values[0],values[1],values[2],values[3]});
    }
    public void persistRejection(PredictionModel.Rejection r) {
        if (r==null)return;
        disk.execute(() -> {
            SQLiteDatabase d=getWritableDatabase();
            d.beginTransaction();
            try {
                String[] fields={"prev2","prev","prefix","word"};
                if (!r.beforePrevious.isEmpty() || !r.previous.isEmpty())
                    increment(d,"rejections",fields,new String[]{r.beforePrevious,r.previous,r.prefix,r.word},1);
                increment(d,"rejections",fields,new String[]{"","",r.prefix,r.word},1);
                d.setTransactionSuccessful();
            } finally {d.endTransaction();}
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
                for (String table : new String[]{"words","pairs","triples","choices","rejections","next_recent","token_pairs","token_recent"}) db.delete(table,null,null);
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
        });
    }

    public void saveClip(String value) {
        if(value==null || value.isEmpty())return;
        disk.execute(() -> {
            SQLiteDatabase db=getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL("INSERT OR REPLACE INTO clipboard(value,inserted) "+
                    "SELECT ?,max(?,coalesce(max(inserted),0)+1) FROM clipboard",
                    new Object[]{value,System.currentTimeMillis()});
                db.execSQL("DELETE FROM clipboard WHERE value NOT IN "+
                    "(SELECT value FROM clipboard ORDER BY inserted DESC LIMIT 20)");
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
        });
    }
    public void countLearnedWords(java.util.function.IntConsumer result) {
        disk.execute(() -> {
            try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM words WHERE typed>0",null)) {
                if(c.moveToFirst())result.accept(c.getInt(0));
            }
        });
    }
    public void readClipboard(java.util.function.Consumer<List<String>> loaded) {
        disk.execute(() -> {
            List<String> values=new ArrayList<>();
            try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT value FROM clipboard ORDER BY inserted DESC LIMIT 20",null)) {
                while(c.moveToNext())values.add(c.getString(0));
            }
            loaded.accept(values);
        });
    }
    public void clearClipboard() {
        disk.execute(() -> getWritableDatabase().delete("clipboard",null,null));
    }
    private final java.util.concurrent.atomic.AtomicBoolean closed=
        new java.util.concurrent.atomic.AtomicBoolean(false);
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        // Do not close SQLite while queued learning writes or an async restore
        // are still executing. This also keeps service teardown non-blocking.
        disk.execute(() -> LearningDatabase.super.close());
        disk.shutdown();
    }
}
