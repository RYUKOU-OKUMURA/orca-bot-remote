# Phase 0 の Android 開発環境（2026-11-21）

## 使用したバージョン

Apple Silicon の Mac mini で、Android Studio を使わずにビルドと動作確認を行った。

| ツール・パッケージ | 確認した版 |
| --- | --- |
| Homebrew の OpenJDK | 21.0.13.0.3 |
| Android SDK Command-line Tools | 22.0 |
| `platform-tools` | 37.0.3 |
| `emulator` | 37.3.10 |
| `platforms;android-37.2` | リビジョン 2 |
| `build-tools;37.0.0` | 37.0.0 |
| `system-images;android-37.2;google_apis_ps16k;arm64-v8a` | リビジョン 7 |
| Gradle Wrapper | 9.6.0 |
| Android Gradle Plugin | 9.5.2 |
| Kotlin と Compose Compiler プラグイン | 2.4.21 |
| Compose BOM | 2026.11.01 |
| Activity Compose | 1.13.0 |

ビルドには `compileSdk` で指定した API 37.2（`platforms;android-37.2`）を使う。採用した Compose UI が要求するコンパイル時の API 37.1 以上を満たす。

今回の画面確認に使った仮想端末の実行環境は、Play ストアを含まない Google APIs の ARM64 システムイメージ（API 37.2、16 KB ページの `google_apis_ps16k`）。

## 初回セットアップ

Homebrew の `openjdk@21` と `android-commandlinetools` がインストール済みの状態から始める。以下の環境変数は、作業するシェルごとに設定する。リポジトリ直下で実行する。

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

以下で SDK のライセンスに同意し、必要なパッケージを追加する。

```sh
yes | sdkmanager --licenses
sdkmanager --channel=0 'platform-tools' 'emulator' \
  'platforms;android-37.2' 'build-tools;37.0.0' \
  'system-images;android-37.2;google_apis_ps16k;arm64-v8a'
```

Pixel 8 の仮想端末 `orca-remote` を初回だけ作る。

```sh
printf 'no\n' | avdmanager create avd --name orca-remote --device pixel_8 \
  --package 'system-images;android-37.2;google_apis_ps16k;arm64-v8a'
```

## ビルドと画面確認

以下はリポジトリ直下から同じシェルで順に実行する。

```sh
(cd android && ./gradlew assembleDebug)
```

APK は `android/app/build/outputs/apk/debug/app-debug.apk` に生成される。

ウィンドウを出さずに仮想端末を起動する。ポート 5554 は空いているものとする。起動ログのパスはコマンドで表示する。

```sh
emulator_log=$(mktemp -t orca-remote-emulator)
emulator -avd orca-remote -port 5554 -memory 4096 -no-window -no-audio -no-snapshot >"$emulator_log" 2>&1 &
emulator_pid=$!
printf '%s\n' "$emulator_log"
```

Android の起動が完了したら APK をインストールし、アプリを起動する。起動を最大約 3 分待つ。

```sh
(
  for attempt in $(seq 1 90); do
    boot_complete=$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$boot_complete" = 1 ] && break
    sleep 2
  done
  [ "$boot_complete" = 1 ] || { printf 'Android が起動しませんでした。\n' >&2; exit 1; }

  adb -s emulator-5554 shell wm dismiss-keyguard &&
    adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk &&
    adb -s emulator-5554 shell am start -S -W -n dev.okumura.orcaremote/.MainActivity
)
```

画面を PNG で保存し、中央に「Orca Bot Remote」と表示されていることを確認する。

```sh
mkdir -p docs/images
adb -s emulator-5554 exec-out screencap -p > docs/images/2026-11-21-phase0-empty-app.png
open docs/images/2026-11-21-phase0-empty-app.png
```

## 停止

確認後、または途中でエラーが出たときは、仮想端末と Gradle デーモンを停止する。エミュレーターが起動途中で adb から見えない場合は、起動ログを確認し、上で記録した PID のプロセスを終了する。

```sh
adb -s emulator-5554 emu kill || kill "$emulator_pid"
wait "$emulator_pid"
(cd android && ./gradlew --stop)
adb devices
pgrep -fl qemu
(cd android && ./gradlew --status)
```

この端末だけを接続していた場合、停止直後に `adb devices` に `offline` と出ても数秒後に消える。`pgrep -fl qemu` は表示なし、`./gradlew --status` は `No Gradle daemons are running.` が確認の目安。

## 所要時間とディスク使用量

- この Mac mini では SDK の導入に約 2 分、初回のアプリビルドに約 53 秒かかった。Gradle 本体とプラグインの初回ダウンロードには別途時間がかかる。ダウンロード済みの状態でのクリーンビルドは約 8 秒だった。
- エミュレーターが Android の起動を完了するまで約 20 秒かかった。APK をインストールしてアプリを開くまでさらに数秒かかった。
- 確認後のディスク使用量は SDK 約 10 GiB、仮想端末のデータ約 1.4 GiB、Gradle キャッシュ約 1.1 GiB、プロジェクトのビルド生成物約 70 MiB だった。SDK のうち約 8.2 GiB はシステムイメージで、ダウンロードと展開のために一時領域も使う。

## 作業中に確認した点

- この Mac の `/usr/bin/java` は起動用のスタブなので、Homebrew で導入した JDK の実体を `JAVA_HOME` に指定した。
- `avdmanager` はイメージの `devices.xml` を読み込めないと表示した。Pixel 8 の AVD は作成され、端末上で API 37.2 とページサイズ 16384 バイトを確認できた。起動時に Android 17 のスマートフォン型 AVD 用に RAM 4 GiB を指定する。
- 初回ビルドでは Gradle の既定ヒープ 512 MiB が不足したため、`android/gradle.properties` で上限を 1 GiB にした。AndroidX が含むネイティブライブラリのデバッグシンボルを除去できないという警告は出たが、デバッグ APK の生成・インストール・起動に成功した。
