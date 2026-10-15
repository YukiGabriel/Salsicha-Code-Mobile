package com.salsicha.code;

import java.util.ArrayList;
import java.util.List;

/** Sugestões simples de Java p/ autocomplete on-device, sem dependências. */
public final class JavaSuggest {
    private JavaSuggest() {}

    private static final String[] KEYWORDS = {
            "abstract", "assert", "break", "case", "catch", "class", "continue",
            "default", "do", "else", "enum", "extends", "final", "finally", "for",
            "if", "implements", "import", "instanceof", "interface", "new",
            "package", "private", "protected", "public", "return", "static",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "try", "void", "volatile", "while", "var", "record", "sealed", "permits", "yield"
    };

    private static final String[] TYPES = {
            "boolean", "byte", "char", "short", "int", "long", "float", "double",
            "String", "Object", "List", "ArrayList", "Map", "HashMap", "Set",
            "HashSet", "Integer", "Long", "Boolean", "Double", "Exception",
            "System", "StringBuilder", "Math"
    };

    private static final String[] SNIPPETS = {
            "public class Main {",
            "public static void main(String[] args) {",
            "System.out.println(",
            "import java.util.Scanner;",
            "import java.util.ArrayList;",
            "import java.util.HashMap;",
            "import java.util.List;",
            "Scanner sc = new Scanner(System.in);",
            "String s = sc.nextLine();",
            "int n = sc.nextInt();",
            "for (int i = 0; i < n; i++) {",
            "for (String s : list) {",
            "while (",
            "if (",
            "else {",
            "try {",
            "catch (Exception e) {",
            "private int ",
            "private String ",
            "public int ",
            "public void ",
            "@Override",
            "import java.util.",
            "String.format(",
            "Math.max(",
            "Math.min(",
            "list.add(",
            "map.put(",
    };

    public static List<String> suggest(String prefix, String fullText) {
        List<String> out = new ArrayList<>();
        if (prefix == null) prefix = "";
        String p = prefix.trim();
        if (p.length() < 1) return out;
        // coleta identificadores do próprio arquivo (variáveis/métodos) como sugestão
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}")
                    .matcher(fullText != null ? fullText : "");
            while (m.find() && seen.size() < 30) {
                String w = m.group();
                if (w.startsWith(p) && !w.equals(p)) seen.add(w);
            }
        } catch (Exception ignored) {}
        for (String s : SNIPPETS) {
            String key = s.replaceAll("^[^A-Za-z]+", "");
            if (key.startsWith(p) || s.startsWith(p)) out.add(s);
            if (out.size() >= 6) break;
        }
        for (String k : TYPES) {
            if (k.startsWith(p) && out.size() < 10) out.add(k);
        }
        for (String k : KEYWORDS) {
            if (k.startsWith(p) && out.size() < 12) out.add(k);
        }
        for (String w : seen) {
            if (out.size() >= 12) break;
            if (!out.contains(w)) out.add(w);
        }
        return out;
    }

    public static String currentPrefix(CharSequence text, int cursor) {
        if (text == null || cursor <= 0 || cursor > text.length()) return "";
        int i = cursor - 1;
        while (i >= 0) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') i--;
            else break;
        }
        int start = i + 1;
        if (start >= cursor) return "";
        String w = text.subSequence(start, cursor).toString();
        if (w.length() < 2) return "";
        return w;
    }
}
