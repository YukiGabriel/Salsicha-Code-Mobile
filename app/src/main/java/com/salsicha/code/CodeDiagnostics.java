package com.salsicha.code;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Diagnóstico leve on-device: erros (vermelho) e warnings (amarelo), com linha + offset. */
public final class CodeDiagnostics {
    private CodeDiagnostics() {}

    public enum Kind { ERROR, WARN }

    public static final class Issue {
        public final Kind kind;
        public final int line;
        public final int start;
        public final int end;
        public final String msg;
        Issue(Kind k, int line, int s, int e, String m) {
            kind = k; this.line = line; start = s; end = e; msg = m;
        }
        @Override public String toString() {
            return (kind == Kind.ERROR ? "❌ L" : "⚠️ L") + line + ": " + msg;
        }
    }

    public static List<Issue> analyze(String src) {
        List<Issue> out = new ArrayList<>();
        if (src == null || src.isEmpty()) return out;
        // 1) bracktes / strings via varredura, com offsets
        scanBrackets(src, out);
        // importa Scanner/ArrayList/etc mas esqueceu o import?
        checkMissingImports(src, out);
        // 2) por linha: ; faltando, TODO, etc.
        String[] lines = src.split("\n", -1);
        int offset = 0;
        Pattern classPat = Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)?class\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String t = line.trim();
            int lineNo = li + 1;
            int lineStart = offset;
            int lineEnd = offset + line.length();
            if (!t.isEmpty() && !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
                    && !t.startsWith("@") && !t.startsWith("import ") && !t.startsWith("package ")) {
                // aviso TODO/FIXME
                if (t.contains("TODO") || t.contains("FIXME")) {
                    out.add(new Issue(Kind.WARN, lineNo, lineStart, lineEnd, "marcador pendente (TODO/FIXME)"));
                }
                // erro provável: linha de código sem ; { } : nem continuação
                if (t.matches(".*(System\\.out\\.println.*|return\\s+.+|=\\s*.+|new\\s+.+\\(.*\\)|\\.add\\(.*\\)|\\.put\\(.*\\))\\s*")
                        && !t.endsWith(";") && !t.endsWith("{") && !t.endsWith("}") && !t.endsWith(",")
                        && !t.endsWith("(") && !t.endsWith(":")) {
                    // evita falsos em declarações de método/if/for
                    if (!t.matches("^(if|for|while|switch|catch|synchronized)\\b.*")
                            && !t.matches("^(public|private|protected|static|final|class|void|int|long|String|boolean|double|float)\\b.*\\{?\\s*$")
                            || t.contains("System.out")) {
                        if (t.contains("System.out") || t.matches(".*=.*[^;]$") || t.matches(".*return\\s+[^;]+$")) {
                            out.add(new Issue(Kind.ERROR, lineNo, lineStart, lineEnd, "talvez falte ';' no fim"));
                        }
                    }
                }
                if (t.endsWith(";;")) {
                    out.add(new Issue(Kind.WARN, lineNo, lineEnd - 2, lineEnd, "';;' duplo"));
                }
                if (t.equals("}") && li > 0) {
                    // sem diagnóstico, só estrutura
                }
                // == em Strings (clássico)
                if (t.contains("==") && (t.contains("\"") || t.contains("String"))) {
                    out.add(new Issue(Kind.WARN, lineNo, lineStart, lineEnd, "comparar String com ==? prefira .equals()"));
                }
                // catch vazio
                if (t.matches("catch\\s*\\(.*\\)\\s*\\{?\\s*")) {
                    // olha próxima linha
                    String next = (li + 1 < lines.length) ? lines[li + 1].trim() : "";
                    if (next.equals("}") || next.isEmpty()) {
                        out.add(new Issue(Kind.WARN, lineNo, lineStart, lineEnd, "catch vazio — logue ao menos"));
                    }
                }
            }
            // classe pública: só coleta, validação é na MainActivity com nome do arquivo
            Matcher cm = classPat.matcher(line);
            offset = lineEnd + 1; // + \n
            if (out.size() > 80) break;
        }
        return out;
    }

    private static void checkMissingImports(String src, List<Issue> out) {
        if (src.contains("Scanner") && !src.contains("import java.util.Scanner")) {
            out.add(new Issue(Kind.ERROR, 1, 0, Math.min(20, src.length()), "falta 'import java.util.Scanner;'"));
        }
        if ((src.contains("ArrayList") && !src.contains("import java.util"))) {
            out.add(new Issue(Kind.WARN, 1, 0, Math.min(20, src.length()), "falta import p/ ArrayList (java.util.*)"));
        }
        if ((src.contains("HashMap") || src.contains(" HashSet")) && !src.contains("import java.util")) {
            out.add(new Issue(Kind.WARN, 1, 0, Math.min(20, src.length()), "falta import p/ HashMap/HashSet"));
        }
        if (src.contains("BufferedReader") && !src.contains("import java.io")) {
            out.add(new Issue(Kind.ERROR, 1, 0, Math.min(20, src.length()), "falta 'import java.io.*;'"));
        }
    }

    private static void scanBrackets(String src, List<Issue> out) {
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>(); // [char, line, offset]
        int line = 1;
        int lineStartOff = 0;
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { line++; lineStartOff = i + 1; i++; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int startLine = line; int startOff = i; i += 2;
                boolean closed = false;
                while (i < n) {
                    if (src.charAt(i) == '\n') { line++; lineStartOff = i + 1; }
                    if (src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/') { closed = true; i += 2; break; }
                    i++;
                }
                if (!closed) out.add(new Issue(Kind.ERROR, startLine, startOff, Math.min(startOff + 2, n), "comentário /* sem fechar"));
                continue;
            }
            if (c == '"' || c == '\'') {
                int startLine = line; int startOff = i; char q = c; i++;
                boolean closed = false;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\n' && q == '\'') break;
                    if (d == '\n') { line++; lineStartOff = i + 1; }
                    if (d == '\\') { i += 2; continue; }
                    if (d == q) { closed = true; i++; break; }
                    i++;
                }
                if (!closed) out.add(new Issue(Kind.ERROR, startLine, startOff, Math.min(startOff + 8, n), "string/char sem fechar"));
                continue;
            }
            if (c == '{' || c == '[' || c == '(') { stack.addLast(new int[]{c, line, i}); i++; continue; }
            if (c == '}' || c == ']' || c == ')') {
                char want = c == '}' ? '{' : (c == ']' ? '[' : '(');
                if (stack.isEmpty() || stack.peekLast()[0] != want) {
                    out.add(new Issue(Kind.ERROR, line, i, i + 1, "'" + c + "' sem par"));
                } else stack.removeLast();
                i++; continue;
            }
            i++;
        }
        while (!stack.isEmpty()) {
            int[] o = stack.removeLast();
            out.add(new Issue(Kind.ERROR, o[1], o[2], o[2] + 1, "'" + (char) o[0] + "' sem fechar"));
            if (out.size() > 80) break;
        }
    }
}
