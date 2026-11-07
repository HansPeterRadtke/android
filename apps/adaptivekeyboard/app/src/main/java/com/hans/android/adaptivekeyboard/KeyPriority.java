package com.hans.android.adaptivekeyboard;

/** Common German diacritics take precedence over rarely typed accents. */
public final class KeyPriority {
    private KeyPriority(){}
    public static String favorite(String key, boolean uppercase) {
        String value;
        switch(key) {
            case "a": value="ä";break;
            case "o": value="ö";break;
            case "u": value="ü";break;
            case "s": value="ß";break;
            default: return null;
        }
        if(!uppercase)return value;
        return key.equals("s")?"ẞ":value.toUpperCase(java.util.Locale.GERMAN);
    }
    public static String[] alternatives(String key,boolean uppercase) {
        String[] values;
        switch(key) {
            case "a":values=new String[]{"ä","á","à","â","ã","å","æ"};break;
            case "o":values=new String[]{"ö","ó","ò","ô","ø","œ"};break;
            case "u":values=new String[]{"ü","ú","ù","û"};break;
            case "s":values=new String[]{"ß","§","ś"};break;
            case "e":values=new String[]{"é","è","ê","ë","€"};break;
            case "i":values=new String[]{"í","ì","î","ï"};break;
            case "c":values=new String[]{"ç","©"};break;
            case "n":values=new String[]{"ñ"};break;
            default:return null;
        }
        if(!uppercase)return values;
        String[] result=values.clone();
        for(int i=0;i<result.length;i++) {
            if(result[i].equals("ß"))result[i]="ẞ";
            else if(result[i].length()==1 && Character.isLetter(result[i].charAt(0)))
                result[i]=result[i].toUpperCase(java.util.Locale.GERMAN);
        }
        return result;
    }
    public static boolean longHold(long durationMs,float displacement,float upwardMovementPx) {
        return durationMs>=410 || displacement>=26 && upwardMovementPx>=30;
    }
}
