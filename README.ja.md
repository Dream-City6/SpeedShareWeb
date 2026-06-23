# SpeedShareWeb

Android端末を一時的なローカルファイルサーバーとして使用し、パソコン、タブレット、ほかのスマートフォンからWebブラウザでアクセスできるアプリです。


## 概要

SpeedShareWebは、同じローカルネットワーク上にある端末間でファイルを閲覧・転送するためのアプリです。

受信側の端末にアプリをインストールする必要はありません。SpeedShareWebに表示されるローカルアドレスをブラウザで開くだけで、ファイルのアップロード、ダウンロード、閲覧、管理ができます。

## このプロジェクトを作った理由

自宅には複数のスマートフォン、タブレット、パソコンがありますが、それぞれ異なるOSやエコシステムを使用しています。

端末間でファイルを転送するたびに、互換性のあるアプリを探したり、クラウドなどの中継サービスを利用したりする必要があり、操作が面倒なだけでなく、自宅のローカルネットワークの速度を十分に活かせないこともありました。

そこで、SpeedShareWebを開発しました。

特定のメーカーや端末のエコシステムに依存せず、同じローカルネットワークに接続され、ブラウザを利用できる端末であれば、ファイルの閲覧、アップロード、ダウンロード、管理を直接行えます。

また、ローカルネットワークとルーターの性能をできる限り活かせるよう、転送性能の最適化にも取り組みました。

このプロジェクトは、まず自分自身の実際の問題を解決するために作ったものです。同じような悩みを持つ方のファイル転送を、少しでも便利にできればうれしいです。


## Screenshots

### Android app

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-home-en.jpg" width="300" alt="SpeedShareWeb English home screen">
      <br>
      <sub>English interface</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-ja.jpg" width="300" alt="SpeedShareWeb Japanese home screen">
      <br>
      <sub>Japanese interface and live transfer</sub>
    </td>
  </tr>
</table>

### Browser file manager

<p align="center">
  <img src="docs/screenshots/web-file-manager.png" width="100%" alt="SpeedShareWeb browser file manager">
</p>

<p align="center">
  Browse, upload, download, organize, and restore files directly from a browser on the same local network.
</p>

### Settings

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/settings-en-general.jpg" width="260" alt="SpeedShareWeb general settings">
      <br>
      <sub>General settings</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings-en-network.jpg" width="260" alt="SpeedShareWeb network settings">
      <br>
      <sub>Network and shortcuts</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings-ja-general.jpg" width="260" alt="SpeedShareWeb Japanese settings">
      <br>
      <sub>Japanese settings</sub>
    </td>
  </tr>
</table>
## 主な特徴

- パソコン、タブレット、スマートフォンのブラウザからアクセス可能
- アカウント登録やクラウドストレージが不要
- ローカルネットワーク内でファイルを閲覧、アップロード、ダウンロード
- スマートフォンとデスクトップに対応したWeb画面
- リスト表示とグリッド表示
- 任意のアクセス保護
- 一時的で直接的なファイル共有に適した設計
- 広告なし

## 使い方

1. Android端末と相手側の端末を、同じ信頼できるローカルネットワークに接続します。
2. SpeedShareWebでローカルサーバーを起動します。
3. 相手側の端末のブラウザで、アプリに表示されたローカルアドレスを開きます。
4. ファイルを閲覧、アップロード、ダウンロード、管理します。
5. 利用後はサーバーを停止します。

## プライバシーとセキュリティ

SpeedShareWebはローカルネットワーク内での動作を前提としており、アカウントやクラウドストレージを必要としません。

通常のHTTP通信を使用する場合、信頼できないネットワーク上の第三者に通信内容を確認される可能性があります。信頼できるネットワークのみで使用し、利用可能なアクセス保護を有効にして、転送後はサーバーを停止してください。

詳細は [PRIVACY.md](PRIVACY.md) と [SECURITY.md](SECURITY.md) をご確認ください。

## ダウンロード

テスト完了後、最初の署名済みAPKをGitHub Releasesで公開します。

非公式な第三者が再配布したAPKはインストールしないでください。

## ライセンス
本プロジェクトは GNU General Public License v3.0 のもとで公開されています。
詳細については LICENSE ファイルをご確認ください。