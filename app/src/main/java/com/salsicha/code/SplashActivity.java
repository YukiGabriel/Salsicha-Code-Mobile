package com.salsicha.code;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Splash premium: pinta instantâneo via windowBackground e anima o logo. */
public class SplashActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable go;
    private boolean gone;

    private static final String[] MSGS = {
            "Aquecendo o forno…",
            "Fatiando a salsicha…",
            "Afiando o ECJ…",
            "Polindo o D8…",
            "Mostarda pronta ✓"
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_splash);

        View logoBox = findViewById(R.id.splashLogoBox);
        View logo = findViewById(R.id.splashLogo);
        ProgressBar bar = findViewById(R.id.splashBar);
        TextView msg = findViewById(R.id.splashMsg);

        try {
            logoBox.setScaleX(0.7f);
            logoBox.setScaleY(0.7f);
            logoBox.setAlpha(0f);
            logoBox.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(550)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            logo.animate()
                    .rotationBy(4f)
                    .setDuration(600)
                    .start();
        } catch (Exception ignored) {}

        // mensagens + barrinha cíclicas, humor seco sem enrolar
        final int[] step = {0};
        Runnable tick = new Runnable() {
            @Override public void run() {
                try {
                    int i = Math.min(step[0]++, MSGS.length - 1);
                    msg.setText(MSGS[i]);
                    bar.setProgress(15 + (i + 1) * 17);
                    if (i < MSGS.length - 1) handler.postDelayed(this, 260);
                } catch (Exception ignored) {}
            }
        };
        handler.postDelayed(tick, 200);

        go = () -> goHome();
        handler.postDelayed(go, 1400);
    }

    private void goHome() {
        if (gone) return;
        gone = true;
        try {
            Intent src = getIntent();
            // arquivo avulso abre direto no editor; senão vai p/ boas-vindas (Criar/Abrir projeto)
            boolean directFile = src != null && src.getData() != null
                    && Intent.ACTION_VIEW.equals(src.getAction());
            Intent i = new Intent(this, directFile ? MainActivity.class : WelcomeActivity.class);
            if (directFile) {
                i.setAction(src.getAction());
                i.setData(src.getData());
                i.setType(src.getType());
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            startActivity(i);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Exception e) {
            try {
                startActivity(new Intent(this, WelcomeActivity.class));
            } catch (Exception ignored) {}
        }
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { handler.removeCallbacks(go); } catch (Exception ignored) {}
        if (isFinishing()) return;
        // se o usuário saiu no meio, não reabre sozinho ao voltar
        gone = true;
    }

    @Override
    public void onBackPressed() {
        try { handler.removeCallbacks(go); } catch (Exception ignored) {}
        super.onBackPressed();
    }
}
