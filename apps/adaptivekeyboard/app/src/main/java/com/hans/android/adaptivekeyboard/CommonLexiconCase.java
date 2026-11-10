package com.hans.android.adaptivekeyboard;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immediate canonical spelling for common words before corpus-case loading. */
public final class CommonLexiconCase {
    private CommonLexiconCase() { }
    private static final Map<String,String> DE=build(
        "die der und in das ich ist nicht zu den von mit es ein auf für im sie eine sich auch dem des als er an dass so bei wie aber aus hat sind nach noch wir du war nur wenn oder " +
        "wird was werden einen am man um haben einer zum kann über da dann einem schon habe ja mehr mir sein vor mal zur durch hier mich bis doch ihr immer jetzt diese wurde wieder keine alle sehr können muss uns bin " +
        "unter vom gut gibt hatte ihre dieser gegen seine alles also geht viel Zeit ab ganz machen denn meine damit dir will einfach ohne weil beim etwas waren zwei mein eines kein seit soll selbst wo dich Menschen hab heute kommt nichts " +
        "nun würde Leben wer macht anderen Deutschland ihm Jahr ob ihn jahren seiner viele zwischen Jahre wäre diesem dieses wurden dabei dort müssen Gerade Neue sondern erst Ersten ihrer lassen sollte andere Hast warum weiss weiter wirklich ihnen ihren Mann Wohl Drei " +
        "seinen vielleicht dazu diesen euch kommen Wollen Bitte hätte sei könnte Sehen sowie Tag Berlin dafür ins Sagen steht während bereits Deutschen Ende nie Uhr Weg Wegen besser einmal Frau Stadt Welt Gehen gemacht genau Danke gar konnte Leute Neuen seinem unsere " +
        "Dies etwa jeder natürlich the bist Gute Recht finden geben Kinder Werde Geld gleich keinen Lange sagt Teil allem beiden davon eigentlich fast richtig Tun Arbeit de denen Deutsche Erste Frage meiner sieht zurück deine jedoch klar Paar Grosse Grossen liegt welche " +
        "Wissen zusammen einige Fall gab kannst sicher Frauen ihrem kam Allen darauf Hatten Letzten meinen Nein schön sogar vielen worden zwar allerdings Art schnell sollen darf jeden jemand später stehen wollte Ganze gerne möchte Seite Spiel weitere wenig heisst Liebe oft sagte " +
        "Fragen Land neben statt dein gehört lässt Platz besonders Geschichte ne nehmen of darüber deren hin Vier gesagt Guten kurz meinem Morgen sonst unser bekommen bzw Euro gesehen Kleine Spielen weniger Familie ging Haus sollten Arbeiten Bild bleibt eigenen je möglich weit " +
        "deshalb eben Gewesen Glaube Minuten Polizei ausserdem beide findet Ganzen Leider musste online Stellen Tage Unternehmen Woche Würden Besten Bleiben daher Grund kaum Namen Problem daran Halt Nacht neu Stand Stunden Thema Beispiel genug allein dessen direkt echt Halten Kleinen Schule solche " +
        "zeigt überhaupt Anders Essen Lang USA Abend Dank ebenfalls eher hinter mag Musik München pro Wasser Alten bald erhalten Junge lieber nachdem Ort sofort Zweiten Aller Auto bringen danach deutlich DR finde Hand jede Kopf Männer nächsten unserer Us damals endlich EU " +
        "Fünf gefunden gilt Person schwer wichtig zeigen egal Gesellschaft Hoch Letzte stark Alter Anfang Gegenüber Kind oben Rund SPD Strasse bekannt bisher Eltern Europa Gott hätten Los Mutter Wochen Augen Beste braucht Denke Gruppe her Is meisten Millionen Schreiben Treffen trotzdem Artikel " +
        "bestimmt Deutsch Folge Glück Herr Hilfe leicht mehrere Niemand Politik Sache scheint zunächst Alte Bayern Bisschen Denken fand Film Internet Laut Lesen oh Personen Regierung Richtung schliesslich stellt Team and Buch Dinge dürfen früher geworden ii Prozent Sohn Spass Stelle Wort Bereich " +
        "besteht bringt Ernst gross Mensch Mädchen Vater Zukunft All darum eigene Fahren Fest Form Frei Idee jedes keiner Liegen Neues passiert Österreich bevor gegeben Hamburg jedem manchmal raus Super tut Wahl Wert Wien einzige Entwicklung Folgen Freunde führt helfen Kosten könnten obwohl " +
        "sehe Suchen weiteren Ziel ziemlich Angst ca dadurch Erfolg Hause Krieg Lage läuft Mai Probleme September To verstehen voll wann wieso AfD Alt aufgrund brauchen innerhalb insgesamt Klasse konnten nimmt ok Preis Rein Rolle stimmt Tagen Tod völlig zudem Ach ausser CDU " +
        "gekommen gern Gestern irgendwie Kirche meist März na seines Trotz Vergessen verschiedenen vorbei Zahlen anderes Blick dagegen deinen ebenso Führen kaufen Lernen Meinung Name Partei Reden Seiten willst wobei Zweite hält Jungen Moment schlecht Sechs Spiele spielt Sprechen Video August Bilder dachte " +
        "Glauben Mitte November scheisse sowohl st wahrscheinlich April deiner einigen Frankfurt Freund insbesondere Per Peter Raum spricht System tatsächlich anderem bekommt erreicht gemeinsam Juli Juni Links mach new nämlich Oktober Schaffen setzen Suche versucht bedeutet Einsatz Falls gleichzeitig grosser habt Hören ihres " +
        "Köln Möglichkeit nr Rahmen Rechts Sachen Schweiz Stück welches fällt genommen hoffe Höhe Job liess Michael nächste Sex Sommer Stunde Titel Tochter unseren Wohnung wären Zahl Zehn handelt kennen Kunst Mitarbeiter sah Sorgen Sprache verloren Weise zumindest übrigens Bad Band Chance Fehler " +
        "gebracht Hallo hinaus langsam Menge Meter mindestens musst selber Sicherheit Spieler drauf erklärt Erster Gefühl gleichen km Laufen Medien Saison TV verschiedene weiterhin ziehen überall Antwort Bahn Berliner dennoch Dezember Gesicht Guter machte Monate seien somit Thomas toll Tragen Vergleich Versuchen Entscheidung " +
        "Gedanken Gehören Gemeinde Gutes Herz Jahres Januar jeweils Nutzen Nähe siehe Sinn Stuttgart Sucht vorher welcher bereit Daten deinem falsch fertig Foto häufig Interesse Kultur König la Luft Lösung nen Reihe Schöne Stimme Boden Bruder Erreichen Februar Frankreich fussball heraus Informationen kennt " +
        "Kritik Manche Neuer nochmal Opfer passt plötzlich Region richtige Schwarz seid setzt Situation Sonntag unten Warten Wirtschaft anderer Bevölkerung darin Deutscher Firma gedacht geschrieben Gewinnen hohen Kamen Kleiner Kreis Körper lass länger Mitglieder Nachrichten Punkt Rede Typ Verein verwendet Zeiten Ausbildung Beginn " +
        "beispielsweise deswegen Ergebnis eure Freundin gestellt Grünen kenne Kindern Licht Markt mittlerweile Monat offen Politiker politische Rechte Regel Russland solchen Staat teilweise unserem weder Acht Bedeutung europäischen FC folgenden Gefallen Hälfte lag Martin Programm reicht Schüler selten Sieben sowas Text unbedingt unterwegs " +
        "Verbindung wenigstens Zeitung zuvor Ahnung Bürger Eins Erfahrung erklären erzählt funktioniert Grad grösste hohe indem Kampf Kraft mache Monaten nahm okay politischen Projekt Präsident Punkte Sport Tat Zimmer zuerst alleine Druck entfernt Facebook fehlt genauso grössten Hans Herren hört lediglich Liste Schauen " +
        "soweit Stimmen Wochenende beginnt bietet dran Dritten durchaus erwartet Fallen gehe inzwischen komplett Kunden müsste niemals On Schau Schritt Staaten Unterstützung Verfügung Verlassen wenige wirst Wählen Angebot Bett blieb Einzelnen erneut Gefahr gefällt genannt Google hilft Klein Linie Max Natur Schuld Top " +
        "Wahrheit Worte wusste öffentlichen aufs co etc GmbH hey it klingt knapp Live Mannschaft Ordnung Paris Reich Rot Samstag Satz Teilen Themen vermutlich ändern Aufgabe bezeichnet Chef Dollar entweder entwickelt Fotos freuen geh gewählt John Lehrer Leipzig Runde scheiss Schluss Serie Teile " +
        "Total verstehe veröffentlicht wollten ähnlich Bank Beitrag cm Entscheiden erkennen Freitag Früh Gebäude Gesetz gleiche Hotel jedenfalls komm Ländern Nahe nennen zieht Zug aktuell Bewegung China Ehe Ehrlich Erde erfahren Ergebnisse Flüchtlinge folgt gebe gehabt gespielt Gewalt Gold Gruppen Gründen Karl Nix " +
        "No Nummer Rechten Reise Sieg Sitzen Start Tür Universität verdient Verhalten verstanden Aktiv Besuch Bildung darunter denkt dürfte eh Englisch gefragt glücklich Grosses Hart irgendwann Landes Leuten Linken Länder offenbar Post Rest Ruhe schreibt Sinne Werk Begriff Bericht drin Erwarten Fans Fenster " +
        "for Freien Freiheit Hannover jemanden Lebens lebt meines mussten nennt Schönen Stolz Verfahren verkauft Versuche vorstellen york zahlreiche Zeichen zuletzt absolut arbeitet Auswahl Christian derzeit Dritte erfolgreich Freude http interessiert Jungs Leistung Lieben meint meinte Montag Plan regelmässig stellte trifft Versuch Vertrag " +
        "wichtige Zusammenhang Angaben Arzt befindet daraus en erstmals gegangen getan Grenzen Hund Linke mm sag schneller Sicht Zeitpunkt Anzahl Baden Bau bloss entsprechend erstmal geführt glaub grösse heutigen Hintergrund immerhin Karte Langen Lust mehreren Meister Regeln sobald solange Sonne Tiere trägt unterstützt " +
        "Volk Website wenigen al Arsch ausserhalb bestehen folgende Frank gelesen gesamte gezeigt Grenze Handy internationalen Kaffee komme Kontakt könnt Laden lustig Mittel Paul Perfekt Position richtigen Schloss Schutz Studium ständig Unterschied verkaufen Videos wagen Winter you zusätzlich AG aktuelle anschliessend benutzt Bücher " +
        "Freie gewonnen heissen Italien Kollegen legen Leid London Mitglied naja Quelle Sachsen Studie Tages Technik Tisch Union Urlaub verletzt weisst welchen Werbung Amt Auge Betrieb Bier brachte Club draussen Einfluss Erfahrungen erwähnt"
    );
    private static final Map<String,String> EN=build(
        "the to and of a in i is for that you it on with this was be as are have at he not by but from my or we an your all so his they me if one can will just like " +
        "about up out what has when more do no were who had it's their there her which time get been would she new people how don't some also them now other i'm its our than good only after first him into know " +
        "see two make over think any then could back these us want because go well said way most much very where even should may here need really did right work year years being day too going before off why made still take " +
        "got many never those life say world down great through you're last that's while best such love man home long look something use can't same used both every am come part state three around between always better find help high little old " +
        "since another does own things under during game i've thing give house place school again next each mr without against didn't end found must show big feel sure team ever family keep might please put money free second someone away left number " +
        "city days lot name night play until company doing few he's let real called different having set thought done however getting god government group looking public top women business care start system times week already anything case nothing person today change enough " +
        "everything full live making point read there's told yet bad doesn't four hard mean once support tell including music power seen states stop water based believe call head men national small took white came far job side though try went yes actually " +
        "american later less line order party run says service country open season shit thank children everyone general they're trying united using area black following law makes together war whole car face five kind maybe per president story working course games health hope " +
        "important least means news within able book early friends i'll information local oh post thanks video young ago others social talk court fact given guys half hand isn't level mind often single become body coming control death food guy hours office pay " +
        "problem south true we're almost fuck history known large lost research room several started taking university win wrong along anyone else girl john matter pretty remember air bit friend hit needs nice playing probably saying understand yeah york class close comes i'd " +
        "idea international looks past possible wanted cause due happy human members months move question series wait woman ask community data late leave north saw special watch won't either fucking future light low million morning police short stay taken age buy deal rather " +
        "reason red report soon third turn whether among check development form further heart minutes myself services yourself act although asked child fire fun living major media phone players art behind building easy gonna market near non plan political quite six talking west " +
        "works according available education final former front kids list ready sometimes son street wasn't bring college current example experience heard london meet program type baby chance father march process she's song study word across action clear gave gets himself month outside self " +
        "students words board cost cut dr field held instead main moment mother road seems thinking town wants de department energy fight fine force hear issue played points price re rest results running shows space summer term wife america beautiful date goes killed " +
        "land miss project sex shot site strong you'll account co especially eyes include june parents period position record similar total above club common died film happened knew lead likely military perfect personal security share st tv what's won april center county couple " +
        "dead english happen hold industry inside issues online player private problems return rights sense star test view weeks break british companies event higher hour member middle needed present result sorry takes training wish wouldn't answer boy design finally girls gold gone guess " +
        "interest july king learn policy society added al alone average bank brought certain church east hands hot let's longer medical movie original park performance press received role sent themselves tried worked worth areas became bill books cool director exactly giving ground meeting " +
        "provide questions relationship september sound source usually value evidence follow lives official ok production rate reading round save stand stuff tax whatever amount blue countries david drive eat fall fast federal feeling felt green league management match model picture size step trust " +
        "you've central changes england forward groups hey key mom page paid range review science trade uk upon various attention brother cannot character chief cup football hate haven't james led looked lower natural october property quality send style vote amazing august blood china " +
        "complete dog economic hell involved itself language lord november oil related serious stage terms title add article attack born couldn't damn decided decision enjoy entire french january kill met perhaps poor release situation technology turned website written choice code considered continue council " +
        "cover currently door election european events financial foreign hair increase legal lose michael pick race seem seven sign simple simply staff super union walk washington bed began built career changed crazy daily daughter december die difficult figure hospital knows loss modern ones " +
        "paper parts popular published safe starting systems version voice whose writing army australia earth forget goal huge internet listen okay practice rules sea sir success towards waiting ways access aren't base below created deep followed la lol mark missing offer pass professional " +
        "released risk schools sleep table ten truth ball box build card cases dark district europe george india mine minister note percent piece products recent seeing straight visit wall wanna we've wrote allowed boys culture etc fans february gives growth included married officer " +
        "pain paul places respect response river rock shall speak specific standard tonight write album century charge cold create effect eight except eye funny ii limited moving network peace provided recently required sales spent store student tomorrow track via watching weight addition ahead " +
        "allow anti association beat brown capital chinese committee conference difference double expect gas island moved normal plans population potential pressure radio russian station text treatment western ass beginning california campaign certainly completely content credit cross described despite female focus hi husband ice " +
        "individual interesting join kept leading loved message miles nearly particular previous quickly region reported section sort speed travel consider contact drop fair feet jesus kid link positive sale throughout tour welcome absolutely additional beyond conditions earlier extra forces immediately jobs leaving minute " +
        "nature numbers quick sell significant studies unless winning agree canada clean computer construction episode favorite income justice levels manager movement photo posted safety san scene sold sounds spend statement sun teams ability announced asking calling coach collection continued costs definitely designed expected " +
        "friday gun happens heavy includes knowledge particularly search subject train wide wow author centre claim dad developed fear fit generally german global goals gotta hotel interested judge lady leader letter lines material named nobody opportunity plus pre product regular secretary sister stories " +
        "unit workers annual anymore bar battle brain contract degree families features finished floor france growing hurt image insurance majority meant opening opinion physical pro reach rule seriously sports stupid successful active administration approach australian biggest cancer civil dance defense direction independent master " +
        "none reasons russia ship stock trump weekend wonder worst africa awesome band beach cash clearly commercial compared effort ended fan fighting imagine impact lack latest learning multiple older operation organization passed pictures"
    );
    private static Map<String,String> build(String words) {
        Map<String,String> dictionary=new LinkedHashMap<>();
        for(String spelling:words.split(" ")) {
            if(!spelling.isEmpty())dictionary.put(spelling.toLowerCase(Locale.GERMAN),spelling);
        }
        return dictionary;
    }
    // The lists above are already bundled in frequency order. Make them
    // available to the lightweight predictor while the full assets load.
    static void seed(PredictionModel model) {
        int rank=0;
        for(String word:DE.values())model.addBaseWord(word,rank++,"de");
        rank=0;
        for(String word:EN.values())model.addBaseWord(word,rank++,"en");
    }
    static double languageSignal(String word) {
        return (EN.containsKey(word)?1:0)-(DE.containsKey(word)?1:0);
    }
    /**
     * Correct accidental all-caps from the last personal selection, but never
     * alter an explicit user-typed prefix or unknown proper/acronym spelling.
     */
    public static String canonical(String word,String language) {
        if(word==null||word.length()<2)return word;
        if(!word.equals(word.toUpperCase(Locale.GERMAN)))return word;
        String known=("en".equals(language)?EN:DE).get(word.toLowerCase(Locale.GERMAN));
        return known==null?word:known;
    }
}
