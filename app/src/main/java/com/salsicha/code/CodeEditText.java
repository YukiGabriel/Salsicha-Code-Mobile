package com.salsicha.code;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

import androidx.appcompat.widget.AppCompatEditText;

/** EditText de código: sem quebra (scroll horizontal via parent), gutter sync + cursor. */
public class CodeEditText extends AppCompatEditText {

    public interface Listener {
        void onScrolled(int y);
        void onCursor(int line, int col);
    }

    private Listener listener;
    private boolean wrap = false;

    public CodeEditText(Context ctx) { super(ctx); init(); }
    public CodeEditText(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }
    public CodeEditText(Context ctx, AttributeSet attrs, int defStyle) { super(ctx, attrs, defStyle); init(); }

    private void init() {
        try {
            setTypeface(android.graphics.Typeface.MONOSPACE);
        } catch (Exception ignored) {}
        // aplica após inflação, quando já tem parent
        post(() -> setWrapEnabled(false));
    }

    public void setListener(Listener l) { listener = l; }

    /** false = estilo IDE (sem quebrar, rola no HorizontalScrollView pai). true = quebra visual. */
    public void setWrapEnabled(boolean enabled) {
        wrap = enabled;
        try {
            // Interno nunca quebra por palavra: deixa o Layout medir por linha real
            setHorizontallyScrolling(true);
            setHorizontalScrollBarEnabled(false);
            setVerticalScrollBarEnabled(true);
            setSingleLine(false);
            setMaxLines(Integer.MAX_VALUE);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            }
            // Largura dita o wrap: WRAP_CONTENT = cresce p/ lado (sem quebra),
            // MATCH_PARENT = limita à tela (com quebra)
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null) {
                if (!enabled) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                } else {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                }
                setLayoutParams(lp);
            }
            // scroll horizontal fica com o pai (editorHScroll)
            try {
                android.view.ViewParent p = getParent();
                if (p instanceof android.widget.HorizontalScrollView) {
                    setHorizontallyScrolling(!enabled);
                }
            } catch (Exception ignored) {}
            requestLayout();
        } catch (Exception ignored) {}
    }

    public boolean isWrapEnabled() { return wrap; }

    @Override
    protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (listener != null && y != oldY) listener.onScrolled(y);
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        try {
            if (listener != null && getLayout() != null && selStart >= 0 && selStart <= length()) {
                int lineIdx = getLayout().getLineForOffset(selStart);
                int line = lineIdx + 1;
                int col = selStart - getLayout().getLineStart(lineIdx) + 1;
                listener.onCursor(line, col);
            }
        } catch (Exception ignored) {}
    }
}
