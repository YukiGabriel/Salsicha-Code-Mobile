# 🌭 Salsicha Code Mobile

Editor + compilador Java on-device para Android. Escreva, compile e rode sem PC.

## ✨ v2.4 — primeira release
- Tela de boas-vindas estilo IntelliJ: **Criar projeto / Abrir projeto** (pasta) + recentes
- Editor sem quebra de linha (modo IDE), régua, highlight, buscar/replace
- Sugestões inline que não roubam o teclado, erros/warnings com gutter `●/▲`
- Run on-device (ECJ + D8): `import java.util.Scanner` funciona, `System.in` interativo pelo campo ⌨ do console
- Console estilo IntelliJ: limpo, com scroll que respeita leitura
- Splash, ícone adaptativo, backup sem levar o `android.jar`

## 📦 Instalação
- `salsicha-code-release.apk` — instale direto no Android 8.0+ (26+)
- `salsicha-code-release.aab` — artefato Play Store

## 🚀 Uso
1. Abra → **Criar projeto** (escolha a pasta-mãe) ou **Abrir projeto** (pasta com `.java`)
2. Edite `Main.java`, toque **▶ Run**
3. Se pedir entrada (`Scanner`), digite no campo ⌨ do console + **Enviar**

## 🛠 Build
```bash
./gradlew :app:assembleDebug        # teste
./gradlew :app:bundleRelease :app:assembleRelease  # release (v7 / 2.4)
```

Requer Android SDK + JDK 17. Release de teste assina com debug key; pra Play use keystore próprio.

## 📄 Licença
MIT — ver `LICENSE`.
ECJ (EPL-2.0) e R8/D8 (BSD-3-Clause) via Gradle.
