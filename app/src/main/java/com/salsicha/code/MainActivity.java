package com.salsicha.code;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Salsicha Code — editor Java simples p/ Android: abas, highlight, buscar, temas,ctor salvar. */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "salsicha_code";
    private static final String SAMPLE = "public class Main {\n"
            + "    public static void main(String[] args) {\n"
            + "        // Bem-vindo ao Salsicha Code! 🌭\n"
            + "        String msg = \"Olá, Android!\";\n"
            + "        System.out.println(msg);\n"
            + "        for (int i = 0; i < 3; i++) {\n"
            + "            System.out.println(\"i = \" + i);\n"
            + "        }\n"
            + "    }\n"
            + "}\n";

    private static final class Doc {
        String title; String uri; String savedText;
        String cached; int sel;
        Doc(String t, String u, String s) { title = t; uri = u; savedText = s; cached = s; }
    }

    private final List<Doc> docs = new ArrayList<>();
    private int current = -1;
    private int untitledCount = 0;

    private CodeEditText editor;
    private TextView gutter, status, consoleView, consoleTitle, appSubtitle;
    private LinearLayout toolbar, tabstrip, symbolbar, consoleBox, editorCard;
    private android.widget.ScrollView consoleScroll;
    private final EditHistory history = new EditHistory();
    // v2.1: RunManager SOMENTE sob demanda (nunca na abertura).
    // O R8/ECJ é pesado e pode falhar ao verificar em alguns aparelhos —
    // se fosse campo `new RunManager()` aqui, o app fecharia ao abrir.
    private RunManager runMgr;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable highlightTask;
    private boolean selfChange = false;
    private boolean dark;
    private boolean wrapEnabled = false;
    private float fontSp = 15f;
    private int curLine = 1, curCol = 1;
    private final List<CodeDiagnostics.Issue> diagnostics = new ArrayList<>();
    private LinearLayout suggestBar;
    private android.widget.HorizontalScrollView suggestBarScroll;
    private Uri projectTree;
    private String projectName = "";

    private final List<int[]> matches = new ArrayList<>();
    private int matchIndex = -1;
    private String lastQuery = "";

    private int pendingSaveDoc = -1;

    private final ActivityResultLauncher<String[]> openLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    openDoc(uri);
                }
            });

    private final ActivityResultLauncher<String> createLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/x-java"), uri -> {
                if (uri != null && pendingSaveDoc >= 0 && pendingSaveDoc < docs.size()) {
                    Doc d = docs.get(pendingSaveDoc);
                    d.uri = uri.toString();
                    d.title = displayName(this, uri);
                    writeDoc(d, editor.getText().toString());
                    pushRecent(d);
                    refreshTabs(); updateStatus();
                }
                pendingSaveDoc = -1;
            });

    @Override
    protected void onCreate(Bundle state) {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        dark = p.getBoolean("dark", true);
        fontSp = p.getFloat("font", 15f);
        wrapEnabled = p.getBoolean("wrap", false);
        AppCompatDelegate.setDefaultNightMode(
                dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        try {
            onCreateInner(state);
        } catch (Throwable t) {
            android.util.Log.e("SalsichaCode", "Falha ao abrir", t);
            saveCrashToFile(t);
            android.widget.TextView tv = new android.widget.TextView(this);
            String trace = android.util.Log.getStackTraceString(t);
            if (trace.length() > 4000) trace = trace.substring(0, 4000);
            tv.setText("Salsicha Code tropeçou ao abrir:\n\n" + trace
                    + "\n\nTire um print e mande ao Yuki!");
            tv.setTextColor(0xFFFF6B6B);
            tv.setBackgroundColor(0xFF171114);
            tv.setPadding(24, 24, 24, 24);
            tv.setTextSize(13f);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(tv);
            setContentView(sv);
            return;
        }
        showLastCrashIfAny();
        android.util.Log.i("SalsichaCode", "onCreate action=" + getIntent().getAction());
        if ("com.salsicha.code.RUN_TEST".equals(getIntent().getAction())) {
            android.util.Log.i("SalsichaCode", "RUN_TEST agendado");
            handler.postDelayed(this::doRun, 1500);
        }
    }

    private void onCreateInner(Bundle state) {
        editor = findViewById(R.id.editor);
        gutter = findViewById(R.id.gutter);
        status = findViewById(R.id.statusbar);
        toolbar = findViewById(R.id.toolbar);
        tabstrip = findViewById(R.id.tabstrip);
        symbolbar = findViewById(R.id.symbolbar);
        consoleBox = findViewById(R.id.consolebox);
        consoleView = findViewById(R.id.console);
        consoleTitle = findViewById(R.id.consoletitle);
        editorCard = findViewById(R.id.editorCard);
        appSubtitle = findViewById(R.id.appsubtitle);
        try {
            consoleScroll = findViewById(R.id.consoleScroll);
        } catch (Exception e) {
            consoleScroll = (android.widget.ScrollView) consoleView.getParent();
        }
        findViewById(R.id.consolehide).setOnClickListener(v -> consoleBox.setVisibility(View.GONE));
        findViewById(R.id.consoleclear).setOnClickListener(v -> consoleView.setText(""));
        wireConsoleInput();
        try {
            View runFab = findViewById(R.id.runFab);
            if (runFab != null) runFab.setOnClickListener(v -> doRun());
        } catch (Exception ignored) {}
        findViewById(R.id.consolestop).setOnClickListener(v -> {
            if (runMgr != null) runMgr.stop();
            consolePrint("\n⏹ Parada solicitada (loops infinitos podem ignorar)\n");
        });

        buildBars();
        applyThemeColors();

        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(fontSp);
        gutter.setTextSize(fontSp);
        editor.setLineSpacing(4f, 1.15f);
        gutter.setLineSpacing(4f, 1.15f);
        try { editor.setWrapEnabled(wrapEnabled); } catch (Exception ignored) {}

        editor.setListener(new CodeEditText.Listener() {
            @Override public void onScrolled(int y) { gutter.scrollTo(0, y); }
            @Override public void onCursor(int line, int col) { curLine = line; curCol = col; updateStatus(); }
        });
        try {
            editor.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) hideSuggest();
            });
        } catch (Exception ignored) {}

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                if (!selfChange) history.push(s.toString(), editor.getSelectionStart(), false);
            }
            @Override public void onTextChanged(CharSequence s, int st, int b, int count) {
                if (selfChange || count <= 0) return;
                String added = s.subSequence(st, st + count).toString();
                if (added.contains("\n")) {
                    final int at = st + count;
                    handler.post(() -> autoIndent(at));
                }
            }
            @Override public void afterTextChanged(Editable s) {
                if (selfChange) return;
                clearFindSpans();
                scheduleHighlight();
                updateStatus();
                handler.post(() -> maybeSuggest());
            }
        });

        if (state != null) {
            ArrayList<String> titles = state.getStringArrayList("titles");
            ArrayList<String> uris = state.getStringArrayList("uris");
            ArrayList<String> texts = state.getStringArrayList("texts");
            if (titles != null) {
                for (int i = 0; i < titles.size(); i++) {
                    Doc d = new Doc(titles.get(i), uris.get(i), texts.get(i));
                    d.cached = texts.get(i);
                    docs.add(d);
                }
                switchDoc(Math.min(state.getInt("cur", 0), docs.size() - 1), true);
            }
        }
        if (docs.isEmpty()) {
            // projeto via Welcome (diretório) tem prioridade
            boolean loadedProject = false;
            try {
                String pu = getIntent().getStringExtra(ProjectStore.EXTRA_PROJECT_URI);
                if (pu == null) {
                    Uri cur = ProjectStore.currentUri(this);
                    if (cur != null) pu = cur.toString();
                }
                if (pu != null) {
                    projectTree = Uri.parse(pu);
                    projectName = getIntent().getStringExtra(ProjectStore.EXTRA_PROJECT_NAME);
                    if (projectName == null) projectName = ProjectStore.currentName(this);
                    if (projectName == null) projectName = "";
                    loadedProject = loadProjectTree();
                }
            } catch (Exception e) {
                android.util.Log.w("SalsichaCode", "projeto falhou", e);
            }
            if (!loadedProject) {
                if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null) {
                    openDoc(getIntent().getData());
                } else {
                    newDoc();
                    docs.get(0).cached = SAMPLE;
                    docs.get(0).savedText = SAMPLE;
                    switchDoc(0, true);
                }
            }
        }
        refreshTabs(); scheduleHighlight(); updateStatus();
    }

    /** Carrega todos os .java do diretório do projeto. true se abriu algo. */
    private boolean loadProjectTree() {
        if (projectTree == null) return false;
        try {
            androidx.documentfile.provider.DocumentFile dir =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(this, projectTree);
            if (dir == null || !dir.exists()) return false;
            if (projectName == null || projectName.isEmpty()) {
                projectName = dir.getName() != null ? dir.getName() : "Projeto";
            }
            androidx.documentfile.provider.DocumentFile[] files = dir.listFiles();
            List<androidx.documentfile.provider.DocumentFile> javas = new ArrayList<>();
            for (androidx.documentfile.provider.DocumentFile f : files) {
                try {
                    if (f != null && f.isFile() && f.getName() != null && f.getName().endsWith(".java")) {
                        javas.add(f);
                    }
                } catch (Exception ignored) {}
            }
            javas.sort((a, b) -> {
                try { return a.getName().compareToIgnoreCase(b.getName()); }
                catch (Exception e) { return 0; }
            });
            docs.clear();
            for (androidx.documentfile.provider.DocumentFile f : javas) {
                try (java.io.InputStream in = getContentResolver().openInputStream(f.getUri())) {
                    String text = readAll(in);
                    Doc d = new Doc(f.getName(), f.getUri().toString(), text);
                    d.cached = text;
                    docs.add(d);
                } catch (Exception e) {
                    android.util.Log.w("SalsichaCode", "ler " + f.getName(), e);
                }
            }
            if (docs.isEmpty()) {
                // projeto vazio: cria Main.java na hora
                try {
                    androidx.documentfile.provider.DocumentFile f = dir.createFile("text/x-java", "Main.java");
                    if (f != null) {
                        try (java.io.OutputStream o = getContentResolver().openOutputStream(f.getUri(), "w")) {
                            o.write(SAMPLE.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                        Doc d = new Doc("Main.java", f.getUri().toString(), SAMPLE);
                        d.cached = SAMPLE;
                        docs.add(d);
                    }
                } catch (Exception ignored) {}
            }
            if (docs.isEmpty()) return false;
            // abre Main.java primeiro, se existir
            int mainIdx = 0;
            for (int i = 0; i < docs.size(); i++) {
                if ("Main.java".equals(docs.get(i).title)) { mainIdx = i; break; }
            }
            switchDoc(mainIdx, true);
            try { ProjectStore.setCurrent(this, projectTree, projectName); } catch (Exception ignored) {}
            return true;
        } catch (Exception e) {
            android.util.Log.w("SalsichaCode", "loadProject", e);
            return false;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try {
            stashCurrent();
            ArrayList<String> titles = new ArrayList<>(), uris = new ArrayList<>(), texts = new ArrayList<>();
            for (Doc d : docs) {
                String t = currentTextOf(d);
                if (t != null && t.length() > 300_000) t = t.substring(0, 300_000);
                titles.add(d.title); uris.add(d.uri); texts.add(t);
                if (texts.size() >= 10) break;
            }
            out.putStringArrayList("titles", titles);
            out.putStringArrayList("uris", uris);
            out.putStringArrayList("texts", texts);
            out.putInt("cur", current);
        } catch (Exception e) {
            android.util.Log.w("SalsichaCode", "saveState falhou", e);
        }
    }

    // ---------- construção das barras ----------

    private void buildBars() {
        addBarBtn(toolbar, "📁", v -> {
            stashCurrent();
            try { ProjectStore.setCurrent(this, projectTree, projectName); } catch (Exception ignored) {}
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
        });
        addBarBtn(toolbar, "＋", v -> { stashCurrent(); newDoc(); if (projectTree == null) switchDoc(docs.size() - 1, true); });
        addBarBtn(toolbar, "📂 Abrir", v -> openLauncher.launch(new String[]{"text/*", "application/*"}));
        addBarBtn(toolbar, "💾 Salvar", v -> saveCurrent(false));
        addBarBtn(toolbar, "⎘ Como…", v -> saveCurrent(true));
        addBarBtn(toolbar, "↩", v -> doUndo());
        addBarBtn(toolbar, "↪", v -> doRedo());
        addBarBtn(toolbar, "🔍 Buscar", v -> showFind());
        addBarBtn(toolbar, "✓ Fx", v -> showProblems());
        addBarBtn(toolbar, wrapEnabled ? "↔ Quebra:ON" : "↔ IDE", v -> {
            wrapEnabled = !wrapEnabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("wrap", wrapEnabled).apply();
            try { editor.setWrapEnabled(wrapEnabled); } catch (Exception ignored) {}
            try { ((Button) v).setText(wrapEnabled ? "↔ Quebra:ON" : "↔ IDE"); } catch (Exception ignored) {}
            toast(wrapEnabled ? "Quebra de linha ligada" : "Modo IDE: rolagem horizontal");
        });
        addBarBtn(toolbar, "A−", v -> setFont(fontSp - 1));
        addBarBtn(toolbar, "A＋", v -> setFont(fontSp + 1));
        addBarBtn(toolbar, dark ? "☀ Claro" : "🌙 Escuro", v -> toggleTheme());
        addBarBtn(toolbar, "🕘", v -> showRecents());
        addBarBtn(toolbar, "✕ Aba", v -> closeCurrent());

        String[] syms = {"⇥", "{", "}", "(", ")", "[", "]", ";", "=", "\"", "'", "<", ">", "/", "\\",
                "+", "-", "*", ":", "!", ".", ",", "_", "@", "#", "$", "?", "|", "&"};
        for (String s : syms) {
            addSymbolBtn(symbolbar, s, v -> {
                if (s.equals("⇥")) insertAtCursor("    ");
                else insertAtCursor(s);
            });
        }
    }

    private void addSymbolBtn(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this, null, 0, R.style.SymbolButton);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(colors().text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (4 * getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        parent.addView(b, lp);
    }

    private void addBarBtn(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this, null, 0, R.style.BarButton);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(colors().text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (3 * getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        parent.addView(b, lp);
    }

    // ---------- documentos / abas ----------

    private void newDoc() {
        // em modo projeto, cria o arquivo direto na pasta (IntelliJ-like)
        if (projectTree != null) {
            askNewFileInProject();
            return;
        }
        untitledCount++;
        String name = (untitledCount == 1 && docs.isEmpty())
                ? "Main.java" : "SemNome" + untitledCount + ".java";
        docs.add(new Doc(name, null, ""));
        history.clear();
    }

    private void askNewFileInProject() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(this);
        name.setHint("Nome.java");
        name.setTextColor(colors().text);
        name.setHintTextColor(colors().gutterText);
        name.setTypeface(Typeface.MONOSPACE);
        box.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("Novo arquivo em " + (projectName != null ? projectName : "projeto"))
                .setView(box)
                .setPositiveButton("Criar", (a, b) -> {
                    String n = name.getText().toString().trim();
                    if (!n.endsWith(".java")) n += ".java";
                    if (!n.matches("[A-Za-z_$][A-Za-z0-9_$]*\\.java")) {
                        toast("Nome inválido");
                        return;
                    }
                    try {
                        androidx.documentfile.provider.DocumentFile dir =
                                androidx.documentfile.provider.DocumentFile.fromTreeUri(this, projectTree);
                        if (dir == null) { toast("Projeto inacessível"); return; }
                        if (dir.findFile(n) != null) { toast("Já existe"); return; }
                        androidx.documentfile.provider.DocumentFile f = dir.createFile("text/x-java", n);
                        if (f == null) { toast("Não criou"); return; }
                        String base = "public class " + n.substring(0, n.length() - 5) + " {\n}\n";
                        try (java.io.OutputStream o = getContentResolver().openOutputStream(f.getUri(), "w")) {
                            o.write(base.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                        stashCurrent();
                        Doc d = new Doc(n, f.getUri().toString(), base);
                        d.cached = base;
                        docs.add(d);
                        history.clear();
                        switchDoc(docs.size() - 1, true);
                    } catch (Exception e) {
                        toast("Erro: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private String currentTextOf(Doc d) {
        int idx = docs.indexOf(d);
        if (idx == current) return editor.getText().toString();
        return d.cached != null ? d.cached : "";
    }

    private void stashCurrent() {
        if (current < 0 || current >= docs.size()) return;
        Doc d = docs.get(current);
        d.cached = editor.getText().toString();
        d.sel = Math.max(editor.getSelectionStart(), 0);
    }

    private void switchDoc(int idx, boolean resetHistory) {
        try { hideSuggest(); } catch (Exception ignored) {}
        stashCurrent();
        current = idx;
        Doc d = docs.get(idx);
        String text = d.cached != null ? d.cached : readText(d);
        d.cached = text;
        if (resetHistory) history.clear();
        setEditorText(text, Math.min(d.sel, text.length()));
        refreshTabs(); updateStatus();
    }

    private String readText(Doc d) {
        if (d.uri == null) return d.savedText != null ? d.savedText : "";
        try (InputStream in = getContentResolver().openInputStream(Uri.parse(d.uri))) {
            return readAll(in);
        } catch (Exception e) {
            toast("Erro ao ler: " + e.getMessage());
            return "";
        }
    }

    private void openDoc(Uri uri) {
        String text;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            text = readAll(in);
        } catch (Exception e) {
            toast("Erro ao abrir: " + e.getMessage());
            return;
        }
        stashCurrent();
        Doc d = new Doc(displayName(this, uri), uri.toString(), text);
        d.cached = text;
        docs.add(d);
        pushRecent(d);
        switchDoc(docs.size() - 1, true);
    }

    private void closeCurrent() {
        if (docs.isEmpty()) return;
        Doc d = docs.get(current);
        if (isDirty(d)) {
            new AlertDialog.Builder(this)
                    .setTitle("Fechar sem salvar?")
                    .setMessage(d.title + " tem alterações.")
                    .setPositiveButton("Salvar", (a, b) -> { saveCurrent(false); removeDoc(d); })
                    .setNegativeButton("Descartar", (a, b) -> removeDoc(d))
                    .setNeutralButton("Cancelar", null)
                    .show();
        } else removeDoc(d);
    }

    private void removeDoc(Doc d) {
        int idx = docs.indexOf(d);
        docs.remove(idx);
        if (docs.isEmpty()) { newDoc(); switchDoc(0, true); return; }
        if (current >= docs.size()) current = docs.size() - 1;
        else if (idx < current) current--;
        Doc n = docs.get(Math.min(idx, docs.size() - 1));
        current = docs.indexOf(n);
        String text = n.cached != null ? n.cached : readText(n);
        n.cached = text;
        history.clear();
        setEditorText(text, Math.min(n.sel, text.length()));
        refreshTabs(); updateStatus();
    }

    private boolean isDirty(Doc d) {
        String cur = (docs.indexOf(d) == current) ? editor.getText().toString()
                : (d.cached != null ? d.cached : "");
        String base = d.savedText != null ? d.savedText : "";
        return !cur.equals(base);
    }

    private void refreshTabs() {
        tabstrip.removeAllViews();
        for (int i = 0; i < docs.size(); i++) {
            final int idx = i;
            Doc d = docs.get(i);
            Button b = new Button(this, null, 0, R.style.TabButton);
            String label = (i == current ? "● " : "") + d.title + (isDirty(d) ? " •" : "");
            b.setText(label);
            b.setAllCaps(false);
            b.setTextColor(colors().text);
            b.setAlpha(i == current ? 1f : 0.6f);
            try {
                b.setBackgroundResource(i == current
                        ? R.drawable.bg_tab_active : R.drawable.bg_tab);
            } catch (Exception ignored) {}
            b.setOnClickListener(v -> { if (idx != current) switchDoc(idx, true); });
            b.setOnLongClickListener(v -> { toast("Use ✕ Aba p/ fechar"); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            int m = (int) (3 * getResources().getDisplayMetrics().density);
            lp.setMargins(m, m, m, m);
            tabstrip.addView(b, lp);
        }
    }

    private void tintButtons(LinearLayout parent, int color) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View v = parent.getChildAt(i);
            if (v instanceof android.widget.TextView) ((android.widget.TextView) v).setTextColor(color);
        }
    }

    // ---------- salvar ----------

    private void saveCurrent(boolean saveAs) {
        if (current < 0) return;
        Doc d = docs.get(current);
        String text = editor.getText().toString();
        if (d.uri == null || saveAs) {
            pendingSaveDoc = current;
            createLauncher.launch(d.title);
            return;
        }
        writeDoc(d, text);
        refreshTabs(); updateStatus();
    }

    private void writeDoc(Doc d, String text) {
        try (OutputStream out = getContentResolver().openOutputStream(Uri.parse(d.uri), "w")) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            d.savedText = text;
            d.cached = text;
            toast("Salvo ✓ " + d.title);
        } catch (Exception e) {
            toast("Erro ao salvar: " + e.getMessage());
        }
    }

    // ---------- edição ----------

    private void setEditorText(String text, int sel) {
        selfChange = true;
        editor.setText(text);
        try { editor.setSelection(Math.min(sel, text.length())); } catch (Exception ignored) {}
        selfChange = false;
        scheduleHighlight();
    }

    private void insertAtCursor(String s) {
        int st = Math.max(editor.getSelectionStart(), 0);
        int en = Math.max(editor.getSelectionEnd(), 0);
        history.push(editor.getText().toString(), st, false);
        selfChange = true;
        editor.getText().replace(Math.min(st, en), Math.max(st, en), s);
        selfChange = false;
        try {
            editor.setSelection(Math.min(st, en) + s.length());
        } catch (Exception ignored) {}
        scheduleHighlight(); updateStatus();
    }

    private void autoIndent(int at) {
        Editable e = editor.getText();
        if (at <= 0 || at > e.length()) return;
        int lineStart = at - 1;
        while (lineStart > 0 && e.charAt(lineStart - 1) != '\n') lineStart--;
        // acha fim da linha anterior (antes do \n inserido)
        int prevEnd = at - 1;
        int prevStart = prevEnd;
        while (prevStart > 0 && e.charAt(prevStart - 1) != '\n') prevStart--;
        String prev = e.subSequence(prevStart, prevEnd).toString();
        StringBuilder ind = new StringBuilder();
        for (char c : prev.toCharArray()) {
            if (c == ' ' || c == '\t') ind.append(c); else break;
        }
        String t = prev.trim();
        if (t.endsWith("{") || t.endsWith("(") || t.endsWith("[")) ind.append("    ");
        if (ind.length() == 0) return;
        selfChange = true;
        e.insert(at, ind.toString());
        selfChange = false;
    }

    private void doUndo() {
        EditHistory.State s = history.undo(editor.getText().toString(), editor.getSelectionStart());
        if (s == null) { toast("Nada a desfazer"); return; }
        setEditorText(s.text, Math.min(s.cursor, s.text.length()));
        updateStatus();
    }

    private void doRedo() {
        EditHistory.State s = history.redo(editor.getText().toString(), editor.getSelectionStart());
        if (s == null) { toast("Nada a refazer"); return; }
        setEditorText(s.text, Math.min(s.cursor, s.text.length()));
        updateStatus();
    }

    // ---------- highlight / régua / status ----------

    private JavaHighlighter.Colors colors() {
        return dark ? JavaHighlighter.DARK : JavaHighlighter.LIGHT;
    }

    private void scheduleHighlight() {
        if (highlightTask != null) handler.removeCallbacks(highlightTask);
        highlightTask = () -> {
            Editable e;
            String txt;
            try {
                e = editor.getText();
                txt = e.toString();
            } catch (Exception ex) { return; }
            JavaHighlighter.highlight(e, colors());
            List<CodeDiagnostics.Issue> issues;
            try {
                issues = CodeDiagnostics.analyze(txt);
            } catch (Exception ex) {
                issues = new ArrayList<>();
            }
            synchronized (diagnostics) {
                diagnostics.clear();
                diagnostics.addAll(issues);
            }
            applyDiagnosticSpans(e, issues);
            reapplyFindSpans(e);
            buildGutter(txt, issues);
            updateTabsDirty();
            updateStatus();
        };
        handler.postDelayed(highlightTask, 350);
    }

    private void applyDiagnosticSpans(Editable e, List<CodeDiagnostics.Issue> issues) {
        try {
            for (android.text.style.UnderlineSpan s : e.getSpans(0, e.length(), android.text.style.UnderlineSpan.class)) {
                e.removeSpan(s);
            }
            for (Object s : e.getSpans(0, e.length(), android.text.style.BackgroundColorSpan.class)) {
                // mantém busca? Busca usa BackgroundColorSpan — remove só os nossos de erro
                // marca erro com tag via Span personalizado abaixo, então aqui remove todos de erro anterior
                // identificados por cor vermelha/amarela
                if (s instanceof android.text.style.BackgroundColorSpan) {
                    android.text.style.BackgroundColorSpan b = (android.text.style.BackgroundColorSpan) s;
                    // não há getter de cor, então remove todos e recria busca depois — busca será reagendada
                    // para simplificar: remove apenas se dentro de um issue (heurística: remove todos)
                    e.removeSpan(s);
                }
            }
        } catch (Exception ignored) {}
        if (issues == null || issues.isEmpty()) return;
        for (CodeDiagnostics.Issue it : issues) {
            try {
                int s = Math.max(0, Math.min(it.start, e.length()));
                int en = Math.max(0, Math.min(it.end, e.length()));
                if (en <= s) continue;
                if (s >= e.length()) continue;
                if (en > e.length()) en = e.length();
                if (en - s > 500) en = s + 500;
                int col = it.kind == CodeDiagnostics.Kind.ERROR ? 0x55FF6B6B : 0x55E8A33D;
                e.setSpan(new android.text.style.BackgroundColorSpan(col),
                        s, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception ignored) {}
        }
    }

    private void reapplyFindSpans(Editable e) {
        try {
            if (lastQuery == null || lastQuery.isEmpty() || matches.isEmpty()) return;
            for (int[] m : matches) {
                int s = Math.max(0, Math.min(m[0], e.length()));
                int en = Math.max(0, Math.min(m[1], e.length()));
                if (en <= s) continue;
                e.setSpan(new BackgroundColorSpan(dark ? 0x665E7A00 : 0x66E8A33D),
                        s, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } catch (Exception ignored) {}
    }

    private void updateTabsDirty() {
        for (int i = 0; i < tabstrip.getChildCount() && i < docs.size(); i++) {
            View v = tabstrip.getChildAt(i);
            if (!(v instanceof Button)) continue;
            Button b = (Button) v;
            Doc d = docs.get(i);
            String label = (i == current ? "● " : "") + d.title + (isDirty(d) ? " •" : "");
            b.setText(label);
        }
    }

    private void buildGutter(String text, List<CodeDiagnostics.Issue> issues) {
        int lines = 1;
        int len = Math.min(text.length(), 200_000);
        for (int i = 0; i < len; i++) if (text.charAt(i) == '\n') lines++;
        if (lines > 5000) {
            gutter.setText(lines + " linhas");
            return;
        }
        java.util.Set<Integer> errLines = new java.util.HashSet<>();
        java.util.Set<Integer> warnLines = new java.util.HashSet<>();
        if (issues != null) {
            for (CodeDiagnostics.Issue it : issues) {
                if (it.kind == CodeDiagnostics.Kind.ERROR) errLines.add(it.line);
                else warnLines.add(it.line);
            }
        }
        StringBuilder sb = new StringBuilder(lines * 4);
        for (int i = 1; i <= lines; i++) {
            if (errLines.contains(i)) sb.append("● ").append(i).append('\n');
            else if (warnLines.contains(i)) sb.append("▲ ").append(i).append('\n');
            else sb.append(i).append('\n');
        }
        try {
            gutter.setText(sb.toString());
            gutter.setTypeface(Typeface.MONOSPACE);
            if (!errLines.isEmpty()) gutter.setTextColor(0xFFFF8A8A);
            else gutter.setTextColor(colors().gutterText);
        } catch (Exception ignored) {}
    }

    private void buildGutter(String text) {
        List<CodeDiagnostics.Issue> cur;
        synchronized (diagnostics) { cur = new ArrayList<>(diagnostics); }
        buildGutter(text, cur);
    }

    private void updateStatus() {
        if (current < 0 || current >= docs.size()) { status.setText("—"); return; }
        Doc d = docs.get(current);
        int chars = 0;
        try { chars = editor.getText().length(); } catch (Exception ignored) {}
        String dirty = isDirty(d) ? " • não salvo" : " • salvo";
        int errs = 0, warns = 0;
        synchronized (diagnostics) {
            for (CodeDiagnostics.Issue it : diagnostics) {
                if (it.kind == CodeDiagnostics.Kind.ERROR) errs++;
                else warns++;
            }
        }
        String diag = "";
        if (errs > 0 || warns > 0) diag = "  •  ❌" + errs + " ⚠️" + warns;
        String wrap = wrapEnabled ? "  •  quebra:ON" : "  •  IDE";
        status.setText("L" + curLine + ":C" + curCol + "  •  " + chars + " chars  •  "
                + d.title + dirty + diag + wrap);
        try {
            if (appSubtitle != null) {
                String proj = (projectName != null && !projectName.isEmpty())
                        ? "📁 " + projectName + " • " : "";
                appSubtitle.setText(proj + docs.size() + " arquivo(s) • " + d.title + diag);
            }
        } catch (Exception ignored) {}
    }

    private void applyThemeColors() {
        JavaHighlighter.Colors c = colors();
        try { findViewById(R.id.root).setBackgroundColor(c.background); } catch (Exception ignored) {}
        try { toolbar.setBackgroundColor(c.background); } catch (Exception ignored) {}
        try { tabstrip.setBackgroundColor(c.background); } catch (Exception ignored) {}
        try { symbolbar.setBackgroundColor(c.background); } catch (Exception ignored) {}
        try {
            View appbar = findViewById(R.id.appbar);
            if (appbar != null) appbar.setBackgroundColor(c.background);
        } catch (Exception ignored) {}
        try {
            if (editorCard != null) {
                editorCard.setBackgroundResource(dark
                        ? R.drawable.bg_editor_card : R.drawable.bg_editor_card);
            }
        } catch (Exception ignored) {}
        tintButtons(toolbar, c.text);
        tintButtons(tabstrip, c.text);
        tintButtons(symbolbar, c.text);
        try {
            if (suggestBar != null) tintButtons(suggestBar, c.text);
            else {
                View sb = findViewById(R.id.suggestBar);
                if (sb instanceof LinearLayout) tintButtons((LinearLayout) sb, c.text);
            }
        } catch (Exception ignored) {}
        try {
            editor.setTextColor(c.text);
            editor.setBackgroundColor(0x00000000);
            editor.setHintTextColor(c.gutterText);
            gutter.setTextColor(c.gutterText);
            gutter.setBackgroundColor(0x00000000);
            status.setTextColor(c.gutterText);
            consoleView.setTextColor(c.text);
            consoleTitle.setTextColor(c.gutterText);
            consoleBox.setBackgroundResource(R.drawable.bg_console_card);
        } catch (Exception ignored) {}
        try {
            TextView title = findViewById(R.id.apptitle);
            if (title != null) title.setTextColor(c.text);
            if (appSubtitle != null) appSubtitle.setTextColor(c.gutterText);
        } catch (Exception ignored) {}
        scheduleHighlight();
    }

    private void toggleTheme() {        dark = !dark;
        getPreferences(MODE_PRIVATE).edit().putBoolean("dark", dark).apply();
        AppCompatDelegate.setDefaultNightMode(
                dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        recreate();
    }

    private void setFont(float v) {
        fontSp = Math.max(10f, Math.min(28f, v));
        getPreferences(MODE_PRIVATE).edit().putFloat("font", fontSp).apply();
        editor.setTextSize(fontSp);
        gutter.setTextSize(fontSp);
    }

    // ---------- buscar / substituir ----------

    private void showFind() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText q = new EditText(this);
        q.setHint("Buscar…");
        q.setTextColor(colors().text);
        q.setHintTextColor(colors().gutterText);
        if (!lastQuery.isEmpty()) q.setText(lastQuery);
        final EditText r = new EditText(this);
        r.setHint("Substituir por…");
        r.setTextColor(colors().text);
        r.setHintTextColor(colors().gutterText);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        try {
            row.setGravity(android.view.Gravity.CENTER);
        } catch (Exception ignored) {}
        addBarBtn(row, "◀", v -> { lastQuery = q.getText().toString(); findAll(); stepMatch(-1); });
        addBarBtn(row, "▶", v -> { lastQuery = q.getText().toString(); findAll(); stepMatch(1); });
        addBarBtn(row, "Trocar", v -> replaceOne(r.getText().toString()));
        addBarBtn(row, "Tudo", v -> replaceAll(q.getText().toString(), r.getText().toString()));
        box.addView(q); box.addView(r); box.addView(row);
        new AlertDialog.Builder(this)
                .setTitle("Buscar no arquivo")
                .setView(box)
                .setNegativeButton("Fechar", null)
                .show();
        if (!q.getText().toString().isEmpty()) {
            lastQuery = q.getText().toString();
            findAll();
        }
    }

    private void replaceOne(String rep) {
        if (matchIndex < 0 || matchIndex >= matches.size()) { toast("Busque primeiro"); return; }
        int[] m = matches.get(matchIndex);
        history.push(editor.getText().toString(), m[0], true);
        selfChange = true;
        editor.getText().replace(m[0], m[1], rep);
        selfChange = false;
        editor.setSelection(m[0] + rep.length());
        findAll(); scheduleHighlight(); updateStatus();
    }

    private void replaceAll(String query, String rep) {
        if (query.isEmpty()) return;
        history.push(editor.getText().toString(), editor.getSelectionStart(), true);
        selfChange = true;
        String done = editor.getText().toString().replace(query, rep);
        editor.setText(done);
        selfChange = false;
        lastQuery = "";
        matches.clear(); matchIndex = -1;
        scheduleHighlight(); updateStatus();
        toast("Substituído ✓");
    }

    private void findAll() {
        clearFindSpans();
        matches.clear(); matchIndex = -1;
        if (lastQuery.isEmpty()) return;
        String text = editor.getText().toString();
        int idx = 0;
        while ((idx = text.indexOf(lastQuery, idx)) >= 0 && matches.size() < 500) {
            matches.add(new int[]{idx, idx + lastQuery.length()});
            idx += lastQuery.length();
        }
        Editable e = editor.getText();
        for (int[] m : matches) {
            e.setSpan(new BackgroundColorSpan(dark ? 0x665E7A00 : 0x66E8A33D),
                    m[0], m[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        toast(matches.size() + " ocorrência(s)");
    }

    private void stepMatch(int dir) {
        if (matches.isEmpty()) return;
        matchIndex = (matchIndex + dir + matches.size()) % matches.size();
        int[] m = matches.get(matchIndex);
        editor.requestFocus();
        try {
            editor.setSelection(m[0], m[1]);
        } catch (Exception ignored) { return; }
        try {
            if (editor.getLayout() == null) return;
            int line = editor.getLayout().getLineForOffset(Math.min(m[0], editor.length()));
            int y = editor.getLayout().getLineTop(line) - editor.getHeight() / 3;
            editor.scrollTo(0, Math.max(y, 0));
        } catch (Exception ignored) {}
    }

    private void clearFindSpans() {
        Editable e = editor.getText();
        for (BackgroundColorSpan s : e.getSpans(0, e.length(), BackgroundColorSpan.class)) e.removeSpan(s);
    }

    // ---------- problemas / recentes ----------

    private void showProblems() {
        List<CodeDiagnostics.Issue> cur;
        synchronized (diagnostics) { cur = new ArrayList<>(diagnostics); }
        // fallback imediato se ainda não analisou
        if (cur.isEmpty()) {
            try {
                cur = CodeDiagnostics.analyze(editor.getText().toString());
            } catch (Exception ignored) {}
        }
        if (cur.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Problemas ✓")
                    .setMessage("Nenhum erro ou warning. Pode dar Run! 🌭")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final List<CodeDiagnostics.Issue> items = cur;
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).toString();
        new AlertDialog.Builder(this).setTitle("Problemas (" + items.size() + ") — toque p/ ir")
                .setItems(labels, (a, which) -> {
                    if (which < 0 || which >= items.size()) return;
                    gotoIssue(items.get(which));
                })
                .setPositiveButton("Fechar", null).show();
    }

    private void gotoIssue(CodeDiagnostics.Issue it) {
        try {
            String txt = editor.getText().toString();
            String[] lines = txt.split("\n", -1);
            int off = 0;
            for (int i = 0; i < Math.min(it.line - 1, lines.length); i++) off += lines[i].length() + 1;
            int target = Math.max(0, Math.min(off, txt.length()));
            // se temos offset preciso, usa ele
            if (it.start >= 0 && it.start < txt.length()) target = it.start;
            editor.requestFocus();
            try { editor.setSelection(target, Math.min(it.end, txt.length())); } catch (Exception ignored) {
                try { editor.setSelection(target); } catch (Exception ignored2) {}
            }
            try {
                if (editor.getLayout() != null) {
                    int lineIdx = editor.getLayout().getLineForOffset(Math.min(target, editor.length()));
                    int y = editor.getLayout().getLineTop(lineIdx) - editor.getHeight() / 2;
                    editor.scrollTo(0, Math.max(y, 0));
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    // ---------- autocomplete ----------

    private void maybeSuggest() {
        try {
            if (editor == null || editor.getText() == null) return;
            // nunca rouba foco/teclado: só sugere com editor focado
            try { if (!editor.hasFocus() || !editor.hasWindowFocus()) { hideSuggest(); return; } } catch (Exception ignored) {}
            int cur = editor.getSelectionStart();
            if (cur < 0) { hideSuggest(); return; }
            // só se está digitando (seleção colapsada), não ao selecionar texto
            try { if (editor.getSelectionEnd() != cur) { hideSuggest(); return; } } catch (Exception ignored) {}
            String prefix = JavaSuggest.currentPrefix(editor.getText(), cur);
            if (prefix.isEmpty()) { hideSuggest(); return; }
            List<String> items = JavaSuggest.suggest(prefix, editor.getText().toString());
            if (items.isEmpty()) { hideSuggest(); return; }
            showSuggest(items, prefix);
        } catch (Exception ignored) { hideSuggest(); }
    }

    private void showSuggest(List<String> items, String prefix) {
        try {
            if (suggestBar == null) suggestBar = findViewById(R.id.suggestBar);
            if (suggestBarScroll == null) suggestBarScroll = findViewById(R.id.suggestBarScroll);
            if (suggestBar == null || suggestBarScroll == null) return;
            suggestBar.removeAllViews();
            int shown = 0;
            for (String item : items) {
                if (shown >= 8) break;
                final String picked = item;
                Button b = new Button(this, null, 0, R.style.BarButton);
                b.setText(picked);
                b.setAllCaps(false);
                b.setTextColor(colors().text);
                // não rouba foco do editor / teclado
                try {
                    b.setFocusable(false);
                    b.setFocusableInTouchMode(false);
                } catch (Exception ignored) {}
                b.setOnClickListener(v -> {
                    try { applySuggestion(prefix, picked); } catch (Exception ignored) {}
                    hideSuggest();
                    try { editor.requestFocus(); } catch (Exception ignored) {}
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                int m = (int) (3 * getResources().getDisplayMetrics().density);
                lp.setMargins(m, m, m, m);
                suggestBar.addView(b, lp);
                shown++;
            }
            // botão ✕ fecha sem perder teclado
            Button x = new Button(this, null, 0, R.style.BarButton);
            x.setText("✕");
            x.setAllCaps(false);
            try { x.setFocusable(false); x.setFocusableInTouchMode(false); } catch (Exception ignored) {}
            x.setOnClickListener(v -> hideSuggest());
            suggestBar.addView(x);
            suggestBarScroll.setVisibility(View.VISIBLE);
            suggestBarScroll.post(() -> {
                try { suggestBarScroll.smoothScrollTo(0, 0); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private void applySuggestion(String prefix, String picked) {
        try {
            int cur = editor.getSelectionStart();
            int start = Math.max(0, cur - prefix.length());
            history.push(editor.getText().toString(), start, false);
            selfChange = true;
            // se for snippet com '(' , insere e posiciona dentro do parêntese
            editor.getText().replace(start, cur, picked);
            int newCur = start + picked.length();
            // auto fecha parêntese se snippet abre e não fecha
            if (picked.endsWith("(") && (newCur >= editor.length() || editor.getText().charAt(newCur) != ')')) {
                editor.getText().insert(newCur, ")");
            }
            selfChange = false;
            try { editor.setSelection(newCur); } catch (Exception ignored) {}
            scheduleHighlight();
        } catch (Exception ignored) { selfChange = false; }
    }

    private void hideSuggest() {
        try {
            if (suggestBarScroll != null && suggestBarScroll.getVisibility() == View.VISIBLE) {
                suggestBarScroll.setVisibility(View.GONE);
            } else {
                View s = findViewById(R.id.suggestBarScroll);
                if (s != null) s.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {}
    }

    private void pushRecent(Doc d) {
        if (d.uri == null) return;
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        Set<String> set = new LinkedHashSet<>(p.getStringSet("recents", new LinkedHashSet<>()));
        set.removeIf(s -> s.endsWith("|" + d.uri));
        set.add(d.title + "|" + d.uri);
        while (set.size() > 10) set.remove(set.iterator().next());
        p.edit().putStringSet("recents", set).apply();
    }

    private void showRecents() {
        Set<String> set = getPreferences(MODE_PRIVATE).getStringSet("recents", new LinkedHashSet<>());
        if (set.isEmpty()) { toast("Nenhum recente"); return; }
        final String[] items = set.toArray(new String[0]);
        String[] labels = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            String[] p = items[i].split("\\|", 2);
            labels[i] = p.length > 0 ? p[0] : items[i];
        }
        new AlertDialog.Builder(this).setTitle("Recentes")
                .setItems(labels, (a, which) -> {
                    if (which < 0 || which >= items.length) return;
                    String[] parts = items[which].split("\\|", 2);
                    if (parts.length == 2) openDoc(Uri.parse(parts[1]));
                }).show();
    }

    // ---------- run (v2) ----------

    private void doRun() {
        android.util.Log.i("SalsichaCode", "doRun chamado");
        // Carga preguiçosa: só aqui encostamos nas classes do compilador.
        try {
            if (runMgr == null) runMgr = new RunManager();
            android.util.Log.i("SalsichaCode", "RunManager ok");
        } catch (Throwable t) {
            consoleBox.setVisibility(View.VISIBLE);
            consolePrint("\n❌ Compilador indisponível neste aparelho:\n" + t + "\n");
            android.util.Log.e("SalsichaCode", "falha ao carregar RunManager", t);
            runMgr = null;
            return;
        }
        if (runMgr.isRunning()) { toast("Já rodando — ⏹ p/ parar"); return; }
        if (current < 0) return;
        stashCurrent();
        Doc main = docs.get(current);
        if (main.title == null || !main.title.endsWith(".java")) {
            toast("A aba atual precisa ser um .java");
            return;
        }
        String mainClass = main.title.substring(0, main.title.length() - 5);
        if (!mainClass.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            toast("Nome de classe inválido: " + main.title);
            return;
        }
        // v2.3: o .java precisa ter o mesmo nome da classe pública — ajusta sozinho.
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile(
                "public\\s+(?:final\\s+|abstract\\s+)?class\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(currentTextOf(main));
        if (pm.find() && !pm.group(1).equals(mainClass)) {
            main.title = pm.group(1) + ".java";
            mainClass = pm.group(1);
            refreshTabs(); updateStatus();
            toast("Aba renomeada p/ " + main.title);
        }
        java.util.Map<String, String> sources = new java.util.LinkedHashMap<>();
        for (Doc d : docs) {
            if (d.title != null && d.title.endsWith(".java")) {
                sources.put(d.title, currentTextOf(d));
            }
        }
        String allSrc = currentTextOf(main);
        boolean needsInput = allSrc.contains("Scanner")
                || allSrc.contains("System.in")
                || allSrc.contains("BufferedReader")
                || allSrc.contains("Console.read");
        final String finalMain = mainClass;
        // Interativo: não bloqueia em diálogo — o campo ⌨ do console alimenta o Scanner ao vivo
        String prefill = "";
        try { prefill = getPreferences(MODE_PRIVATE).getString("last_stdin", ""); } catch (Exception ignored) {}
        if (needsInput && (prefill == null || prefill.isEmpty())) {
            toast("Programa interativo: digite no campo ⌨ do console");
        }
        startRun(sources, finalMain, prefill != null ? prefill : "");
    }

    private void wireConsoleInput() {
        try {
            View send = findViewById(R.id.consoleSend);
            EditText inp = findViewById(R.id.consoleInput);
            if (send == null || inp == null) return;
            inp.setTextColor(colors().text);
            inp.setHintTextColor(colors().gutterText);
            View.OnClickListener doSend = v -> {
                String t = inp.getText().toString();
                if (t.isEmpty()) return;
                if (runMgr != null && runMgr.isRunning()) {
                    runMgr.sendInput(t);
                    consolePrint("> " + t + "\n");
                    inp.setText("");
                } else {
                    toast("Nada rodando — dê Run primeiro");
                }
            };
            send.setOnClickListener(doSend);
            inp.setOnEditorActionListener((tv, actionId, ev) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    doSend.onClick(tv);
                    return true;
                }
                return false;
            });
        } catch (Exception ignored) {}
    }

    private void showStdinDialog(String src, java.util.function.Consumer<String> cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView hint = new TextView(this);
        hint.setText("Esse código lê do teclado (Scanner/System.in).\nDigite a entrada abaixo, uma linha por valor:");
        hint.setTextColor(colors().gutterText);
        hint.setTextSize(13f);
        final EditText inp = new EditText(this);
        inp.setHint("ex:\nJoão\n25\n3.5");
        inp.setTextColor(colors().text);
        inp.setHintTextColor(colors().gutterText);
        inp.setTypeface(Typeface.MONOSPACE);
        inp.setMinLines(3);
        inp.setGravity(android.view.Gravity.TOP);
        // tenta recuperar última entrada usada
        try {
            String last = getPreferences(MODE_PRIVATE).getString("last_stdin", "");
            if (!last.isEmpty()) inp.setText(last);
        } catch (Exception ignored) {}
        box.addView(hint);
        box.addView(inp);
        new AlertDialog.Builder(this)
                .setTitle("⌨ Entrada do programa")
                .setView(box)
                .setPositiveButton("▶ Run com entrada", (a, b) -> {
                    String s = inp.getText().toString();
                    try { getPreferences(MODE_PRIVATE).edit().putString("last_stdin", s).apply(); } catch (Exception ignored) {}
                    cb.accept(s);
                })
                .setNeutralButton("Run sem entrada", (a, b) -> cb.accept(""))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void startRun(java.util.Map<String, String> sources, String mainClass, String stdin) {
        consoleBox.setVisibility(View.VISIBLE);
        // estilo IntelliJ: console limpo, só saída do programa
        try { consoleView.setText(""); } catch (Exception ignored) {}
        consoleTitle.setText("Console … run " + mainClass);
        try {
            View row = findViewById(R.id.consoleInputRow);
            if (row != null) row.setVisibility(View.VISIBLE);
            EditText ci = findViewById(R.id.consoleInput);
            if (ci != null) { ci.setEnabled(true); ci.setText(""); ci.setHint("⌨ programa rodando — digite aqui + Enviar…"); }
        } catch (Exception ignored) {}
        RunManager.Listener listener = new RunManager.Listener() {
            @Override public void onPrint(String chunk) { consolePrint(chunk); }
            @Override public void onDone(String summary, boolean success) {
                runOnUiThread(() -> {
                    // IntelliJ: linha em branco + resultado
                    if (!success) consolePrint("\n" + summary + "\n");
                    else consolePrint("\n" + summary + "\n");
                    consoleTitle.setText(success ? "Console ✓ " + mainClass : "Console ✗ " + mainClass);
                    try {
                        EditText ci2 = findViewById(R.id.consoleInput);
                        if (ci2 != null) { ci2.setEnabled(false); ci2.setHint("⌨ run finalizado"); }
                    } catch (Exception ignored) {}
                });
            }
        };
        try {
            runMgr.run(this, sources, mainClass, stdin, listener);
        } catch (Exception e) {
            runMgr.run(this, sources, mainClass, listener);
        }
    }

    private void consolePrint(String s) {
        if (s == null) return;
        runOnUiThread(() -> {
            try {
                // só auto-desce se já estava no fim — senão respeita quem está lendo acima
                boolean atBottom = true;
                try {
                    if (consoleScroll != null && consoleView != null) {
                        int diff = consoleView.getBottom() - (consoleScroll.getHeight() + consoleScroll.getScrollY());
                        atBottom = diff <= 40;
                    }
                } catch (Exception ignored) {}
                consoleView.append(s);
                if (atBottom && consoleScroll != null) {
                    consoleScroll.post(() -> {
                        try { consoleScroll.fullScroll(View.FOCUS_DOWN); } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onBackPressed() {
        try {
            View s = suggestBarScroll != null ? suggestBarScroll : findViewById(R.id.suggestBarScroll);
            if (s != null && s.getVisibility() == View.VISIBLE) {
                hideSuggest();
                return;
            }
        } catch (Exception ignored) {}
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try { if (runMgr != null) runMgr.stop(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------- util ----------

    private static String displayName(Context ctx, Uri uri) {
        if (uri == null) return "SemNome.java";
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            return p == null ? "SemNome.java" : p.substring(p.lastIndexOf('/') + 1);
        }
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        return "SemNome.java";
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // ---------- diagnóstico de tela preta ----------

    private void saveCrashToFile(Throwable t) {
        try {
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.US).format(new java.util.Date());
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String info = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " Android " + android.os.Build.VERSION.RELEASE
                    + " (SDK " + android.os.Build.VERSION.SDK_INT + ")\n"
                    + t + "\n" + sw;
            try (java.io.FileOutputStream o =
                         new java.io.FileOutputStream(new java.io.File(
                                 getFilesDir(), "crash-" + ts + ".txt"))) {
                o.write(info.getBytes(StandardCharsets.UTF_8));
            }
            android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
            p.edit().putString("last_crash", info.length() > 3000
                    ? info.substring(0, 3000) : info).apply();
        } catch (Exception ignored) {}
    }

    private void showLastCrashIfAny() {
        try {
            android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
            String last = p.getString("last_crash", null);
            if (last == null) return;
            p.edit().remove("last_crash").apply();
            new AlertDialog.Builder(this)
                    .setTitle("Na abertura anterior deu erro")
                    .setMessage(last + "\n\nTire um print e mande ao Yuki!")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {}
    }
}
