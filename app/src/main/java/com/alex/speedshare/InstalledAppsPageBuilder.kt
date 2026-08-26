package com.alex.speedshare

object InstalledAppsPageBuilder {
    fun build(
        apps: List<InstalledAppManager.InstalledApp>,
        language: ResolvedLanguage
    ): String {
        val text = Texts.forLanguage(language)
        val userCount = apps.count { !it.isSystemApp }
        val systemCount = apps.size - userCount
        val items = apps.joinToString("\n") { app ->
            val packageEncoded = urlEncode(app.packageName)
            val type = if (app.isSystemApp) "system" else "user"
            val format = if (app.isSplit) "APKS · ${app.splitApks.size + 1} APK" else "APK"
            """
                <article class="app" data-name="${escapeHtml((app.label + " " + app.packageName).lowercase())}" data-type="$type">
                  <img class="icon" src="/apps/icon?package=$packageEncoded" alt="" loading="lazy">
                  <div class="info">
                    <div class="name">${escapeHtml(app.label)}</div>
                    <div class="pkg">${escapeHtml(app.packageName)}</div>
                    <div class="meta">${escapeHtml(app.versionName.ifBlank { app.versionCode.toString() })} · ${formatBytes(app.totalBytes)} · $format</div>
                  </div>
                  <a class="download" href="/apps/download?package=$packageEncoded">${escapeHtml(text.download)}</a>
                </article>
            """.trimIndent()
        }

        return """
            <!doctype html>
            <html lang="${language.htmlLanguageTag}">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <meta name="color-scheme" content="light dark">
              <meta name="theme-color" content="#2563eb">
              <title>${escapeHtml(text.title)} - SpeedShareWeb</title>
              <style>
                :root{color-scheme:light dark;--bg:#f4f8fd;--panel:rgba(255,255,255,.92);--text:#142033;--muted:#66758b;--line:rgba(35,63,99,.14);--brand:#2563eb;--brand2:#0891b2;--chip:#e9f0fa;--shadow:0 10px 30px rgba(30,55,90,.09)}
                @media(prefers-color-scheme:dark){:root{--bg:#061426;--panel:rgba(13,27,46,.92);--text:#edf3fb;--muted:#a5b5ca;--line:rgba(186,205,232,.15);--brand:#8ab4ff;--brand2:#43d4e7;--chip:#17263b;--shadow:0 16px 40px rgba(0,0,0,.28)}}
                *{box-sizing:border-box}html,body{margin:0;min-height:100%;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans SC","Microsoft YaHei",sans-serif;background:var(--bg);color:var(--text)}
                body{background-image:radial-gradient(circle at 10% 0,rgba(37,99,235,.14),transparent 30%),radial-gradient(circle at 90% 6%,rgba(8,145,178,.11),transparent 30%)}
                a{-webkit-tap-highlight-color:transparent}.wrap{max-width:1100px;margin:0 auto;padding:12px 12px 64px}.header{position:sticky;top:8px;z-index:20;padding:14px;background:var(--panel);border:1px solid var(--line);border-radius:18px;box-shadow:var(--shadow);backdrop-filter:blur(18px)}
                .top{display:flex;align-items:flex-start;gap:12px}.back{text-decoration:none;color:var(--text);background:var(--chip);border:1px solid var(--line);border-radius:10px;padding:8px 10px;font-weight:800}.heading{flex:1;min-width:0}h1{margin:0;font-size:23px;background:linear-gradient(110deg,var(--brand),var(--brand2));-webkit-background-clip:text;background-clip:text;color:transparent}.sub{font-size:12px;color:var(--muted);margin-top:4px;line-height:1.5}
                .controls{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;margin-top:12px}.search{width:100%;min-width:0;border:1px solid var(--line);background:var(--chip);color:var(--text);border-radius:11px;padding:10px 12px;outline:none}.search:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(37,99,235,.13)}.filters{display:flex;gap:6px;flex-wrap:wrap}.filter{border:1px solid var(--line);background:var(--chip);color:var(--text);border-radius:999px;padding:8px 10px;cursor:pointer;font-weight:750}.filter.active{background:linear-gradient(120deg,var(--brand),var(--brand2));border-color:transparent;color:#fff}
                .note{margin:11px 0;padding:10px 12px;border:1px solid var(--line);border-radius:12px;background:var(--panel);font-size:12px;color:var(--muted);line-height:1.55}.list{display:grid;gap:8px}.app{display:grid;grid-template-columns:52px minmax(0,1fr) auto;gap:11px;align-items:center;padding:11px;background:var(--panel);border:1px solid var(--line);border-radius:15px;box-shadow:0 5px 18px rgba(30,55,90,.05)}.icon{width:52px;height:52px;border-radius:13px;object-fit:contain;background:var(--chip)}.info{min-width:0}.name{font-weight:850;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.pkg,.meta{font-size:11px;color:var(--muted);margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.download{text-decoration:none;white-space:nowrap;background:linear-gradient(120deg,var(--brand),var(--brand2));color:#fff;border-radius:10px;padding:9px 11px;font-size:12px;font-weight:850}.empty{display:none;text-align:center;color:var(--muted);padding:42px 12px}
                @media(max-width:650px){.wrap{padding:8px 8px 50px}.header{top:5px;border-radius:15px}.controls{grid-template-columns:1fr}.app{grid-template-columns:46px minmax(0,1fr);gap:9px}.icon{width:46px;height:46px}.download{grid-column:1/-1;text-align:center}.pkg{font-size:10px}}
                @media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important;transition:none!important}}
              </style>
            </head>
            <body>
              <main class="wrap">
                <header class="header">
                  <div class="top">
                    <a class="back" href="/">←</a>
                    <div class="heading"><h1>${escapeHtml(text.title)}</h1><div class="sub">${escapeHtml(text.subtitle(apps.size, userCount, systemCount))}</div></div>
                  </div>
                  <div class="controls">
                    <input id="search" class="search" type="search" placeholder="${escapeHtml(text.search)}" oninput="applyFilter()">
                    <div class="filters">
                      <button class="filter active" data-filter="all" onclick="setFilter('all',this)">${escapeHtml(text.all)}</button>
                      <button class="filter" data-filter="user" onclick="setFilter('user',this)">${escapeHtml(text.user)}</button>
                      <button class="filter" data-filter="system" onclick="setFilter('system',this)">${escapeHtml(text.system)}</button>
                    </div>
                  </div>
                </header>
                <div class="note">${escapeHtml(text.note)}</div>
                <section id="list" class="list">$items</section>
                <div id="empty" class="empty">${escapeHtml(text.empty)}</div>
              </main>
              <script>
                let currentFilter='all';
                function setFilter(value,button){currentFilter=value;document.querySelectorAll('.filter').forEach(function(item){item.classList.toggle('active',item===button);});applyFilter();}
                function applyFilter(){
                  const query=document.getElementById('search').value.trim().toLowerCase();let visible=0;
                  document.querySelectorAll('.app').forEach(function(item){
                    const typeOk=currentFilter==='all'||item.dataset.type===currentFilter;
                    const searchOk=!query||(item.dataset.name||'').includes(query);
                    const show=typeOk&&searchOk;item.style.display=show?'grid':'none';if(show)visible++;
                  });
                  document.getElementById('empty').style.display=visible===0?'block':'none';
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private data class Texts(
        val title: String,
        val search: String,
        val all: String,
        val user: String,
        val system: String,
        val download: String,
        val note: String,
        val empty: String,
        val subtitle: (Int, Int, Int) -> String
    ) {
        companion object {
            fun forLanguage(language: ResolvedLanguage): Texts = when (language) {
                ResolvedLanguage.ZH_CN -> Texts(
                    title = "已安装应用",
                    search = "搜索应用名称或包名",
                    all = "全部",
                    user = "用户应用",
                    system = "系统应用",
                    download = "下载",
                    note = "单 APK 应用直接导出 .apk；Split 应用导出完整 .apks。导出只包含应用安装文件，不包含账号、聊天记录、存档或其他应用数据。",
                    empty = "没有找到应用",
                    subtitle = { total, users, systems -> "共 $total 个 · 用户应用 $users · 系统应用 $systems" }
                )
                ResolvedLanguage.JA -> Texts(
                    title = "インストール済みアプリ",
                    search = "アプリ名またはパッケージ名を検索",
                    all = "すべて",
                    user = "ユーザーアプリ",
                    system = "システムアプリ",
                    download = "ダウンロード",
                    note = "単一 APK は .apk、Split APK は完全な .apks として書き出します。アカウント、チャット履歴、セーブデータなどのアプリデータは含まれません。",
                    empty = "アプリが見つかりません",
                    subtitle = { total, users, systems -> "合計 $total · ユーザー $users · システム $systems" }
                )
                ResolvedLanguage.EN -> Texts(
                    title = "Installed apps",
                    search = "Search app name or package",
                    all = "All",
                    user = "User apps",
                    system = "System apps",
                    download = "Download",
                    note = "Single-APK apps are exported as .apk; split apps are exported as complete .apks archives. App accounts, chats, saves, and other private app data are not included.",
                    empty = "No apps found",
                    subtitle = { total, users, systems -> "$total total · $users user · $systems system" }
                )
            }
        }
    }
}
