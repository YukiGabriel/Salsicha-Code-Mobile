package com.salsicha.code;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dalvik.system.InMemoryDexClassLoader;

/**
 * v2.1: compila Java no próprio aparelho (ECJ) → converte p/ dex (D8) →
 * carrega (InMemoryDexClassLoader, API 26+) → executa main() com saída capturada.
 *
 * DIFERENÇA v2.1: ECJ e D8 são carregados SOMENTE via reflexão dentro do
 * worker, nunca em imports estáticos. Assim a abertura do app jamais
 * verifica/carrega as 20MB de dex do R8 — se o aparelho não suportar,
 * só o Run falha com mensagem, o app continua abrindo.
 *
 * Limites honestos: sem System.in (vazio), sem pacotes extras,
 * Parar é melhor-esforço.
 */
public class RunManager {

    public interface Listener {
        void onPrint(String chunk);
        void onDone(String summary, boolean success);
    }

    private Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean running;
    private volatile LiveInputStream liveIn;

    /** Entrada interativa: o console envia texto enquanto o programa roda. */
    public static final class LiveInputStream extends InputStream {
        private final java.util.concurrent.LinkedBlockingQueue<Byte> q =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private volatile boolean closed;
        public void offer(String s) {
            if (s == null || closed) return;
            try {
                byte[] b = s.getBytes("UTF-8");
                for (byte x : b) q.offer(x);
            } catch (Exception ignored) {}
        }
        @Override public int read() {
            while (!closed) {
                try {
                    Byte b = q.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (b != null) return b & 0xFF;
                    if (Thread.currentThread().isInterrupted()) return -1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                } catch (Exception e) { return -1; }
            }
            return -1;
        }
        @Override public int read(byte[] buf, int off, int len) {
            if (buf == null || len <= 0) return 0;
            try {
                int first = read();
                if (first < 0) return -1;
                buf[off] = (byte) first;
                int count = 1;
                while (count < len) {
                    Byte b = q.poll();
                    if (b == null) break;
                    buf[off + count++] = b;
                }
                return count;
            } catch (Exception e) { return -1; }
        }
        @Override public int available() { return q.size(); }
        @Override public void close() { closed = true; }
    }

    /** Chamado pelo campo de entrada do console (thread UI). */
    public void sendInput(String s) {
        try {
            LiveInputStream in = liveIn;
            if (in == null || !running) return;
            String t = s != null ? s : "";
            if (!t.endsWith("\n")) t += "\n";
            in.offer(t);
        } catch (Exception ignored) {}
    }

    public synchronized boolean isRunning() { return running; }

    public synchronized void run(Context ctx, Map<String, String> sources, String mainClass, Listener l) {
        run(ctx, sources, mainClass, "", l);
    }

    public synchronized void run(Context ctx, Map<String, String> sources, String mainClass, String stdin, Listener l) {
        if (running) return;
        running = true;
        stopRequested = false;
        final String input = stdin != null ? stdin : "";
        worker = new Thread(() -> execute(ctx.getApplicationContext(), sources, mainClass, input, l), "salsicha-run");
        worker.start();
    }

    public synchronized void stop() {
        stopRequested = true;
        try { if (liveIn != null) liveIn.close(); } catch (Exception ignored) {}
        if (worker != null) worker.interrupt();
    }

    private void execute(Context ctx, Map<String, String> sources, String mainClass, String stdin, Listener l) {
        long t0 = System.currentTimeMillis();
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        InputStream realIn = System.in;
        try {
            File work = new File(ctx.getFilesDir(), "run");
            File srcDir = new File(work, "src");
            File clsDir = new File(work, "classes");
            File dexDir = new File(work, "dex");
            deleteRec(work);
            srcDir.mkdirs(); clsDir.mkdirs(); dexDir.mkdirs();

            List<String> files = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                String name = e.getKey();
                if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*\\.java")) continue;
                File f = new File(srcDir, name);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(e.getValue().getBytes("UTF-8"));
                }
                files.add(f.getAbsolutePath());
            }
            if (files.isEmpty()) { done(l, "❌ Nenhum arquivo .java válido p/ compilar", false, t0); return; }

