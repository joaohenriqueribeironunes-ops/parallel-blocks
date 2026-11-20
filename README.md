# ParaDown

ParaDown é um gerenciador de downloads para Android (Kotlin + Jetpack Compose) com motor de
download segmentado por HTTP Range, estilo dos navegadores, com até 8 conexões por arquivo,
3 downloads ativos simultâneos, foreground service `dataSync`, captura de links, retomada e
UI em Material 3.

## Principais recursos

- Download paralelo segmentado (HTTP `Range`) com work stealing e adaptação de conexões (4 → 8).
- Fallback para fluxo único quando o servidor não aceita faixas.
- Retomada com validação por `If-Range` (ETag forte ou `Last-Modified`).
- Checksum SHA-256 opcional antes de publicar o arquivo.
- Escrita posicional em disco via `RandomAccessFile`/`FileChannel` (`FileDescriptor`).
- Armazenamento via MediaStore (Downloads) ou SAF, sem `MANAGE_EXTERNAL_STORAGE`.
- Foreground service persistente com progresso, velocidade, pausar/cancelar.
- Captura sem root: `ACTION_VIEW` (MIME/extensões), `ACTION_SEND` (texto), monitor de clipboard em primeiro plano e botão "Colar link".
- Configurações: conexões, pasta, somente Wi-Fi, limite de velocidade, tema.
- Onboarding para MIUI/HyperOS (autostart e bateria).

## Requisitos de build

- Android SDK 34, Java 17.
- Gradle 8.13 (wrapper incluído).
- O caminho do projeto contém caracteres não ASCII; em Windows use um junction ASCII se necessário:
  `mklink /J C:\pd "C:\Users\conta\OneDrive\Documentos\Projeto Padrão\ParaDown"`

## Compilar

```sh
./gradlew assembleDebug
```

No Windows, via junction:

```bat
cd /d C:\Users\conta\AppData\Local\Temp\pd
gradlew.bat :app:assembleDebug
```

## Testes

```sh
./gradlew testDebugUnitTest
```

Os testes JVM cobrem o divisor/work stealing do `SegmentScheduler` (cobertura exata, split, work stealing, retomada e validação de segmentos).

## Verificação completa

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## APK

O APK de debug é gerado em:

`app/build/outputs/apk/debug/app-debug.apk`

## Limitações conhecidas

- Downloads na fila não são iniciados automaticamente após o processo ser encerrado pelo sistema; ao reabrir o app, os downloads interrompidos ficam pausados para retomada manual.
- O monitor de clipboard funciona apenas enquanto o app está em primeiro plano (restrição do Android 10+).
- A captura por `ACTION_VIEW` é filtrada por extensões/MIME conhecidos; links sem extensão ou MIME incompatível podem não aparecer no chooser.
- Sem uso de `AccessibilityService` ou VPN.
- Sem `MANAGE_EXTERNAL_STORAGE`; a pasta de destino usa MediaStore ou SAF.

## Estrutura

- `app/src/main/kotlin/com/paradown/app/download` — motor, scheduler, probe, controle.
- `app/src/main/kotlin/com/paradown/app/service` — foreground service e notificações.
- `app/src/main/kotlin/com/paradown/app/capture` — captura de links e clipboard.
- `app/src/main/kotlin/com/paradown/app/ui` — telas Compose.
- `app/src/test` — testes JVM.
