# アプリケーション仕様書：YT Downloader for VDMX

## 1. 基本概要
* **目的:** YouTubeなどの動画をMP4としてダウンロードし、VJソフト（VDMXなど）へ即ドラッグ＆ドロップできるようにするローカルツール。
* **動作環境:** macOS (Intel / Apple Silicon)
* **開発言語:** Java (JDK 21)
* **フレームワーク:** JavaFX (Maven管理)
* **依存ツール:** `yt-dlp`, `ffmpeg`。起動時に `~/.ytdownloader/bin` を作成し、yt-dlpはGitHubのmacOS最新ビルドをダウンロード、ffmpegは同梱バイナリをコピーして実行権限を付与。

## 2. 機能要件

### A. ダウンロード機能
* **入力:** 画面上部のテキストフィールドにURLを入力（空のままなら何もしない）。
* **処理フロー:**
    * ボタン押下でボタンを無効化しスピナーを表示、プログレスを「動画読み込み中...」で開始。
    * バックグラウンドスレッドでダウンロードし、終了後にUIスレッドへ処理を戻す。
    * ログに含まれる`XX%`を検出した場合はプログレスバーを更新。検出できない間は不確定状態。
    * **進捗100%到達後**、完了イベントまでの間は「変換中...」を表示し、プログレスバーは不確定（indeterminate）状態に切り替える。
    * 成功時はボタンを`success`スタイルにしてリストを更新、失敗時は`error`スタイルにする。いずれも約2秒後に元の状態へ戻し、ボタンを再度操作可能にする。
* **通常のダウンロード（共通パス）:**
    * **H.264優先モード**: `yt-dlp --no-playlist --extractor-args "youtube:player_client=web" --extractor-args "youtube:skip=translated_subs" --concurrent-fragments 4 -S "vcodec:h264,res,acodec:m4a" --match-filter "vcodec~='(?i)^(avc|h264)'" --merge-output-format mp4 --ffmpeg-location <内蔵ffmpeg> -o ~/Movies/YtDlpDownloads/%(title)s.%(ext)s <URL>`
    * **互換モード（フォールバック）**: H.264形式が見つからない場合、720p以下の動画を取得しGPU変換を実行。
      * コマンド: `yt-dlp --no-playlist --extractor-args "youtube:player_client=web" --extractor-args "youtube:skip=translated_subs" --concurrent-fragments 4 -f "bv*[height<=720]+ba/b[height<=720]" --recode-video mp4 --postprocessor-args "VideoConvertor:-c:v h264_videotoolbox -b:v 5M -pix_fmt yuv420p" --ffmpeg-location <内蔵ffmpeg> -o ... <URL>`
      * Apple Silicon GPU（VideoToolbox）を利用した高速変換。
    * **速度最適化:** 動画読み込み（メタデータ取得）短縮のため `--extractor-args "youtube:player_client=web"`（取得クライアントを web に限定）と `youtube:skip=translated_subs`（字幕翻訳取得をスキップ）を付与。DASH/HLS の並列取得のため `--concurrent-fragments 4` を付与。`youtube:` の extractor-args は YouTube 以外では無視される。
    * 環境: `PATH` の先頭に `~/.ytdownloader/bin` を付与して`ProcessBuilder`経由で実行。
* **AnimeThemes専用パイプライン:**
    * 対象: URLに`animethemes.moe`を含む場合に分岐。
    * ファイル名決定: curlでページのtitleタグから取得し、取得失敗時はURLパスを基にした`*.mp4`（タイムスタンプ付き）へフォールバック。
    * コマンド: `yt-dlp --no-playlist --concurrent-fragments 4 -f "bv+ba/b" -o - <URL>` の出力を `ffmpeg -loglevel error -analyzeduration 100M -probesize 100M -f webm -i pipe:0 -c:v h264_videotoolbox -b:v 5M -pix_fmt yuv420p -c:a aac -b:a 192k -ignore_unknown -movflags +faststart -f mp4 -y <出力パス>` へパイプ。
    * Apple Silicon GPU（VideoToolbox）を利用した高速変換。両プロセスの終了コードが0で成功扱い。
* **保存先:** `~/Movies/YtDlpDownloads`（起動時に作成）。通常は`%(title)s.%(ext)s`で保存し、AnimeThemesは必ず`.mp4`に変換して保存。
* **プレイリスト対応:** 常に `--no-playlist` で単体動画のみを対象。

### B. ファイル管理・表示機能
* **リスト表示:** 保存先フォルダ内の`.mp4`のみを読み込み。
* **並び順:** `Files.list`で取得した順序を逆順にして表示（作成日時順ではない）。
* **自動更新:** ダウンロード成功時と削除成功時にリストを再読込。

### C. 外部連携機能 (Drag & Drop)
* **連携方式:** ListViewのアイテムをドラッグすると`TransferMode.COPY`でファイルを渡す。VDMX、Finder、デスクトップ等へのドロップに対応。

### D. 削除機能
* **トリガー:** ダウンロード済み各行右端の×ボタン。
* **動作:** 対象の`.mp4`を`File.delete()`で即削除。成功時のみリストを更新。
* **失敗時:** 標準エラーへログ出力のみで、UI上の表示は変更しない。確認ダイアログなし。

### E. ログ機能
* **ログ表示:** メニュー「ログ...」からログ画面を開き、セッション中のログを一覧表示。アプリ終了でクリア。
* **コピー:** 「直近10分をコピー」で直近10分のログをクリップボードへコピー。
* **表示クリア:** 「表示をクリア」でログ一覧を消去。

## 3. 内部ロジック仕様 (Backend)
* **依存関係の自動セットアップ:** 起動時にバックグラウンドで `~/.ytdownloader/bin` を準備。yt-dlpをGitHubからダウンロードし、`src/main/resources/bin/ffmpeg` をコピーしてPOSIX実行権を付与。
* **プロセス実行の共通設定:** `PATH` の先頭に内蔵binを追加して`ProcessBuilder`を実行。通常ダウンロードでは標準出力にエラーストリームもまとめ、進捗文字列から`%`を抽出してUIへ反映。
* **非同期処理:** ダウンロード処理は専用スレッドで実行し、完了通知やUI更新はJavaFX Application Threadで行う。完了時に進捗表示をリセットし、必要に応じてファイルリストを更新。

## 4. UI/UX デザイン
* **ウィンドウサイズ:** 360px × 600px。常に最前面 (`Always On Top`)。
* **構成:**
    1. URL入力フィールド（プレースホルダ「YouTube URL...」）
    2. ダウンロードボタン（ダウンロードアイコン、処理中はスピナー表示）
    3. 進行状況エリア（ラベル＋プログレスバー。「動画読み込み中...」「ダウンロード中...XX%」「変換中...」「待機中...」などを表示）
    4. "Downloads" ラベル
    5. ダウンロード済みファイルリスト（削除ボタン付き、ドラッグ＆ドロップ可能）
* **テーマ:** ダーク基調のグラデーション背景にシアン系アクセントカラーを使用。

## 5. 開発環境
* **起動（VS Code）:** `.vscode/launch.json` の「Launch YtDownloader」で起動。起動前に `mvn-compile` タスクでビルドする。Java 拡張が `ClassNotFoundException` を出す場合は、タスク「Run YtDownloader (Maven javafx:run)」で `mvn javafx:run` を実行する。
* **デバッグ:** タスク「Run YtDownloader (Maven) with Debug」で `mvn javafx:run -Pdebug` を実行し、JDWP で待機したら、launch の「Attach to YtDownloader (port 5005)」でアタッチする。`pom.xml` の `debug` プロファイルが JDWP オプションを付与する。
