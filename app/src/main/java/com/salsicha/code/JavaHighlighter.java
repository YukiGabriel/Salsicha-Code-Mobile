package com.salsicha.code;

import android.graphics.Color;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Highlight simples de Java via regex. Rápido o bastante p/ arquivos pequenos no celular. */
public final class JavaHighlighter {

    private JavaHighlighter() {}

    public static final class Colors {
        public final int keyword, type, string, comment, number, annotation, text, background, gutterBg, gutterText;
        Colors(int keyword, int type, int string, int comment, int number, int annotation,
               int text, int background, int gutterBg, int gutterText) {
            this.keyword = keyword; this.type = type; this.string = string;
            this.comment = comment; this.number = number; this.annotation = annotation;
            this.text = text; this.background = background;
            this.gutterBg = gutterBg; this.gutterText = gutterText;
        }
    }

    public static final Colors DARK = new Colors(
            Color.parseColor("#E8A33D"), Color.parseColor("#7DD3FC"),
            Color.parseColor("#4ADE80"), Color.parseColor("#8A7F85"),
            Color.parseColor("#FF6B6B"), Color.parseColor("#C084FC"),
            Color.parseColor("#FFF9F7"), Color.parseColor("#171114"),
            Color.parseColor("#241A1E"), Color.parseColor("#8A7F85"));

    public static final Colors LIGHT = new Colors(
            Color.parseColor("#B45309"), Color.parseColor("#0369A1"),
            Color.parseColor("#15803D"), Color.parseColor("#8A7F85"),
            Color.parseColor("#DC2626"), Color.parseColor("#7E22CE"),
            Color.parseColor("#2B2124"), Color.parseColor("#FFF9F7"),
            Color.parseColor("#F1E8E4"), Color.parseColor("#8A7F85"));

    private static final Pattern TOKEN = Pattern.compile(
            "(?<comment>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)"
            + "|(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')"
            + "|(?<annotation>@[A-Za-z_][A-Za-z0-9_]*)"
            + "|(?<number>\\b\\d[\\d_]*(?:\\.\\d+)?[lLfFdD]?)"
            + "|(?<keyword>\\b(?:abstract|assert|break|case|catch|class|const|continue|default|do|else|enum|extends|final|finally|for|goto|if|implements|import|instanceof|interface|native|new|package|private|protected|public|return|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|var|record|sealed|permits|yield)\\b)"
            + "|(?<type>\\b(?:boolean|byte|char|short|int|long|float|double|String|Object|List|ArrayList|Map|HashMap|Set|Integer|Long|Boolean|Double|void)\\b)");

    public static void highlight(Editable e, Colors c) {
        for (ForegroundColorSpan s : e.getSpans(0, e.length(), ForegroundColorSpan.class)) {
            e.removeSpan(s);
        }
        if (e.length() > 200_000) return;
        Matcher m = TOKEN.matcher(e);
        while (m.find()) {
            int color = -1;
            if (m.group("comment") != null) color = c.comment;
            else if (m.group("string") != null) color = c.string;
            else if (m.group("annotation") != null) color = c.annotation;
            else if (m.group("number") != null) color = c.number;
            else if (m.group("keyword") != null) color = c.keyword;
            else if (m.group("type") != null) color = c.type;
            if (color != -1) {
                e.setSpan(new ForegroundColorSpan(color), m.start(), m.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
