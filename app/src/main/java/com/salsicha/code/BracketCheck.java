package com.salsicha.code;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Checagem leve: chaves/colchetes/parênteses balanceados + strings fechadas, com linhas. */
public final class BracketCheck {

    private BracketCheck() {}

    private static final class Open {
        final char ch; final int line;
        Open(char c, int l) { ch = c; line = l; }
    }

    public static List<String> check(String src) {
        List<String> out = new ArrayList<>();
        Deque<Open> stack = new ArrayDeque<>();
        int line = 1;
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int start = line; i += 2;
                boolean closed = false;
                while (i < n) {
                    if (src.charAt(i) == '\n') line++;
                    if (src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/') { closed = true; i += 2; break; }
                    i++;
                }
                if (!closed) out.add("Linha " + start + ": comentário /* sem fechar");
                continue;
            }
            if (c == '"' || c == '\'') {
                int start = line; char q = c; i++;
                boolean closed = false;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\n' && q == '\'') break;
                    if (d == '\\') { i += 2; continue; }
                    if (d == q) { closed = true; i++; break; }
                    if (d == '\n') line++;
                    i++;
                }
                if (!closed) out.add("Linha " + start + ": string/char sem fechar");
                continue;
            }
            if (c == '{' || c == '[' || c == '(') { stack.addLast(new Open(c, line)); i++; continue; }
            if (c == '}' || c == ']' || c == ')') {
                char want = c == '}' ? '{' : (c == ']' ? '[' : '(');
                if (stack.isEmpty() || stack.peekLast().ch != want) {
                    out.add("Linha " + line + ": '" + c + "' sem par correspondente");
                } else {
                    stack.removeLast();
                }
                i++; continue;
            }
            i++;
        }
        while (!stack.isEmpty()) {
            Open o = stack.removeLast();
            out.add("Linha " + o.line + ": '" + o.ch + "' sem fechar");
            if (out.size() > 50) break;
        }
        return out;
    }
}
