package com.lelloman.paravoidandroid.runtime;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import static org.junit.Assert.*;

public class ShellTextTest {
    private static final Locale ITALIAN=Locale.ITALIAN;
    private static final Pattern LITERAL=Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PLACEHOLDER=Pattern.compile("%(\\d+\\$)?[sd%]");

    @Test public void everyShellTextHasAnItalianTranslationWithTheSamePlaceholders() throws IOException {
        Set<String> keys=sourceKeys();
        assertTrue("source scan found too few texts: "+keys.size(),keys.size()>100);
        Map<String,String> italian=ShellText.TABLES.get("it");
        List<String> problems=new ArrayList<>();
        for(String key:keys) {
            String translated=italian.get(key);
            if(translated==null) problems.add("missing: "+key);
            else if(!placeholders(key).equals(placeholders(translated))) problems.add("placeholders differ: "+key);
        }
        assertTrue(String.join("\n",problems),problems.isEmpty());
        Set<String> unused=new TreeSet<>(italian.keySet()); unused.removeAll(keys);
        assertTrue("unused translations: "+unused,unused.isEmpty());
    }

    @Test public void fallsBackToEnglishAndFormatsArguments() {
        assertEquals("Riavvia",ShellText.tr(ITALIAN,"Restart"));
        assertEquals("Restart",ShellText.tr(Locale.GERMAN,"Restart"));
        assertEquals("Not in any table",ShellText.tr(ITALIAN,"Not in any table"));
        assertEquals("La versione 42 è disponibile per il download.",ShellText.tr(ITALIAN,"Version %1$s is available to download.",42));
        assertEquals("Riavvio di Notes",ShellText.tr(Locale.forLanguageTag("it-CH"),"Restarting %1$s","Notes"));
        assertEquals("1 MB of 2 MB · 50%",ShellText.tr(Locale.ENGLISH,"%1$s of %2$s · %3$d%%","1 MB","2 MB",50));
    }

    @Test public void generatedRestartStringsMatchTheShellTable() throws IOException {
        Path restart=Paths.get("../paravoid-gradle-plugin/src/main/resources/com/lelloman/paravoidandroid/gradle/restart");
        Map<String,String> english=strings(restart.resolve("values/paravoid_restart.xml"));
        Map<String,String> italian=strings(restart.resolve("values-it/paravoid_restart.xml"));
        assertEquals(english.keySet(),italian.keySet());
        for(Map.Entry<String,String> entry:english.entrySet())
            assertEquals(entry.getKey(),ShellText.TABLES.get("it").get(entry.getValue()),italian.get(entry.getKey()));
    }
    private static Map<String,String> strings(Path file) throws IOException {
        Map<String,String> strings=new TreeMap<>();
        Matcher m=Pattern.compile("<string name=\"(\\w+)\">(.*?)</string>").matcher(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        while(m.find()) strings.put(m.group(1),m.group(2).replace("\\'","'"));
        return strings;
    }

    /** Key literals of every tr(...) call in shell UI sources. */
    private static Set<String> sourceKeys() throws IOException {
        List<Path> roots=Arrays.asList(Paths.get("src/main/java"),Paths.get("../delivery/android/src"));
        Set<String> keys=new TreeSet<>();
        for(Path root:roots) try(Stream<Path> files=Files.walk(root)) {
            for(Path file:files.filter(p -> p.toString().endsWith(".java") && !p.endsWith("ShellText.java")).collect(Collectors.toList())) {
                String source=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);
                Matcher call=Pattern.compile("\\btr\\(").matcher(source);
                while(call.find()) {
                    String argument=firstArgument(source,call.end());
                    Matcher literal=LITERAL.matcher(argument);
                    while(literal.find()) keys.add(unescape(literal.group(1)));
                }
                // RestartActivity's theme texts fall back to ShellText.tr(fallback).
                Matcher restart=Pattern.compile("\\btext\\(\"paravoidRestart\\w+\",\"((?:[^\"\\\\]|\\\\.)*)\"\\)").matcher(source);
                while(restart.find()) keys.add(unescape(restart.group(1)));
            }
        }
        return keys;
    }
    /** The first argument holding a string literal: the key of tr("..."), or of ShellText.tr(context,"..."). */
    private static String firstArgument(String source,int start) {
        int depth=0, from=start; boolean quoted=false;
        for(int i=start;i<source.length();i++) {
            char c=source.charAt(i);
            if(quoted) { if(c=='\\') i++; else if(c=='"') quoted=false; continue; }
            if(c=='"') quoted=true;
            else if(c=='(') depth++;
            else if((c==')' || c==',') && depth==0) {
                String argument=source.substring(from,i);
                if(argument.contains("\"") || c==')') return argument;
                from=i+1;
            } else if(c==')') depth--;
        }
        return "";
    }
    private static String unescape(String literal) { return literal.replace("\\n","\n").replace("\\\"","\"").replace("\\\\","\\"); }
    private static List<String> placeholders(String text) {
        List<String> found=new ArrayList<>(); Matcher m=PLACEHOLDER.matcher(text);
        while(m.find()) found.add(m.group()); Collections.sort(found); return found;
    }
}
