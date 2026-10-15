package com.salsicha.code;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** v2.4: minSdk 26 tem multidex nativo + salva crash em arquivo p/ diagnóstico sem logcat. */
public class SalsichaApp extends Application {
    private static final String TAG = "SalsichaCode";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String trace = sw.toString();
                Log.e(TAG, "CRASH na thread " + t.getName(), e);
                try {
                    String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                    String info = "thread=" + t.getName() + "\n" + trace;
                    // interno (sempre) + externo (acessível via Arquivos, se permitido)
                    try {
                        File out = new File(getFilesDir(), "crash-" + ts + ".txt");
                        try (FileOutputStream o = new FileOutputStream(out)) {
                            o.write(info.getBytes("UTF-8"));
                        }
                    } catch (Exception ignored) {}
                    try {
                        File ext = getExternalFilesDir(null);
                        if (ext != null) {
                            File out2 = new File(ext, "crash-" + ts + ".txt");
                            try (FileOutputStream o2 = new FileOutputStream(out2)) {
                                o2.write(info.getBytes("UTF-8"));
                            }
                        }
                    } catch (Exception ignored) {}
                } catch (Exception ignored) {}
            } catch (Exception ignored) {
            } finally {
                // deixa o sistema mostrar o diálogo padrão
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }
}
