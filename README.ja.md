<div align="center">

# SpeedShareWeb

Androidスマートフォンを高速なローカルネットワーク用ファイルサーバーにし、ブラウザから直接アクセスできます。

[English](README.md) · [简体中文](README.zh-CN.md)

<br>

<a href="https://github.com/Dream-City6/SpeedShareWeb/releases/download/v1.3.0/SpeedShareWeb-v1.3.0.apk">
  <img src="https://img.shields.io/badge/ダウンロード-SpeedShareWeb%20v1.3.0%20APK-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="SpeedShareWeb v1.3.0 APKをダウンロード">
</a>

<br><br>

v1.3.0 では、ブラウザからの再開可能なアップロード、アップロードキューの進捗表示、キャンセル/失敗項目の再試行、より確実なフォルダーのドラッグ＆ドロップ、動画プレビュー互換性の改善を追加しました。
その他のバージョンや更新内容については、[GitHub Releases](https://github.com/Dream-City6/SpeedShareWeb/releases)をご確認ください。

</div>

## このプロジェクトを作った理由

自宅には複数のスマートフォン、タブレット、パソコンがありますが、それぞれ異なるOSやデバイス環境を使用しています。

ファイルを転送するたびに対応するアプリやサービスを探す必要があり、ルーターの性能を十分に引き出せないうえ、動作が遅く、手間もかかり、広告が表示されるものもありました。

さすがに我慢できなくなり、SpeedShareWebを自分で作りました。同じローカルネットワークに接続されていれば、特定のメーカー、アカウント、クラウドストレージに依存せず、ファイルの閲覧、アップロード、ダウンロード、管理を直接行えます。

## プロジェクト概要

SpeedShareWebを使うと、Android端末と同じローカルネットワーク上にあるスマートフォン、タブレット、パソコンとの間でファイルを転送できます。

受信側の端末にアプリをインストールする必要はありません。SpeedShareWebに表示されるローカルアドレスをブラウザで開くだけで利用できます。

## 主な機能

- スマートフォン、タブレット、パソコンのブラウザからアクセス可能
- アカウントやクラウドストレージは不要
- ファイルの閲覧、アップロード、ダウンロード、移動、削除
- フォルダー構造を保持したフォルダーアップロード
- 単一ファイル、複数ファイル、ZIP形式でのダウンロード
- 最近のアップロード、ダウンロード、ファイル操作履歴の確認
- Androidアプリとブラウザページ間の任意のクリップボード同期
- レスポンシブ対応のリスト表示とグリッド表示
- ごみ箱からの復元、完全削除、一括削除
- 日本語、簡体字中国語、英語に対応
- 広告なし

## スクリーンショット

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-home-en.jpg" width="300" alt="SpeedShareWeb 英語ホーム画面">
      <br>
      <sub>Androidアプリのホーム画面</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-ja.jpg" width="300" alt="SpeedShareWeb 日本語ホーム画面">
      <br>
      <sub>リアルタイム転送状況</sub>
    </td>
  </tr>
</table>

<p align="center">
  <img src="docs/screenshots/web-file-manager.png" width="100%" alt="SpeedShareWeb ブラウザファイルマネージャー">
</p>

## 使い方

1. Android端末ともう一方の端末を、同じ信頼できるローカルネットワークに接続します。
2. SpeedShareWebでサーバーを起動します。
3. もう一方の端末のブラウザで、表示されたローカルアドレスを開きます。
4. ファイルの閲覧、転送、管理を行います。
5. テキストを同期したい場合は、設定でクリップボード同期を有効にします。
6. 使用後はサーバーを停止します。

## プライバシーとセキュリティ

SpeedShareWebは主にローカルネットワーク内で動作し、アカウントやクラウドストレージを必要としません。

信頼できるネットワーク上でのみ使用し、ローカルHTTPサーバーをインターネットへ直接公開しないでください。

クリップボード同期も同じローカルHTTP接続を使用します。パスワード、認証コード、トークン、その他の機密テキストは同期しないでください。

詳細については、[PRIVACY.md](PRIVACY.md)および[SECURITY.md](SECURITY.md)をご確認ください。

## ライセンス

本プロジェクトは GNU General Public License v3.0 のもとで公開されています。詳細については[LICENSE](LICENSE)をご確認ください。

## 免責事項

SpeedShareWebは、アクセスおよび管理する権限のあるファイルと端末に対してのみ使用してください。本プロジェクトはいかなる保証もなく提供されます。
