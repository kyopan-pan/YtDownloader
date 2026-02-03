# アプリケーション仕様書：YT Downloader for VDMX

## 1. 基本概要
* **目的:** YouTubeなどの動画をMP4としてダウンロードし、VJソフト（VDMXなど）へ即ドラッグ＆ドロップできるようにするローカルツール。
* **動作環境:** macOS（**Apple Silicon 専用**）
* **開発言語:** Java (JDK 21)
* **フレームワーク:** JavaFX (Maven管理)
* **依存ツール:** `yt-dlp`, `ffmpeg`, `deno`。起動時に `~/.ytdownloader/bin` を作成し、各ツールを以下の方法で準備する。
  * **yt-dlp:** GitHubのmacOS最新ビルド（`yt-dlp_macos`）をダウンロード
  * **ffmpeg:** 同梱バイナリをリソースからコピーして実行権限を付与
  * **Deno:** GitHubのApple Silicon向け最新ビルド（`deno-aarch64-apple-darwin.zip`）をダウンロード・解凍して実行権限を付与

## 2. 機能要件

### A. ダウンロード機能
* **入力:** クリップボードにコピーされたURL（ボタン押下時にクリップボードの文字列を取得。空またはURLでない場合は何もしない）。
* **処理フロー:**
    * ボタン押下でボタンを無効化しスピナーを表示、プログレスを「動画読み込み中...」で開始。
    * バックグラウンドスレッドでダウンロードし、終了後にUIスレッドへ処理を戻す。
    * ログに含まれる`XX%`を検出した場合はプログレスバーを更新。検出できない間は不確定状態。
    * **進捗100%到達後**、完了イベントまでの間は「変換中...」を表示し、プログレスバーは不確定（indeterminate）状態に切り替える。
    * 後処理完了後に「ダウンロード完了!」を短時間表示し、その後「待機中...」へ戻す。
    * 成功時はボタンを`success`スタイルにしてリストを更新、失敗時は`error`スタイルにする。いずれも約2秒後に元の状態へ戻し、ボタンを再度操作可能にする。
* **通常のダウンロード（共通パス）:**
    * **YouTube認証補助（任意）:** bot確認が出る場合に備えて、設定で有効化すると `--cookies-from-browser <browser[:profile]>` を付与して実行。ブラウザ名とプロファイル（例: `Default` / `Profile 1`）は設定画面で指定する。
    * **H.264優先モード**: `yt-dlp --no-playlist --extractor-args "youtube:player_client=web" --extractor-args "youtube:skip=translated_subs" --concurrent-fragments 4 -S "vcodec:h264,res,acodec:m4a" --match-filter "vcodec~='(?i)^(avc|h264)'" --merge-output-format mp4 --ffmpeg-location <内蔵ffmpeg> --js-runtimes deno -o ~/Movies/YtDlpDownloads/%(title)s.%(ext)s <URL>`
    * **互換モード（フォールバック）**: H.264形式が見つからない場合、720p以下の動画を取得しGPU変換を実行。
      * コマンド: `yt-dlp --no-playlist --extractor-args "youtube:player_client=web" --extractor-args "youtube:skip=translated_subs" --concurrent-fragments 4 -f "bv*[height<=720]+ba/b[height<=720]" --recode-video mp4 --postprocessor-args "VideoConvertor:-c:v h264_videotoolbox -b:v 5M -pix_fmt yuv420p" --ffmpeg-location <内蔵ffmpeg> --js-runtimes deno -o ... <URL>`
      * Apple Silicon GPU（VideoToolbox）を利用した高速変換。
    * **速度最適化:** 動画読み込み（メタデータ取得）短縮のため `--extractor-args "youtube:player_client=web"`（取得クライアントを web に限定）と `youtube:skip=translated_subs`（字幕翻訳取得をスキップ）を付与。DASH/HLS の並列取得のため `--concurrent-fragments 4` を付与。`youtube:` の extractor-args は YouTube 以外では無視される。
    * 環境: `PATH` の先頭に `~/.ytdownloader/bin` を付与して`ProcessBuilder`経由で実行。
* **AnimeThemes専用パイプライン:**
    * 対象: URLに`animethemes.moe`を含む場合に分岐。
    * ファイル名決定: URLパスを基にした`*.mp4`（タイムスタンプ付き）を使用。curlによるtitle取得は行わない（遅延削減のため）。
    * **直リンク取得（優先）:** ページ HTML を curl（ブラウザ UA、タイムアウト 8 秒、先頭約 30KB）で取得し、`og:video` または `video src` から `https://.../*.webm` を抽出。取得できた場合、`curl -L -m 120 --fail -o - <直リンク>` の出力を ffmpeg へパイプ。yt-dlp を介さないため、約 17 秒の yt-dlp 起動・generic 抽出の遅延を回避。
    * **フォールバック:** 直リンクを取得できない場合、`yt-dlp --no-playlist -f "bv+ba/b" -o - <ページURL>` の出力を ffmpeg へパイプ。`--js-runtimes` と yt-dlp 起動時の PATH への bin 追加を行わず、generic エクストラクターの起動をできるだけ速くする。
    * ffmpeg: `-loglevel error -analyzeduration 100M -probesize 100M -f webm -i pipe:0 -c:v h264_videotoolbox -b:v 5M -pix_fmt yuv420p -c:a aac -b:a 192k -ignore_unknown -movflags +faststart -f mp4 -y <出力パス>`
    * Apple Silicon GPU（VideoToolbox）を利用した高速変換。パイプライン各プロセスの終了コードが 0 で成功扱い。