            File androidJar = new File(work, "android.jar");
            File stubJar = new File(work, "lambda_stub.jar");
            if (!androidJar.exists() || androidJar.length() < 1000
                    || !stubJar.exists() || stubJar.length() == 0) {
                android.util.Log.i("SalsichaCode", "preparando android.jar (1ª vez)");
                try {
                    copyAsset(ctx, "android_rt.bin", androidJar);
                } catch (java.io.FileNotFoundException fnf) {
                    post(realOut, l, "❌ android_rt.bin não está no APK.\n"
                            + "Reinstale o app com assets (app/src/main/assets).\n");
                    done(l, "❌ Asset ausente: android_rt.bin", false, t0);
                    return;
                }
                try {
                    copyAsset(ctx, "lambda_stub.bin", stubJar);
                } catch (java.io.FileNotFoundException fnf) {
                    post(realOut, l, "❌ lambda_stub.bin não está no APK.\nReinstale o app.\n");
                    done(l, "❌ Asset ausente: lambda_stub.bin", false, t0);
                    return;
                }
                if (androidJar.length() < 1000) {
                    post(realOut, l, "❌ android.jar copiado mas vazio/corrompido.\n");
                    done(l, "❌ Biblioteca corrompida", false, t0);
                    return;
                }
            }

            // ---- 1. ECJ via reflexão ---- (silencioso estilo IntelliJ: só erro aparece)
            StringWriter ecjOut = new StringWriter(), ecjErr = new StringWriter();
            String bootcp = androidJar.getAbsolutePath() + File.pathSeparator + stubJar.getAbsolutePath();
            List<String> args = new ArrayList<>();
            args.add("-bootclasspath"); args.add(bootcp);
            args.add("-source"); args.add("1.8");
            args.add("-target"); args.add("1.8");
            args.add("-encoding"); args.add("UTF-8");
            args.add("-nowarn"); args.add("-proc:none");
            args.add("-d"); args.add(clsDir.getAbsolutePath());
            args.addAll(files);
            boolean ok;
            try {
                ok = compileWithEcj(args, ecjOut, ecjErr);
            } catch (Throwable t) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                post(realOut, l, "❌ Compilador indisponível neste aparelho:\n" + t + "\n");
                android.util.Log.e("SalsichaCode", "ECJ falhou", t);
                done(l, "❌ Falha interna do compilador", false, t0);
                return;
            }
            String diag = ecjOut.toString() + ecjErr.toString();
            if (stopRequested) { done(l, "⏹ Parado", false, t0); return; }
            if (!ok) {
                if (!diag.isEmpty()) post(realOut, l, diag + "\n");
                String hint = importHint(diag);
                if (!hint.isEmpty()) post(realOut, l, hint);
                done(l, "❌ Erros de compilação", false, t0); return;
            }

            List<java.nio.file.Path> classes = new ArrayList<>();
            collectClasses(clsDir, classes);
            if (classes.isEmpty()) { done(l, "❌ Nada compilado (classe pública = nome do arquivo?)", false, t0); return; }

            // ---- 2. D8 via reflexão ---- (silencioso; log só no Logcat)
            java.io.ByteArrayOutputStream d8Buf = new java.io.ByteArrayOutputStream();
            PrintStream tmpOut = new PrintStream(d8Buf, true);
            System.setOut(tmpOut);
            System.setErr(tmpOut);
            System.setOut(tmpOut);
            System.setErr(tmpOut);
            try {
                runD8Reflect(classes, androidJar, stubJar, dexDir);
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
                if (t.getClass().getName().contains("CompilationFailed")) {
                    post(realOut, l, "❌ D8: " + msg + "\n");
                    done(l, "❌ Falha ao converter (API nova demais? min API 26)", false, t0);
                } else {
                    post(realOut, l, "❌ Conversor indisponível neste aparelho:\n" + t + "\n");
                    android.util.Log.e("SalsichaCode", "D8 falhou", t);
                    done(l, "❌ Falha interna do conversor", false, t0);
                }
                return;
            } finally {
                System.setOut(realOut);
                System.setErr(realErr);
            }
            if (stopRequested) { done(l, "⏹ Parado", false, t0); return; }

            File dex = new File(dexDir, "classes.dex");
            if (!dex.exists()) { done(l, "❌ classes.dex não gerado", false, t0); return; }
            byte[] dexBytes = java.nio.file.Files.readAllBytes(dex.toPath());

            // ---- 3. carrega + executa ----
            Class<?> cls;
            try {
                InMemoryDexClassLoader loader = new InMemoryDexClassLoader(
                        ByteBuffer.wrap(dexBytes), getClass().getClassLoader());
                cls = Class.forName(mainClass, true, loader);
            } catch (Throwable t) {
                done(l, "❌ Classe " + mainClass + " não encontrada (" + t.getMessage() + ")", false, t0);
                return;
            }
            Method main;
            try {
                main = cls.getMethod("main", String[].class);
                if (!Modifier.isStatic(main.getModifiers())) throw new NoSuchMethodException("main não-estático");
            } catch (Throwable t) {
                done(l, "❌ " + mainClass + " sem 'public static void main(String[])'", false, t0);
                return;
            }

            PrintStream cap = new PrintStream(new ListenerStream(l), true);
            System.setOut(cap);
            System.setErr(cap);
            LiveInputStream live = new LiveInputStream();
            liveIn = live;
            try {
                if (stdin != null && !stdin.isEmpty()) {
                    String pre = stdin.endsWith("\n") ? stdin : stdin + "\n";
                    live.offer(pre);
                }
            } catch (Exception ignored) {}
            System.setIn(live);
            try {
                main.invoke(null, (Object) new String[0]);
                done(l, "Process finished with exit code 0", true, t0);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                StringWriter sw = new StringWriter();
                cause.printStackTrace(new PrintWriter(sw));
                String[] lines = sw.toString().split("\n");
                StringBuilder sb = new StringBuilder("❌ Exceção: ").append(cause).append('\n');
                for (int i = 1; i < Math.min(lines.length, 12); i++) sb.append(lines[i]).append('\n');
                // dica amigável p/ Scanner sem entrada
                String cn = String.valueOf(cause);
                if (cn.contains("NoSuchElement") || cn.contains("No line found")) {
                    sb.append("💡 Dica: faltou entrada — digite no campo ⌨ do console e toque Enviar.\n");
                    sb.append("Programas com menu precisam de várias entradas, uma por vez.\n");
                }
                post(realOut, l, sb.toString());
                done(l, "❌ Erro na execução", false, t0);
            } catch (Throwable t) {
                post(realOut, l, "❌ " + t + "\n");
                done(l, "❌ Erro na execução", false, t0);
            } finally {
                System.setOut(realOut);
                System.setErr(realErr);
                System.setIn(realIn);
                try { live.close(); } catch (Exception ignored) {}
                liveIn = null;
            }
        } catch (Throwable t) {
            try { post(realOut, l, "❌ " + t + "\n"); } catch (Exception ignored) {}
            done(l, "❌ Falha inesperada", false, t0);
            System.setOut(realOut);
            System.setErr(realErr);
            System.setIn(realIn);
        } finally {
            synchronized (this) { running = false; }
        }
    }

    /** ECJ sem import estático: Class.forName só aqui, dentro do worker. */
    private static boolean compileWithEcj(List<String> args, StringWriter out, StringWriter err) throws Exception {
        Class<?> mainCls = Class.forName("org.eclipse.jdt.internal.compiler.batch.Main");
        Class<?> progressCls;
        try {
            progressCls = Class.forName("org.eclipse.jdt.core.compiler.CompilationProgress");
        } catch (ClassNotFoundException e) {
            progressCls = null;
        }
        Constructor<?> ctor = null;
        // tenta assinatura (PrintWriter, PrintWriter, boolean, Map, CompilationProgress)
        for (Constructor<?> c : mainCls.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 5 && p[0] == PrintWriter.class && p[1] == PrintWriter.class
                    && p[2] == boolean.class && Map.class.isAssignableFrom(p[3])) {
                ctor = c;
                break;
            }
        }
        if (ctor == null) throw new NoSuchMethodException("ECJ Main(Writer,Writer,boolean,Map,Progress) não achado");
        Object ecj = ctor.newInstance(new PrintWriter(out), new PrintWriter(err), false, null, null);
        Method compile = mainCls.getMethod("compile", String[].class);
        Object ret = compile.invoke(ecj, (Object) args.toArray(new String[0]));
        return ret instanceof Boolean ? (Boolean) ret : false;
    }

    /** D8 sem import estático: builder via reflexão. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runD8Reflect(List<java.nio.file.Path> classes, File androidJar, File stubJar, File dexDir) throws Exception {
        Class<?> d8CmdCls = Class.forName("com.android.tools.r8.D8Command");
        Class<?> builderCls = Class.forName("com.android.tools.r8.D8Command$Builder");
        Class<?> outputModeCls = Class.forName("com.android.tools.r8.OutputMode");
        Class<?> d8Cls = Class.forName("com.android.tools.r8.D8");

        Method builderM = d8CmdCls.getMethod("builder");
        Object builder = builderM.invoke(null);

        Method addProgram = builderCls.getMethod("addProgramFiles", java.util.Collection.class);
        builder = addProgram.invoke(builder, classes);

        Method addLib = builderCls.getMethod("addLibraryFiles", java.nio.file.Path[].class);
        builder = addLib.invoke(builder, (Object) new java.nio.file.Path[]{
                androidJar.toPath(), stubJar.toPath()});

        Method setMinApi = builderCls.getMethod("setMinApiLevel", int.class);
        builder = setMinApi.invoke(builder, 26);

        Object dexIndexed = Enum.valueOf((Class<Enum>) outputModeCls, "DexIndexed");
        Method setOutput = builderCls.getMethod("setOutput", java.nio.file.Path.class, outputModeCls);
        builder = setOutput.invoke(builder, dexDir.toPath(), dexIndexed);

        Method build = builderCls.getMethod("build");
        Object cmd = build.invoke(builder);

        Method run = d8Cls.getMethod("run", d8CmdCls);
        try {
            run.invoke(null, cmd);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    private void post(Listener l, String s) {
        if (stopRequested && s != null) return;
        l.onPrint(s);
    }

    private void post(PrintStream real, Listener l, String s) {
        real.print(s);
        l.onPrint(s);
    }

    private void done(Listener l, String summary, boolean ok, long t0) {
        double secs = (System.currentTimeMillis() - t0) / 1000.0;
        l.onDone(summary + String.format(" (%.1fs)", secs), ok);
    }

    /** Dica quando import não existe no Android (ex: java.awt, javax.swing). */
    private static String importHint(String diag) {
        if (diag == null) return "";
        String d = diag.toLowerCase();
        boolean missing = d.contains("cannot be resolved") || d.contains("cannot find")
                || d.contains("package") && d.contains("does not exist")
                || d.contains("import") && d.contains("cannot");
        if (!missing) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n💡 Imports no Salsicha Code usam o Android (API 36):\n");
        sb.append("✓ funcionam: java.util.* (Scanner, ArrayList, HashMap), java.io.*, java.math.*, java.text.*, java.time.*, java.util.regex.*\n");
        if (d.contains("awt") || d.contains("swing") || d.contains("javafx")) {
            sb.append("✗ java.awt / javax.swing / javafx NÃO existem no Android — use console.\n");
        }
        if (d.contains("nio") && d.contains("file")) {
            sb.append("✗ java.nio.file é parcial no Android — prefira java.io.File.\n");
        }
        if (d.contains("scanner")) {
            sb.append("💡 Scanner: adicione 'import java.util.Scanner;' no topo e informe a entrada ao rodar.\n");
        }
        sb.append("Ex: import java.util.Scanner; // + Scanner sc = new Scanner(System.in);\n");
        return sb.toString();
    }

    private static void copyAsset(Context ctx, String name, File dst) throws Exception {
        File tmp = new File(dst.getParent(), dst.getName() + ".tmp");
        try (InputStream in = ctx.getAssets().open(name);
             FileOutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; }
            o.getFD().sync();
            if (total == 0) throw new java.io.IOException("asset vazio: " + name);
        }
        if (!tmp.renameTo(dst)) {
            try (java.io.FileInputStream fin = new java.io.FileInputStream(tmp);
                 FileOutputStream o2 = new FileOutputStream(dst)) {
                byte[] buf2 = new byte[65536];
                int m;
                while ((m = fin.read(buf2)) > 0) o2.write(buf2, 0, m);
            }
            try { tmp.delete(); } catch (Exception ignored) {}
        }
    }

    private static void collectClasses(File dir, List<java.nio.file.Path> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectClasses(f, out);
            else if (f.getName().endsWith(".class")) out.add(f.toPath());
        }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRec(c);
        }
        f.delete();
    }

    /** OutputStream que joga tudo no console da UI. */
    private static class ListenerStream extends OutputStream {
        private final Listener l;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ListenerStream(Listener l) { this.l = l; }
        @Override public synchronized void write(int b) {
            buf.write(b);
            if (b == '\n') flush();
        }
        @Override public synchronized void flush() {
            if (buf.size() == 0) return;
            l.onPrint(buf.toString());
            buf.reset();
        }
    }
}
