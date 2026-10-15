package com.salsicha.code;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Tela inicial estilo IntelliJ: Criar projeto / Abrir projeto / recentes. */
public class WelcomeActivity extends AppCompatActivity {

    private static final String SAMPLE_MAIN =
            "public class Main {\n"
            + "    public static void main(String[] args) {\n"
            + "        // Bem-vindo ao Salsicha Code! 🌭\n"
            + "        System.out.println(\"Olá, projeto novo!\");\n"
            + "    }\n"
            + "}\n";

    private ListView recentList;
    private View emptyView;
    private String pendingProjectName;
    private Uri pendingParentTree;

    private final ActivityResultLauncher<Uri> openTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
                openProject(uri);
            });

    private final ActivityResultLauncher<Uri> parentTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) { pendingProjectName = null; return; }
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
                pendingParentTree = uri;
                createProjectIn(uri, pendingProjectName);
                pendingProjectName = null;
            });

    private final ActivityResultLauncher<String[]> openFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
                ProjectStore.clearCurrent(this);
                Intent i = new Intent(this, MainActivity.class);
                i.setAction(Intent.ACTION_VIEW);
                i.setData(uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivity(i);
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_welcome);

        recentList = findViewById(R.id.recentList);
        emptyView = findViewById(R.id.emptyView);

        findViewById(R.id.btnNew).setOnClickListener(v -> askNewProject());
        findViewById(R.id.btnOpen).setOnClickListener(v -> {
            try { openTreeLauncher.launch(null); } catch (Exception e) { toast("Sem seletor de pasta"); }
        });
        findViewById(R.id.btnFile).setOnClickListener(v -> {
            try { openFileLauncher.launch(new String[]{"text/*", "application/*"}); }
            catch (Exception e) { toast("Sem seletor de arquivo"); }
        });

        refreshRecents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecents();
    }

    private void refreshRecents() {
        List<String[]> recents = ProjectStore.recents(this);
        emptyView.setVisibility(recents.isEmpty() ? View.VISIBLE : View.GONE);
        recentList.setVisibility(recents.isEmpty() ? View.GONE : View.VISIBLE);
        ArrayAdapter<String[]> ad = new ArrayAdapter<String[]>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, recents) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                try {
                    TextView t1 = v.findViewById(android.R.id.text1);
                    TextView t2 = v.findViewById(android.R.id.text2);
                    String[] e = getItem(pos);
                    t1.setText(e != null ? e[0] : "?");
                    t1.setTypeface(Typeface.MONOSPACE);
                    t1.setTextColor(0xFFFFF9F7);
                    t1.setTextSize(15f);
                    if (t2 != null) {
                        t2.setText(e != null ? e[1] : "");
                        t2.setTypeface(Typeface.MONOSPACE);
                        t2.setTextSize(11f);
                        t2.setTextColor(0xFFA89B9E);
                        t2.setSingleLine(true);
                    }
                    v.setBackgroundResource(R.drawable.bg_pill);
                    int p = (int) (10 * getResources().getDisplayMetrics().density);
                    v.setPadding(p, p, p, p);
                } catch (Exception ignored) {}
                return v;
            }
        };
        recentList.setAdapter(ad);
        recentList.setOnItemClickListener((p, v, pos, id) -> {
            try {
                String[] e = recents.get(pos);
                openProject(Uri.parse(e[1]));
            } catch (Exception ex) { toast("Projeto inválido"); }
        });
        recentList.setOnItemLongClickListener((p, v, pos, id) -> {
            try {
                String[] e = recents.get(pos);
                new AlertDialog.Builder(WelcomeActivity.this)
                        .setTitle("Remover dos recentes?")
                        .setMessage(e[0])
                        .setPositiveButton("Remover", (a, b2) -> {
                            ProjectStore.removeRecent(WelcomeActivity.this, e[1]);
                            refreshRecents();
                        })
                        .setNegativeButton("Cancelar", null).show();
            } catch (Exception ignored) {}
            return true;
        });
    }

    private void askNewProject() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView hint = new TextView(this);
        hint.setText("Nome da pasta do projeto:");
        hint.setTextColor(0xFFA89B9E);
        final EditText name = new EditText(this);
        name.setHint("MeuApp");
        name.setTextColor(0xFFFFF9F7);
        name.setHintTextColor(0xFFA89B9E);
        name.setTypeface(Typeface.MONOSPACE);
        box.addView(hint);
        box.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("＋ Criar projeto")
                .setView(box)
                .setPositiveButton("Escolher onde criar…", (a, b) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = "MeuApp";
                    n = n.replaceAll("[^A-Za-z0-9_\\- ]", "").trim().replace(" ", "_");
                    if (n.isEmpty()) n = "MeuApp";
                    pendingProjectName = n;
                    try { parentTreeLauncher.launch(null); }
                    catch (Exception e) { toast("Sem seletor de pasta"); pendingProjectName = null; }
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void createProjectIn(Uri parentTree, String name) {
        try {
            DocumentFile parent = DocumentFile.fromTreeUri(this, parentTree);
            if (parent == null || !parent.canWrite()) { toast("Pasta sem escrita"); return; }
            // evita duplicar: se já existe, abre
            DocumentFile existing = parent.findFile(name);
            DocumentFile dir = existing != null && existing.isDirectory() ? existing
                    : parent.createDirectory(name);
            if (dir == null) { toast("Não criou a pasta"); return; }
            DocumentFile main = dir.findFile("Main.java");
            if (main == null || !main.exists()) {
                DocumentFile f = dir.createFile("text/x-java", "Main.java");
                if (f != null) {
                    try (OutputStream o = getContentResolver().openOutputStream(f.getUri(), "w")) {
                        o.write(SAMPLE_MAIN.getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                }
            }
            try {
                getContentResolver().takePersistableUriPermission(dir.getUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            openProject(dir.getUri());
        } catch (Exception e) {
            toast("Erro ao criar: " + e.getMessage());
        }
    }

    private void openProject(Uri treeUri) {
        try {
            DocumentFile d = DocumentFile.fromTreeUri(this, treeUri);
            String name = d != null && d.getName() != null ? d.getName()
                    : ProjectStore.displayName(this, treeUri);
            ProjectStore.setCurrent(this, treeUri, name);
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(ProjectStore.EXTRA_PROJECT_URI, treeUri.toString());
            i.putExtra(ProjectStore.EXTRA_PROJECT_NAME, name);
            startActivity(i);
        } catch (Exception e) {
            toast("Erro ao abrir: " + e.getMessage());
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