* **保存先:** `~/Movies/YtDlpDownloads`（起動時に作成）。通常は`%(title)s.%(ext)s`で保存し、AnimeThemesは必ず`.mp4`に変換して保存。
* **プレイリスト対応:** 常に `--no-playlist` で単体動画のみを対象。

### B. ファイル管理・表示機能
* **リスト表示:** 保存先フォルダ内の`.mp4`のみを読み込み。
* **並び順:** `Files.list`で取得した順序を逆順にして表示（作成日時順ではない）。
* **自動更新:** ダウンロード成功時と削除成功時にリストを再読込。

### B'. ファイル検索機能
* **配置:** UI右側に検索エリアを配置（左側はダウンロード機能）。左右はスプリッターでリサイズ可能。
* **検索対象フォルダ:** 設定画面で「検索対象フォルダ」を指定する（外付けSSD等のパス）。未指定時は検索ボタン押下で検索は行わずログにのみ記録。
* **検索対象:** 指定フォルダ以下を最大深度8で走査し、動画ファイル（`.mp4`, `.webm`, `.mkv`, `.mov`, `.m4v`）を対象とする。
* **検索条件:** クエリ文字列に対し、(1) ファイル名の部分一致（大文字小文字無視）、(2) 動画メタ情報（ffprobe の `format_tags.title`）の部分一致のいずれかでマッチしたファイルを結果に含める。
* **メタ情報検索:** `~/.ytdownloader/bin/ffprobe` が存在し実行可能な場合にのみメタ情報検索を実施。同梱していないため、ユーザーが手動で配置するか、FFmpeg と一緒に配布されている ffprobe をコピーすると利用可能。
* **検索結果:** リスト表示し、ドラッグ＆ドロップで VDMX 等へ渡せる。削除ボタンはなし（外部フォルダのため）。

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
* **依存関係の自動セットアップ:** 起動時にバックグラウンドで `~/.ytdownloader/bin` を準備。
  * yt-dlpをGitHubからダウンロード
  * `src/main/resources/bin/ffmpeg` をコピーしてPOSIX実行権を付与
  * DenoをGitHubからダウンロード（`deno-aarch64-apple-darwin.zip`を取得・解凍）
* **依存関係の更新:** 設定画面または初回セットアップダイアログにて、yt-dlpとDenoそれぞれ個別に「最新を取得」ボタンで最新版へ更新可能。各ツールのバージョンも個別に確認できる。
* **JS Runtime の指定:** 通常ダウンロード（H.264優先・互換モード）の yt-dlp 実行時に `--js-runtimes deno` を指定し、PATH 先頭に追加した内蔵binからDenoを解決する。これによりGUI起動時でもJS runtime未検出問題を回避する。AnimeThemesパイプラインでは generic エクストラクターが JS を使わないため、`--js-runtimes` と PATH への bin 追加を行わず起動を高速化する。
* **プロセス実行の共通設定:** `PATH` の先頭に内蔵binを追加して`ProcessBuilder`を実行。通常ダウンロードでは標準出力にエラーストリームもまとめ、進捗文字列から`%`を抽出してUIへ反映。
* **非同期処理:** ダウンロード処理は専用スレッドで実行し、完了通知やUI更新はJavaFX Application Threadで行う。完了時に進捗表示をリセットし、必要に応じてファイルリストを更新。

## 4. UI/UX デザイン
* **ウィンドウサイズ:** 360px × 600px。常に最前面 (`Always On Top`)。
* **構成:** 画面は左右スプリッターで分割。
    1. **左側（ダウンロード）:** ダウンロードボタン（「Download」と表示、横幅いっぱい。クリップボードのURLでダウンロード。処理中はストップアイコン表示）、進行状況エリア（ラベル＋プログレスバー。「動画読み込み中...」「ダウンロード中...XX%」「変換中...」「待機中...」などを表示）、"Downloads" ラベル、ダウンロード済みファイルリスト（削除ボタン付き、ドラッグ＆ドロップ可能）。
    2. **右側（検索）:** "Search" ラベル、検索入力＋検索ボタン、検索対象フォルダの状態表示、検索結果リスト（ドラッグ＆ドロップ可能）。
* **テーマ:** ダーク基調のグラデーション背景にシアン系アクセントカラーを使用。
* **設定画面（追加項目）:** bot確認対策として `--cookies-from-browser` を有効化でき、ブラウザ名とプロファイルを指定可能。プロファイルの所在は Chrome の `chrome://version` にある「プロフィール パス」で確認できる。Firefoxは `about:profiles` の「Root Directory」に表示される。Safariは `chrome://version` のような画面がないため、Safari設定の「プロファイル」で名前を確認し、必要に応じて Finder の「フォルダへ移動」で `~/Library/Safari/Profiles/` を確認する。**検索対象フォルダ:** ファイル検索のルート（外付けSSD等）を指定。未設定のまま検索は実行されない。

## 5. 開発環境
* **起動（VS Code）:** `.vscode/launch.json` の「Launch YtDownloader」で起動。起動前に `mvn-compile` タスクでビルドする。Java 拡張が `ClassNotFoundException` を出す場合は、タスク「Run YtDownloader (Maven javafx:run)」で `mvn javafx:run` を実行する。
* **デバッグ:** タスク「Run YtDownloader (Maven) with Debug」で `mvn javafx:run -Pdebug` を実行し、JDWP で待機したら、launch の「Attach to YtDownloader (port 5005)」でアタッチする。`pom.xml` の `debug` プロファイルが JDWP オプションを付与する。
