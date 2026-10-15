package com.salsicha.code;

import java.util.ArrayDeque;
import java.util.Deque;

/** Desfazer/refazer por snapshots de texto, agrupando digitação contínua. */
public class EditHistory {

    public static final class State {
        public final String text; public final int cursor;
        State(String t, int c) { text = t; cursor = c; }
    }

    private static final int CAP = 100;
    private static final long GROUP_MS = 1500;

    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();
    private long lastPush;

    public void push(String text, int cursor, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && !undo.isEmpty() && now - lastPush < GROUP_MS) return;
        State top = undo.peekLast();
        if (top != null && top.text.equals(text)) return;
        undo.addLast(new State(text, cursor));
        while (undo.size() > CAP) undo.removeFirst();
        redo.clear();
        lastPush = now;
    }

    public void clear() { undo.clear(); redo.clear(); }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public State undo(String current, int cursor) {
        if (undo.isEmpty()) return null;
        redo.addLast(new State(current, cursor));
        State s = undo.removeLast();
        lastPush = 0;
        return s;
    }

    public State redo(String current, int cursor) {
        if (redo.isEmpty()) return null;
        undo.addLast(new State(current, cursor));
        State s = redo.removeLast();
        lastPush = 0;
        return s;
    }

    public int lastCursor(boolean wasUndo) {
        Deque<State> from = wasUndo ? undo : redo;
        State s = from.peekLast();
        return s == null ? -1 : s.cursor;
    }
}
