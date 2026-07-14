package com.alex.speedshare

object WebPageBuilder {

    fun buildSelectedPage(
        items: List<WebItem>,
        language: ResolvedLanguage,
        clipboardSyncEnabled: Boolean,
        pageVersion: String,
        accessProtected: Boolean = false
    ): String {
        val tr = Localization.translator(language)
        val subtitle = tr.text("web_selected_sub", items.size)
        return buildPage(
            title = tr.text("web_selected_title"),
            subtitle = subtitle,
            breadcrumbs = "",
            items = items,
            uploadPanel = "",
            currentRelativePath = "",
            directoryMode = false,
            remoteManagementEnabled = false,
            clipboardSyncEnabled = clipboardSyncEnabled,
            language = language,
            pageVersion = pageVersion,
            accessProtected = accessProtected
        )
    }

    fun buildDirectoryPage(
        displayPath: String,
        relativePath: String,
        items: List<WebItem>,
        uploadEnabled: Boolean,
        remoteManagementEnabled: Boolean,
        clipboardSyncEnabled: Boolean,
        deleteToTrashByDefault: Boolean,
        language: ResolvedLanguage,
        pageVersion: String,
        accessProtected: Boolean = false
    ): String {
        val tr = Localization.translator(language)
        return buildPage(
            title = tr.text("web_phone_title"),
            subtitle = "$displayPath · ${tr.text("web_items", items.size)}",
            breadcrumbs = buildBreadcrumbs(relativePath, tr),
            items = items,
            uploadPanel = if (uploadEnabled) buildUploadPanel(relativePath, tr) else "",
            currentRelativePath = relativePath,
            directoryMode = true,
            remoteManagementEnabled = remoteManagementEnabled,
            clipboardSyncEnabled = clipboardSyncEnabled,
            language = language,
            pageVersion = pageVersion,
            accessProtected = accessProtected
        )
    }

    fun buildLoginPage(
        language: ResolvedLanguage,
        next: String,
        invalidPassword: Boolean
    ): String {
        val tr = Localization.translator(language)
        val error = if (invalidPassword) {
            "<div class=\"error\" role=\"alert\">${escapeHtml(tr.text("web_login_invalid"))}</div>"
        } else {
            ""
        }
        return """
            <!doctype html>
            <html lang="${language.htmlLanguageTag}">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <meta name="color-scheme" content="light dark">
              <title>${escapeHtml(tr.text("web_login_title"))} - SpeedShareWeb</title>
              <style>
                :root{color-scheme:light dark;--bg:#f3f7fd;--panel:rgba(255,255,255,.9);--text:#142033;--muted:#68758a;--line:rgba(52,76,112,.18);--brand:#2563eb;--brand2:#0891b2;--accent:#7357c7;--danger:#b42318;--dangerBg:rgba(180,35,24,.1)}
                @media(prefers-color-scheme:dark){:root{--bg:#061426;--panel:rgba(13,27,46,.92);--text:#edf3fb;--muted:#a7b5c9;--line:rgba(157,181,216,.18);--brand:#7eb3ff;--brand2:#43d4e7;--accent:#bea7ff;--danger:#ff9b91;--dangerBg:rgba(255,107,94,.12)}}
                *{box-sizing:border-box}body{margin:0;min-height:100dvh;display:grid;place-items:center;padding:20px;background:radial-gradient(circle at 18% 8%,rgba(37,99,235,.22),transparent 38%),radial-gradient(circle at 88% 88%,rgba(8,145,178,.18),transparent 40%),radial-gradient(circle at 80% 12%,rgba(115,87,199,.1),transparent 32%),var(--bg);color:var(--text);font-family:Inter,system-ui,-apple-system,"Segoe UI",sans-serif}
                .card{width:min(100%,410px);padding:26px;border:1px solid var(--line);border-radius:22px;background:var(--panel);box-shadow:0 24px 70px rgba(20,31,64,.18);backdrop-filter:blur(18px);animation:loginCardIn .38s cubic-bezier(.2,.8,.2,1) both}
                .mark{width:46px;height:46px;display:grid;place-items:center;border-radius:14px;background:linear-gradient(135deg,var(--brand),var(--brand2));color:#fff;font-weight:900;font-size:20px;box-shadow:0 10px 24px rgba(37,99,235,.24)}
                h1{font-size:24px;line-height:1.2;margin:18px 0 7px}.subtitle,.note{color:var(--muted);line-height:1.6}.subtitle{font-size:14px;margin-bottom:19px}.note{font-size:12px;margin-top:14px}
                label{display:block;font-size:13px;font-weight:750;margin-bottom:7px}.field{width:100%;height:48px;border:1px solid var(--line);border-radius:12px;background:rgba(127,140,170,.08);color:var(--text);padding:0 13px;font:inherit;outline:none}.field:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(37,99,235,.14)}
                button{width:100%;height:47px;border:0;border-radius:12px;margin-top:12px;background:linear-gradient(120deg,var(--brand),var(--brand2));color:#fff;font:inherit;font-weight:800;cursor:pointer;box-shadow:0 10px 24px rgba(37,99,235,.2)}button:active{transform:scale(.985)}
                .error{margin:0 0 14px;padding:10px 12px;border-radius:11px;background:var(--dangerBg);color:var(--danger);font-size:13px;font-weight:700}
                @keyframes loginCardIn{from{opacity:0;transform:translateY(14px) scale(.985)}to{opacity:1;transform:none}}
                @media(max-width:480px){body{padding:13px}.card{padding:22px;border-radius:19px}}
                @media(prefers-reduced-motion:reduce){*,*::before,*::after{animation-duration:.01ms!important;transition-duration:.01ms!important}}
              </style>
            </head>
            <body>
              <main class="card">
                <div class="mark" aria-hidden="true">S</div>
                <h1>${escapeHtml(tr.text("web_login_title"))}</h1>
                <div class="subtitle">${escapeHtml(tr.text("web_login_subtitle"))}</div>
                $error
                <form method="post" action="/login?next=${urlEncode(next)}">
                  <label for="password">${escapeHtml(tr.text("web_login_password"))}</label>
                  <input id="password" class="field" name="password" type="password" minlength="4" maxlength="64" autocomplete="current-password" required autofocus>
                  <button type="submit">${escapeHtml(tr.text("web_login_submit"))}</button>
                </form>
                <div class="note">${escapeHtml(tr.text("web_login_note"))}</div>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildPage(
        title: String,
        subtitle: String,
        breadcrumbs: String,
        items: List<WebItem>,
        uploadPanel: String,
        currentRelativePath: String,
        directoryMode: Boolean,
        remoteManagementEnabled: Boolean,
        clipboardSyncEnabled: Boolean,
        language: ResolvedLanguage,
        pageVersion: String,
        accessProtected: Boolean
    ): String {
        val tr = Localization.translator(language)
        val itemsHtml = if (items.isEmpty()) {
            "<div class=\"empty\">${escapeHtml(tr.text("web_empty"))}</div>"
        } else {
            items.joinToString(separator = "\n") {
                buildItem(it, allowSelection = true, remoteManagementEnabled = remoteManagementEnabled, tr = tr)
            }
        }

        val managementPanel = if (directoryMode) {
            buildManagementPanel(currentRelativePath, remoteManagementEnabled, tr)
        } else if (items.isNotEmpty()) {
            buildSelectedDownloadPanel(tr)
        } else {
            ""
        }
        val clipboardPanel = if (clipboardSyncEnabled) buildClipboardPanel(tr) else ""
        val logoutControl = if (accessProtected) {
            "<form class=\"logoutForm\" method=\"post\" action=\"/logout\"><button class=\"themeToggle\" type=\"submit\">${escapeHtml(tr.text("web_logout"))}</button></form>"
        } else {
            ""
        }
        val jsTranslations = Localization.table(language)
            .filterKeys { it.startsWith("web_") || it == "speed_upload" || it == "speed_download" }
            .entries
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "\"${escapeJavaScript(key)}\":\"${escapeJavaScript(value)}\""
            }

        return """
            <!doctype html>
            <html lang="${language.htmlLanguageTag}">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover">
              <meta name="theme-color" content="#2563eb">
              <title>${escapeHtml(title)} - SpeedShareWeb</title>
              <style>
                :root{color-scheme:light dark;--bg:#f5f8fc;--bg2:#eaf2fc;--panel:rgba(255,255,255,.92);--panelSolid:#fff;--panel2:#ebf1f8;--text:#142033;--muted:#647287;--line:rgba(36,63,98,.13);--brand:#2563eb;--brand2:#0891b2;--accent:#7357c7;--success:#0b9f6e;--danger:#d04444;--shadow:0 8px 24px rgba(31,57,91,.08);--shadowHover:0 14px 34px rgba(31,57,91,.14);--radius:14px}
                @media(prefers-color-scheme:dark){:root{--bg:#061426;--bg2:#0d1b2e;--panel:rgba(13,27,46,.9);--panelSolid:#0d1b2e;--panel2:#17263b;--text:#e8eef8;--muted:#a7b5c9;--line:rgba(186,205,232,.14);--brand:#8ab4ff;--brand2:#43d4e7;--accent:#bea7ff;--success:#62ddb0;--danger:#ff8585;--shadow:0 12px 32px rgba(0,0,0,.26);--shadowHover:0 18px 42px rgba(0,0,0,.36)}}
                html[data-theme="light"]{--bg:#f5f8fc;--bg2:#eaf2fc;--panel:rgba(255,255,255,.92);--panelSolid:#fff;--panel2:#ebf1f8;--text:#142033;--muted:#647287;--line:rgba(36,63,98,.13);--brand:#2563eb;--brand2:#0891b2;--accent:#7357c7;--success:#0b9f6e;--danger:#d04444;--shadow:0 8px 24px rgba(31,57,91,.08);--shadowHover:0 14px 34px rgba(31,57,91,.14)}
                html[data-theme="dark"]{--bg:#061426;--bg2:#0d1b2e;--panel:rgba(13,27,46,.9);--panelSolid:#0d1b2e;--panel2:#17263b;--text:#e8eef8;--muted:#a7b5c9;--line:rgba(186,205,232,.14);--brand:#8ab4ff;--brand2:#43d4e7;--accent:#bea7ff;--success:#62ddb0;--danger:#ff8585;--shadow:0 12px 32px rgba(0,0,0,.26);--shadowHover:0 18px 42px rgba(0,0,0,.36)}
                *{box-sizing:border-box}
                html{scroll-behavior:smooth}
                html,body{margin:0;min-height:100%;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans SC","Microsoft YaHei",sans-serif}
                body{background-image:radial-gradient(circle at 12% 0,rgba(37,99,235,.13),transparent 30%),radial-gradient(circle at 88% 6%,rgba(8,145,178,.1),transparent 28%),linear-gradient(180deg,var(--bg2),var(--bg) 310px);background-attachment:fixed}
                button,input,select{font:inherit}
                button,a{-webkit-tap-highlight-color:transparent}
                a{color:inherit}
                .wrap{max-width:1380px;margin:0 auto;padding:10px 12px 72px}
                .header{position:sticky;top:8px;z-index:30;background:var(--panel);backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);border:1px solid var(--line);border-radius:16px;padding:11px 13px;box-shadow:var(--shadow)}
                .titleRow{display:flex;align-items:center;justify-content:space-between;gap:12px}
                h1{font-size:22px;line-height:1.15;margin:0 0 3px;font-weight:850;letter-spacing:0;background:linear-gradient(110deg,var(--brand),var(--brand2));-webkit-background-clip:text;background-clip:text;color:transparent}
                .subtitle{font-size:12px;color:var(--muted);overflow-wrap:anywhere}
                .badge{flex:0 0 auto;padding:6px 9px;border-radius:999px;background:var(--panel2);border:1px solid var(--line);font-size:11px;color:var(--muted);white-space:nowrap}
                .headerActions{display:flex;align-items:center;gap:7px;flex:0 0 auto}.logoutForm{margin:0}.themeToggle{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:999px;padding:6px 9px;font-size:11px;cursor:pointer}.settingsToggle{display:flex;align-items:center;gap:5px}.settingsIcon{display:grid;place-items:center}.settingsIcon svg{width:14px;height:14px}
                .syncDot{display:inline-block;width:7px;height:7px;border-radius:50%;background:var(--success);margin-right:6px;box-shadow:0 0 0 4px rgba(11,159,110,.1);animation:syncPulse 1.8s ease-in-out infinite}
                .breadcrumbs{display:flex;align-items:center;gap:5px;flex-wrap:wrap;margin:9px 0 0;font-size:12px}
                .breadcrumbs a{text-decoration:none;color:var(--brand);background:var(--panel2);border:1px solid var(--line);padding:6px 9px;border-radius:9px;transition:.16s}
                .breadcrumbs a:hover{transform:translateY(-1px);border-color:var(--brand)}

                .livePanel{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:6px;margin-top:9px;padding-top:9px;border-top:1px solid var(--line)}
                .liveCard{background:var(--panel2);border:1px solid transparent;border-radius:9px;padding:6px 8px;min-width:0}
                .liveLabel{font-size:10px;color:var(--muted);margin-bottom:2px}
                .liveValue{font-size:14px;font-weight:850;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                .liveTransfers{display:none;grid-column:1/-1;font-size:11px;color:var(--muted);line-height:1.55;overflow-wrap:anywhere;padding:7px 10px}.liveTransfers.active{display:block}

                .connectionBanner{position:fixed;left:50%;bottom:16px;z-index:240;transform:translate(-50%,130%);opacity:0;transition:.22s;background:#151a2d;color:#fff;border-radius:12px;padding:10px 14px;box-shadow:0 14px 42px rgba(0,0,0,.34);font-size:12px;max-width:min(92vw,620px);text-align:center;pointer-events:none}
                .connectionBanner.show{transform:translate(-50%,0);opacity:1}
                .connectionBanner.error{background:#9f3030}.connectionBanner.ok{background:#087d58}

                .toolbar{display:grid;grid-template-columns:minmax(170px,1fr) auto auto;gap:7px;margin:9px 0}
                .control{min-height:38px;border:1px solid var(--line);background:var(--panel);color:var(--text);border-radius:10px;padding:0 11px;outline:none;box-shadow:0 4px 14px rgba(43,54,99,.04)}
                .control:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(37,99,235,.13)}
                .searchBox{position:relative;min-width:0}.searchBox .control{width:100%;padding-right:38px}.clearSearch{position:absolute;right:6px;top:50%;transform:translateY(-50%);width:27px;height:27px;border:0;border-radius:8px;background:transparent;color:var(--muted);cursor:pointer;font-size:16px}.clearSearch:hover{background:var(--panel2);color:var(--text)}
                .viewButtons{display:flex;border:1px solid var(--line);border-radius:11px;overflow:hidden;background:var(--panel)}
                .viewButtons button{border:0;background:transparent;color:var(--muted);padding:0 12px;cursor:pointer;transition:.15s}
                .viewButtons button.active{background:linear-gradient(120deg,var(--brand),var(--brand2));color:#fff}

                .uploadBox,.managementBox{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:10px;margin:9px 0;box-shadow:var(--shadow)}
                .utilityGrid{display:grid;gap:9px;margin:9px 0}.utilityGrid>.uploadBox,.utilityGrid>.managementBox{margin:0;min-width:0}
                .clipboardText{width:100%;min-height:84px;resize:vertical;border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:12px;padding:9px 10px;outline:none;margin-top:8px}
                .clipboardText:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(37,99,235,.13)}
                .clipboardOutput{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--panel2);border:1px solid var(--line);border-radius:12px;padding:9px 10px;margin-top:8px;min-height:42px;font-size:12px;color:var(--text)}
                .dropZone{min-height:142px;display:flex;flex-direction:column;justify-content:center;border:2px dashed var(--line);border-radius:12px;padding:16px;text-align:center;transition:.18s;background:var(--panel2)}
                .dropZone.drag{border-color:var(--brand2);background:linear-gradient(135deg,rgba(37,99,235,.12),rgba(8,145,178,.13));box-shadow:0 0 0 4px rgba(37,99,235,.08),0 14px 34px rgba(8,145,178,.13);transform:scale(.995);animation:dropPulse .85s ease-in-out infinite alternate}
                .dropPrompt{font-weight:800;color:var(--text);margin-bottom:2px}.dropZone.drag .dropPrompt{color:var(--brand)}
                .uploadTitle,.managementTitle{font-weight:800;margin-bottom:3px}
                .uploadHint,.uploadStatus,.readOnlyHint{font-size:12px;color:var(--muted);overflow-wrap:anywhere}.uploadStatus{min-height:20px;font-weight:700}.uploadStatus.success{color:var(--success)}.uploadStatus.error{color:var(--danger)}
                .uploadProgress{height:9px;background:rgba(120,130,160,.16);border-radius:999px;overflow:hidden;margin-top:10px;border:1px solid var(--line)}.uploadProgress span{position:relative;display:block;height:100%;width:0;background:linear-gradient(90deg,var(--brand),var(--brand2),var(--accent));transition:width .18s ease;overflow:hidden}.uploadProgress span::after{content:"";position:absolute;inset:0;background:linear-gradient(100deg,transparent 15%,rgba(255,255,255,.55) 50%,transparent 85%);transform:translateX(-100%);animation:progressShine 1.45s ease-in-out infinite}
                .uploadQueue{display:grid;gap:6px;margin-top:8px;text-align:left;max-height:290px;overflow:auto;overscroll-behavior:contain;padding-right:2px}.uploadQueue:empty{display:none}.uploadQueueItem{display:grid;grid-template-columns:1fr auto;gap:8px;align-items:center;background:rgba(120,130,160,.08);border:1px solid var(--line);border-radius:10px;padding:7px 9px;font-size:12px}.uploadQueueName{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--text)}.uploadQueueState{color:var(--muted);font-weight:750;display:flex;align-items:center;gap:6px}.uploadQueueRetry{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:7px;padding:4px 6px;cursor:pointer;font-size:11px}.uploadQueueItem.done .uploadQueueState{color:var(--success)}.uploadQueueItem.failed .uploadQueueState,.uploadQueueItem.cancelled .uploadQueueState{color:var(--danger)}
                .uploadActions,.managementButtons{display:flex;gap:7px;margin-top:9px;flex-wrap:wrap}
                .primary,.secondary{border:0;border-radius:9px;padding:9px 12px;font-weight:750;cursor:pointer;transition:.15s}
                .primary{background:linear-gradient(120deg,var(--brand),var(--brand2));color:#fff;box-shadow:0 7px 18px rgba(37,99,235,.18)}
                .secondary{background:var(--panel2);color:var(--text);border:1px solid var(--line)}
                .primary:active,.secondary:active,.actions a:active,.actions button:active{transform:scale(.97)}
                .managementTop{display:flex;align-items:center;justify-content:space-between;gap:10px}
                .selectionBar{position:sticky;bottom:10px;z-index:80;display:flex;align-items:center;gap:6px;flex-wrap:wrap;background:rgba(21,26,45,.94);color:#fff;border-radius:14px;padding:9px 10px;margin-top:9px;box-shadow:0 16px 44px rgba(0,0,0,.27);backdrop-filter:blur(14px)}
                .selectionBar.hidden{display:none}.selectionBar strong{margin-right:auto;font-size:12px}.selectionBar button{border:0;border-radius:9px;padding:7px 9px;background:rgba(255,255,255,.13);color:#fff;cursor:pointer;font-size:11px}.selectionBar .danger{background:#b83b3b}.selectionBar .dangerGhost{border:1px solid rgba(255,121,121,.55);color:#ffb2b2;background:transparent}

                .items{display:grid;gap:9px}
                .items.grid{grid-template-columns:repeat(auto-fill,minmax(156px,1fr))}
                .card{position:relative;background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);overflow:hidden;box-shadow:var(--shadow);min-width:0;transition:transform .17s ease,box-shadow .17s ease,border-color .17s ease;content-visibility:auto;contain-intrinsic-size:180px}
                .card:hover{transform:translateY(-3px);box-shadow:var(--shadowHover);border-color:rgba(37,99,235,.28)}
                .card:focus-visible{outline:3px solid var(--brand);outline-offset:2px}
                .card.selected{outline:2px solid var(--brand);outline-offset:-2px}
                .selectBox{position:absolute;top:8px;left:8px;z-index:12;width:27px;height:27px;border-radius:9px;background:rgba(15,20,35,.58);display:grid;place-items:center;color:#fff;backdrop-filter:blur(8px);cursor:pointer;border:1px solid rgba(255,255,255,.22)}
                .selectBox input{position:absolute;opacity:0}.selectBox span{opacity:.3;font-weight:900}.selectBox input:checked+span{opacity:1;color:#94f3ca}
                .folder-card{text-decoration:none;display:flex;align-items:center;gap:10px;padding:12px;min-height:72px}
                .folderIcon{width:42px;height:42px;display:grid;place-items:center;font-size:28px;background:linear-gradient(145deg,rgba(37,99,235,.12),rgba(15,118,110,.12));border-radius:12px;flex:0 0 auto}
                .folderInfo{min-width:0;flex:1}.folderArrow{font-size:21px;color:var(--muted);flex:0 0 auto}
                .items.grid .folder-card{display:grid;grid-template-columns:minmax(0,1fr) auto;grid-template-rows:auto auto;align-items:center;gap:8px;padding:10px;min-height:160px}
                .items.grid .folderIcon{grid-column:1/-1;width:100%;height:88px;font-size:46px;border-radius:13px}
                .items.grid .folderInfo{grid-column:1;grid-row:2;min-width:0}.items.grid .folder-card .miniManage{grid-column:2;grid-row:2;align-self:center}.items.grid .folderArrow{display:none}
                .thumb{position:relative;width:100%;aspect-ratio:4/3;background:linear-gradient(145deg,var(--panel2),rgba(37,99,235,.08));overflow:hidden;display:grid;place-items:center;cursor:pointer}
                .thumb.loading::before{content:"";position:absolute;inset:0;z-index:2;background:linear-gradient(100deg,transparent 20%,rgba(255,255,255,.2) 45%,transparent 70%);transform:translateX(-100%);animation:thumbShimmer 1.15s infinite}
                .thumb img{width:100%;height:100%;object-fit:cover;display:block;animation:thumbIn .24s ease both}
                @keyframes thumbIn{from{opacity:0;transform:scale(1.025)}to{opacity:1;transform:scale(1)}}
                @keyframes thumbShimmer{to{transform:translateX(100%)}}
                .fallback{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:42px;background:var(--panel2)}
                .mediaBadge{position:absolute;right:7px;bottom:7px;background:rgba(15,20,35,.72);color:#fff;border-radius:7px;padding:3px 6px;font-size:10px;backdrop-filter:blur(6px)}
                .info{padding:9px 10px 10px;min-width:0}.items.grid .file-card .info{display:block;min-height:96px}
                .name{font-size:13px;font-weight:780;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;line-height:1.35}
                .items.grid .folderInfo .name,.items.grid .file-card .name{display:block;white-space:normal;max-height:2.7em;overflow:hidden;overflow-wrap:anywhere}
                .sub{font-size:10.5px;color:var(--muted);margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                .actions{display:flex;gap:5px;margin-top:7px}
                .actions a,.actions button{flex:1;text-align:center;text-decoration:none;border:1px solid var(--line);border-radius:8px;background:var(--panel2);color:var(--text);padding:6px 5px;font-size:10.5px;font-weight:750;cursor:pointer;transition:.14s}
                .actions .download{background:linear-gradient(120deg,var(--brand),var(--brand2));border-color:transparent;color:#fff}
                .miniManage{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:8px;padding:6px 8px;font-size:10.5px;font-weight:700;cursor:pointer}
                .empty{grid-column:1/-1;text-align:center;padding:46px 16px;color:var(--muted);background:var(--panel);border:1px dashed var(--line);border-radius:17px}

                .items.list{display:block}.items.list .card{margin-bottom:7px}.items.list .file-card{display:grid;grid-template-columns:70px minmax(0,1fr)}.items.list .thumb{height:70px;aspect-ratio:auto}.items.list .info{padding:8px 10px}.items.list .actions{justify-content:flex-end}.items.list .actions a,.items.list .actions button{flex:0 0 auto;min-width:58px}.items.list .folder-card{min-height:64px}.items.list .folderIcon{width:40px;height:40px}

                .modal{position:fixed;inset:0;z-index:150;background:rgba(3,6,15,.8);display:none;align-items:center;justify-content:center;padding:18px;backdrop-filter:blur(10px)}.modal.open{display:flex;animation:modalFade .18s ease-out}.modal.open .modalPanel{animation:panelIn .22s cubic-bezier(.2,.8,.2,1)}
                .modalPanel{width:min(1050px,100%);height:min(90vh,840px);background:var(--panelSolid);border:1px solid var(--line);border-radius:18px;overflow:hidden;display:grid;grid-template-rows:auto minmax(0,1fr) auto;box-shadow:0 30px 100px rgba(0,0,0,.5)}
                .modalHeader,.modalFooter{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid var(--line)}.modalFooter{border-bottom:0;border-top:1px solid var(--line)}
                .modalTitle{flex:1;min-width:0;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.iconBtn{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:9px;padding:7px 9px;cursor:pointer;text-decoration:none;font-size:12px}
                .viewer{min-height:0;display:flex;align-items:center;justify-content:center;background:#05070d;overflow:auto}.viewer img,.viewer video{max-width:100%;max-height:100%;object-fit:contain}.viewer audio{width:min(720px,92%)}.viewer iframe{width:100%;height:100%;border:0;background:#fff}.details{flex:1;min-width:0;font-size:11px;color:var(--muted);overflow-wrap:anywhere}.emptyAction{display:inline-block;margin-top:12px;text-decoration:none;border-radius:9px;padding:8px 11px;background:linear-gradient(120deg,var(--brand),var(--brand2));color:#fff;font-weight:800}

                .managerModal,.dialogModal{position:fixed;inset:0;z-index:180;background:rgba(3,6,15,.76);display:none;align-items:center;justify-content:center;padding:18px;backdrop-filter:blur(10px)}.managerModal.open,.dialogModal.open{display:flex;animation:modalFade .18s ease-out}.managerModal.open .managerPanel,.dialogModal.open .dialogPanel{animation:panelIn .22s cubic-bezier(.2,.8,.2,1)}
                .managerPanel{width:min(880px,100%);max-height:88vh;background:var(--panelSolid);border:1px solid var(--line);border-radius:18px;overflow:hidden;display:grid;grid-template-rows:auto minmax(0,1fr);box-shadow:0 28px 90px rgba(0,0,0,.45)}
                .managerHeader{display:flex;align-items:center;gap:10px;padding:11px 12px;border-bottom:1px solid var(--line)}.managerBody{overflow:auto;padding:12px}
                .managerRow{display:flex;align-items:center;gap:9px;padding:9px 0;border-bottom:1px solid var(--line)}.managerRow:last-child{border-bottom:0}.managerRowMain{flex:1;min-width:0}.managerName{font-weight:760;overflow-wrap:anywhere}.managerMeta{font-size:11px;color:var(--muted);margin-top:2px;overflow-wrap:anywhere}.managerActions{display:flex;gap:6px;flex-wrap:wrap}.managerActions button{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:8px;padding:6px 8px;cursor:pointer;font-size:11px}
                .dialogPanel{width:min(460px,100%);background:var(--panelSolid);border:1px solid var(--line);border-radius:18px;box-shadow:0 28px 90px rgba(0,0,0,.45);overflow:hidden}.dialogBody{padding:14px}.dialogTitle{font-size:16px;font-weight:850;margin-bottom:5px}.dialogMessage{font-size:12px;color:var(--muted);line-height:1.55;white-space:pre-wrap;overflow-wrap:anywhere}.dialogField{width:100%;min-height:40px;margin-top:12px;border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:10px;padding:0 11px;outline:none}.dialogField:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(37,99,235,.13)}.dialogActions{display:flex;justify-content:flex-end;gap:8px;padding:10px 14px;border-top:1px solid var(--line)}.dialogActions button{min-width:88px}.dialogActions .danger{background:var(--danger);color:#fff}
                .progressTrack{height:6px;background:var(--panel2);border-radius:999px;overflow:hidden;margin-top:6px}.progressBar{height:100%;background:linear-gradient(90deg,var(--brand),var(--brand2));width:0%;transition:width .18s}
                .contextMenu{position:fixed;z-index:260;display:none;width:min(245px,calc(100vw - 16px));padding:6px;background:var(--panelSolid);border:1px solid var(--line);border-radius:13px;box-shadow:0 22px 60px rgba(0,0,0,.3);backdrop-filter:blur(18px);transform-origin:top left}.contextMenu.open{display:block;animation:contextMenuIn .14s ease-out}.contextMenu button{width:100%;display:flex;align-items:center;gap:9px;border:0;background:transparent;color:var(--text);border-radius:9px;padding:9px 10px;text-align:left;cursor:pointer;font-size:12px}.contextMenu button:hover,.contextMenu button:focus-visible{background:var(--panel2);outline:0}.contextMenu .contextIcon{width:18px;display:grid;place-items:center;color:var(--muted)}.contextIcon svg{width:17px;height:17px}.contextMenu .danger{color:var(--danger)}.contextSeparator{height:1px;background:var(--line);margin:5px 3px}.contextMore.hidden{display:none}.contextFileOnly.hidden,.contextFolderOnly.hidden{display:none}
                @keyframes contextMenuIn{from{opacity:0;transform:scale(.96) translateY(-3px)}to{opacity:1;transform:none}}
                @keyframes syncPulse{50%{box-shadow:0 0 0 7px rgba(11,159,110,0)}}
                @keyframes dropPulse{to{box-shadow:0 0 0 5px rgba(37,99,235,.1),0 18px 42px rgba(8,145,178,.2)}}
                @keyframes progressShine{55%,100%{transform:translateX(120%)}}
                @keyframes modalFade{from{opacity:0}to{opacity:1}}
                @keyframes panelIn{from{opacity:0;transform:translateY(10px) scale(.985)}to{opacity:1;transform:none}}
                .hidden{display:none!important}

                @media(min-width:980px){.utilityGrid{grid-template-columns:repeat(2,minmax(0,1fr));align-items:start}.header{padding:13px 15px;border-radius:19px}.livePanel{gap:8px}.liveCard{padding:8px 10px}.uploadBox,.managementBox{border-radius:17px;padding:13px}}
                @media(max-width:760px){.wrap{padding:8px 8px 64px}.header{top:5px;padding:10px 11px;border-radius:15px}.badge{display:none}h1{font-size:20px}.livePanel{grid-template-columns:repeat(5,minmax(0,1fr));gap:4px}.liveCard{padding:5px}.liveLabel{font-size:9px}.liveValue{font-size:11px}.toolbar{grid-template-columns:1fr auto}.toolbar select{grid-column:1/-1;grid-row:2}.items.grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.items.grid .folder-card{min-height:145px}.items.grid .folderIcon{height:76px}.managementTop{align-items:flex-start;flex-direction:column}.managementButtons{margin-top:0}.selectionBar{bottom:7px}.modal,.managerModal,.dialogModal{padding:0}.modalPanel,.managerPanel{width:100%;height:100%;max-height:none;border-radius:0}.dialogPanel{width:100%;max-height:100%;border-radius:0;margin-top:auto}.actions{gap:4px}.actions a,.actions button{padding:6px 3px}.liveTransfers{font-size:10.5px}.contextMenu{left:8px!important;right:8px;top:auto!important;bottom:max(8px,env(safe-area-inset-bottom));width:auto;max-height:min(76vh,620px);overflow:auto;border-radius:19px;padding:8px;transform-origin:bottom center}.contextMenu.open{animation:contextSheetIn .18s ease-out}.contextMenu button{min-height:43px;font-size:13px}}
                @keyframes contextSheetIn{from{opacity:0;transform:translateY(18px)}to{opacity:1;transform:none}}
                @media(max-width:390px){.items.grid{grid-template-columns:repeat(2,minmax(0,1fr))}.info{padding:8px}.name{font-size:12px}.sub{font-size:10px}.actions a,.actions button{font-size:10px}.folderIcon{font-size:26px}.settingsText{display:none}.settingsToggle{padding-inline:8px}}
                @media(hover:none){.card:hover{transform:none;box-shadow:var(--shadow)}}
                @media(prefers-reduced-motion:reduce){*,*::before,*::after{scroll-behavior:auto!important;animation-duration:.01ms!important;animation-iteration-count:1!important;transition-duration:.01ms!important}}
              </style>
            </head>
            <body>
              <main class="wrap">
                <header class="header">
                  <div class="titleRow">
                    <div>
                      <h1>${escapeHtml(title)}</h1>
                      <div class="subtitle">${escapeHtml(subtitle)}</div>
                    </div>
                    <div class="headerActions">
                      $logoutControl
                      <button class="themeToggle settingsToggle" type="button" onclick="openWebSettings()" aria-label="${escapeHtml(tr.text("web_settings"))}"><span class="settingsIcon">${svgIcon("settings")}</span><span class="settingsText">${escapeHtml(tr.text("web_settings"))}</span></button>
                      <button id="themeToggle" class="themeToggle" type="button" onclick="cycleTheme()">${escapeHtml(tr.text("web_theme_auto"))}</button>
                      <div class="badge"><span class="syncDot"></span><span id="syncState">${escapeHtml(tr.text("web_live"))}</span></div>
                    </div>
                  </div>
                  $breadcrumbs
                  <section class="livePanel" aria-label="${escapeHtml(tr.text("web_live"))}">
                    <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_connections"))}</div><div id="liveConnections" class="liveValue">0</div></div>
                    <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_send_speed"))}</div><div id="liveDownload" class="liveValue">0 B/s</div></div>
                    <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_receive_speed"))}</div><div id="liveUpload" class="liveValue">0 B/s</div></div>
                    <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_active_tasks"))}</div><div id="liveTaskCount" class="liveValue">0</div></div>
                    <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_storage"))}</div><div id="liveStorage" class="liveValue">--</div></div>
                    <div id="liveTransfers" class="liveCard liveTransfers"></div>
                  </section>
                </header>

                <div class="utilityGrid">
                  $uploadPanel
                  $managementPanel
                  $clipboardPanel
                </div>

                <section class="toolbar">
                  <div class="searchBox">
                    <input id="search" class="control" type="search" placeholder="${escapeHtml(tr.text("web_search"))}" oninput="applyFilters()">
                    <button id="clearSearch" class="clearSearch hidden" type="button" onclick="clearSearchNow()" aria-label="${escapeHtml(tr.text("web_clear_search"))}">×</button>
                  </div>
                  <select id="sort" class="control" onchange="applyFilters()">
                    <option value="name">${escapeHtml(tr.text("web_sort_name"))}</option>
                    <option value="time-desc">${escapeHtml(tr.text("web_sort_newest"))}</option>
                    <option value="time-asc">${escapeHtml(tr.text("web_sort_oldest"))}</option>
                    <option value="size-desc">${escapeHtml(tr.text("web_sort_large"))}</option>
                    <option value="size-asc">${escapeHtml(tr.text("web_sort_small"))}</option>
                    <option value="type">${escapeHtml(tr.text("web_sort_type"))}</option>
                  </select>
                  <div class="viewButtons">
                    <button id="gridBtn" type="button" onclick="setView('grid')">${escapeHtml(tr.text("web_grid"))}</button>
                    <button id="listBtn" type="button" onclick="setView('list')">${escapeHtml(tr.text("web_list"))}</button>
                  </div>
                </section>

                <div id="visibleCount" class="subtitle" style="margin:0 0 10px"></div>
                <section id="items" class="items grid">
                  $itemsHtml
                </section>
                <div id="emptySearch" class="empty hidden">${escapeHtml(tr.text("web_no_search_results"))}</div>
              </main>

              <div id="connectionBanner" class="connectionBanner">${escapeHtml(tr.text("web_connecting"))}</div>

              <div id="dialogModal" class="dialogModal" onclick="dialogBackdrop(event)">
                <div class="dialogPanel">
                  <div class="dialogBody">
                    <div id="dialogTitle" class="dialogTitle"></div>
                    <div id="dialogMessage" class="dialogMessage"></div>
                    <input id="dialogInput" class="dialogField" type="text">
                    <select id="dialogSelect" class="dialogField"></select>
                  </div>
                  <div class="dialogActions">
                    <button id="dialogCancel" class="secondary" type="button">${escapeHtml(tr.text("web_cancel"))}</button>
                    <button id="dialogOk" class="primary" type="button">${escapeHtml(tr.text("web_ok"))}</button>
                  </div>
                </div>
              </div>

              <div id="managerModal" class="managerModal" onclick="managerBackdrop(event)">
                <div class="managerPanel">
                  <div class="managerHeader">
                    <div id="managerTitle" class="modalTitle">${escapeHtml(tr.text("web_file_management"))}</div>
                    <button class="iconBtn" type="button" onclick="closeManagerModal()">${escapeHtml(tr.text("web_close"))}</button>
                  </div>
                  <div id="managerBody" class="managerBody"></div>
                </div>
              </div>

              <div id="modal" class="modal" onclick="modalBackdrop(event)">
                <div class="modalPanel">
                  <div class="modalHeader">
                    <button class="iconBtn" type="button" onclick="movePreview(-1)">←</button>
                    <div id="modalTitle" class="modalTitle"></div>
                    <button class="iconBtn" type="button" onclick="movePreview(1)">→</button>
                    <button class="iconBtn" type="button" onclick="closeModal()">${escapeHtml(tr.text("web_close"))}</button>
                  </div>
                  <div id="viewer" class="viewer"></div>
                  <div class="modalFooter">
                    <div id="modalDetails" class="details"></div>
                    <a id="modalDownload" class="iconBtn" href="#" download>${escapeHtml(tr.text("web_download_original"))}</a>
                  </div>
                </div>
              </div>

              <script>
                const I18N = $jsTranslations;
                function t(key){
                  let value=I18N[key]||key;
                  for(let i=1;i<arguments.length;i++) value=value.split('{'+(i-1)+'}').join(String(arguments[i]));
                  return value;
                }
                const PAGE_VERSION = '${pageVersion}';
                const CURRENT_PATH = ${jsString(currentRelativePath)};
                const DIRECTORY_MODE = ${directoryMode};
                const REMOTE_MANAGEMENT = ${remoteManagementEnabled};
                const CLIPBOARD_SYNC = ${clipboardSyncEnabled};
                let liveEvents = null;
                let livePollTimer = null;
                let clipboardPollTimer = null;
                let consecutiveFailures = 0;
                let wasDisconnected = false;
                let bannerTimer = null;
                let hasActiveTransfers = false;
                let selectedUploadEntries = [];
                const DEFAULT_WEB_SETTINGS = {
                  uploadParallel: 3,
                  smallDownloadBytes: 16 * 1024 * 1024,
                  downloadParallel: 3
                };
                let webSettings = loadWebSettings();

                function loadWebSettings(){
                  try{
                    const stored = JSON.parse(localStorage.getItem('speedshare-web-settings') || '{}');
                    return {
                      uploadParallel: boundedSetting(stored.uploadParallel,DEFAULT_WEB_SETTINGS.uploadParallel,1,6),
                      smallDownloadBytes: boundedSetting(stored.smallDownloadBytes,DEFAULT_WEB_SETTINGS.smallDownloadBytes,0,64 * 1024 * 1024),
                      downloadParallel: boundedSetting(stored.downloadParallel,DEFAULT_WEB_SETTINGS.downloadParallel,1,6)
                    };
                  }catch(_){
                    return Object.assign({},DEFAULT_WEB_SETTINGS);
                  }
                }

                function boundedSetting(value,fallback,min,max){
                  const parsed = Number(value);
                  return Number.isFinite(parsed) ? Math.min(max,Math.max(min,parsed)) : fallback;
                }

                function saveWebSettings(next){
                  webSettings = {
                    uploadParallel: boundedSetting(next.uploadParallel,DEFAULT_WEB_SETTINGS.uploadParallel,1,6),
                    smallDownloadBytes: boundedSetting(next.smallDownloadBytes,DEFAULT_WEB_SETTINGS.smallDownloadBytes,0,64 * 1024 * 1024),
                    downloadParallel: boundedSetting(next.downloadParallel,DEFAULT_WEB_SETTINGS.downloadParallel,1,6)
                  };
                  localStorage.setItem('speedshare-web-settings',JSON.stringify(webSettings));
                }

                function applyTheme(mode){
                  const selected = ['light','dark','auto'].includes(mode) ? mode : 'auto';
                  if(selected === 'auto') document.documentElement.removeAttribute('data-theme');
                  else document.documentElement.setAttribute('data-theme',selected);
                  localStorage.setItem('speedshare-theme',selected);
                  const button=document.getElementById('themeToggle');
                  if(button) button.textContent = selected === 'dark' ? t('web_theme_dark') : selected === 'light' ? t('web_theme_light') : t('web_theme_auto');
                }

                function cycleTheme(){
                  const current = localStorage.getItem('speedshare-theme') || 'auto';
                  const next = current === 'auto' ? 'dark' : current === 'dark' ? 'light' : 'auto';
                  applyTheme(next);
                }

                function showConnectionBanner(text,type,persistent){
                  const banner = document.getElementById('connectionBanner');
                  if(!banner) return;
                  if(bannerTimer){clearTimeout(bannerTimer);bannerTimer=null;}
                  banner.textContent = text;
                  banner.className = 'connectionBanner show ' + (type || '');
                  if(!persistent){bannerTimer=setTimeout(function(){banner.className='connectionBanner';},1800);}
                }

                function setSyncState(text, connected){
                  const state = document.getElementById('syncState');
                  const dot = document.querySelector('.syncDot');
                  if(state) state.textContent = text;
                  if(dot) dot.style.background = connected ? '#22c55e' : '#f59e0b';
                  if(connected){
                    if(wasDisconnected) showConnectionBanner(t('web_reconnected'),'ok',false);
                    wasDisconnected = false;
                  }
                }

                function connectLiveEvents(){
                  if(!window.EventSource) return;
                  if(liveEvents) liveEvents.close();
                  liveEvents = new EventSource('/events?v=' + encodeURIComponent(PAGE_VERSION));
                  liveEvents.addEventListener('hello',function(event){
                    if(event.data && event.data !== PAGE_VERSION){location.reload();return;}
                    setSyncState(t('web_live_connected'),true);
                  });
                  liveEvents.addEventListener('content-changed',function(){
                    if(uploadInProgress){contentChangePending=true;return;}
                    location.reload();
                  });
                  liveEvents.onerror = function(){
                    setSyncState(t('web_reconnecting'),false);
                  };
                }

                function formatSpeed(value){return humanBytes(value) + '/s';}

                function renderLiveStatus(data){
                  if(data.version && data.version !== PAGE_VERSION){location.reload();return;}
                  document.getElementById('liveConnections').textContent = String(data.activeConnections || 0);
                  document.getElementById('liveDownload').textContent = formatSpeed(data.downloadBytesPerSecond || 0);
                  document.getElementById('liveUpload').textContent = formatSpeed(data.uploadBytesPerSecond || 0);
                  const transfers = Array.isArray(data.activeTransfers) ? data.activeTransfers : [];
                  hasActiveTransfers = transfers.length > 0;
                  document.getElementById('liveTaskCount').textContent = String(transfers.length);
                  latestUploadAvailableBytes = Number(data.uploadAvailableBytes || 0);
                  latestUploadReserveBytes = Number(data.uploadReserveBytes || 0);
                  const available = Number(data.availableStorageBytes || 0);
                  const totalStorage = Number(data.totalStorageBytes || 0);
                  document.getElementById('liveStorage').textContent = totalStorage > 0 ? humanBytes(available) + ' / ' + humanBytes(totalStorage) : '--';
                  const panel = document.getElementById('liveTransfers');
                  panel.classList.toggle('active',transfers.length > 0);
                  if(transfers.length === 0){panel.textContent = '';return;}
                  panel.innerHTML = transfers.map(function(item){
                    const direction = item.direction === 'upload' ? t('speed_upload') : t('speed_download');
                    const total = Number(item.totalBytes || 0);
                    const done = Number(item.transferredBytes || 0);
                    const percent = total > 0 ? Math.min(100,done * 100 / total).toFixed(1) + '%' : '';
                    return '<div>' + direction + ' · ' + escapeText(item.fileName || '') + ' · ' + percent + ' · ' + formatSpeed(item.bytesPerSecond || 0) + ' · ' + escapeText(item.clientAddress || '') + '</div>';
                  }).join('');
                }

                function escapeText(value){
                  return String(value).replace(/[&<>"']/g,function(char){
                    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char];
                  });
                }

                let dialogResolve = null;
                let dialogReturnFocus = null;
                let modalReturnFocus = null;
                let managerReturnFocus = null;

                function syncBodyLock(){
                  const locked=['dialogModal','modal','managerModal'].some(function(id){
                    const element=document.getElementById(id);return element && element.classList.contains('open');
                  });
                  document.body.style.overflow=locked?'hidden':'';
                }

                function restoreFocus(element){
                  if(element && element.isConnected && typeof element.focus==='function')setTimeout(function(){element.focus();},0);
                }

                function trapFocus(event,root){
                  if(event.key!=='Tab' || !root)return;
                  const focusable=Array.from(root.querySelectorAll('button:not([disabled]),a[href],input:not([disabled]),select:not([disabled]),[tabindex]:not([tabindex="-1"])')).filter(function(item){return item.offsetParent!==null;});
                  if(focusable.length===0)return;
                  const first=focusable[0],last=focusable[focusable.length-1];
                  if(event.shiftKey && document.activeElement===first){event.preventDefault();last.focus();}
                  else if(!event.shiftKey && document.activeElement===last){event.preventDefault();first.focus();}
                }

                function closeDialog(result){
                  const modal = document.getElementById('dialogModal');
                  if(modal) modal.classList.remove('open');
                  syncBodyLock();
                  restoreFocus(dialogReturnFocus);dialogReturnFocus=null;
                  if(dialogResolve){
                    const resolve = dialogResolve;
                    dialogResolve = null;
                    resolve(result);
                  }
                }

                function dialogBackdrop(event){
                  if(event.target.id === 'dialogModal') closeDialog(null);
                }

                function openDialog(options){
                  return new Promise(function(resolve){
                    dialogReturnFocus=document.activeElement;
                    dialogResolve = resolve;
                    const modal = document.getElementById('dialogModal');
                    const title = document.getElementById('dialogTitle');
                    const message = document.getElementById('dialogMessage');
                    const input = document.getElementById('dialogInput');
                    const select = document.getElementById('dialogSelect');
                    const cancel = document.getElementById('dialogCancel');
                    const ok = document.getElementById('dialogOk');
                    title.textContent = options.title || '';
                    message.textContent = options.message || '';
                    input.value = options.value || '';
                    input.placeholder = options.placeholder || '';
                    input.style.display = options.input ? 'block' : 'none';
                    select.replaceChildren();
                    (options.choices || []).forEach(function(choice){
                      const option = document.createElement('option');
                      option.value = choice.value;
                      option.textContent = choice.label;
                      select.appendChild(option);
                    });
                    if(options.choice) select.value = options.choice;
                    select.style.display = options.choices ? 'block' : 'none';
                    ok.textContent = options.okText || t('web_ok');
                    ok.className = options.danger ? 'primary danger' : 'primary';
                    cancel.onclick = function(){closeDialog(null);};
                    ok.onclick = function(){
                      closeDialog(options.choices ? select.value : (options.input ? input.value : true));
                    };
                    modal.classList.add('open');
                    syncBodyLock();
                    setTimeout(function(){(options.input ? input : (options.choices ? select : ok)).focus();},30);
                  });
                }

                function confirmDialog(title,message,danger){
                  return openDialog({title:title,message:message,danger:danger,okText:t('web_ok')});
                }

                async function pollLiveStatus(){
                  try{
                    const response = await fetch('/api/status?t=' + Date.now(),{cache:'no-store'});
                    if(!response.ok) throw new Error('HTTP ' + response.status);
                    renderLiveStatus(await response.json());
                    consecutiveFailures = 0;
                    setSyncState(t('web_live_connected'),true);
                  }catch(_){
                    consecutiveFailures++;
                    setSyncState(t('web_connection_lost'),false);
                    if(consecutiveFailures >= 3){
                      wasDisconnected = true;
                      showConnectionBanner(t('web_wait_server'),'error',true);
                    }
                  }finally{
                    const nextPollDelay = document.hidden ? 5000 : (hasActiveTransfers ? 700 : 2000);
                    livePollTimer = setTimeout(pollLiveStatus,nextPollDelay);
                  }
                }

                const container = document.getElementById('items');
                let previewItems = [];
                let previewIndex = -1;
                let lastSelectedCard = null;
                let currentUploadXhrs = new Set();
                let uploadCancelled = false;
                let uploadInProgress = false;
                let latestUploadAvailableBytes = null;
                let latestUploadReserveBytes = 0;
                let contentChangePending = false;
                let failedUploadEntries = [];
                let lastUploadEntries = [];
                let lastUploadQueueRenderAt = 0;

                function setView(mode){
                  const chosen = mode === 'list' ? 'list' : 'grid';
                  container.classList.remove('grid','list');
                  container.classList.add(chosen);
                  document.getElementById('gridBtn').classList.toggle('active',chosen === 'grid');
                  document.getElementById('listBtn').classList.toggle('active',chosen === 'list');
                  localStorage.setItem('speedshare-view',chosen);
                }

                function applyFilters(){
                  const search = document.getElementById('search');
                  const sortSelect = document.getElementById('sort');
                  const query = (search.value || '').trim().toLocaleLowerCase();
                  const sort = sortSelect.value;
                  localStorage.setItem('speedshare-sort',sort);
                  const clearButton = document.getElementById('clearSearch');
                  if(clearButton) clearButton.classList.toggle('hidden',query.length === 0);
                  const children = Array.from(container.querySelectorAll('.item'));

                  children.sort(function(a,b){
                    const dirDiff = Number(b.dataset.dir || 0) - Number(a.dataset.dir || 0);
                    if(dirDiff !== 0) return dirDiff;
                    if(sort === 'time-desc') return Number(b.dataset.time || 0) - Number(a.dataset.time || 0);
                    if(sort === 'time-asc') return Number(a.dataset.time || 0) - Number(b.dataset.time || 0);
                    if(sort === 'size-desc') return Number(b.dataset.size || 0) - Number(a.dataset.size || 0);
                    if(sort === 'size-asc') return Number(a.dataset.size || 0) - Number(b.dataset.size || 0);
                    if(sort === 'type') return String(a.dataset.type || '').localeCompare(String(b.dataset.type || ''),'zh-CN');
                    return String(a.dataset.name || '').localeCompare(String(b.dataset.name || ''),'zh-CN',{numeric:true,sensitivity:'base'});
                  });

                  let visible = 0;
                  const fragment = document.createDocumentFragment();
                  children.forEach(function(item){
                    fragment.appendChild(item);
                    const match = String(item.dataset.name || '').toLocaleLowerCase().includes(query);
                    item.classList.toggle('hidden',!match);
                    if(match) visible++;
                  });
                  container.appendChild(fragment);
                  document.getElementById('visibleCount').textContent = t('web_visible',visible);
                  const emptySearch = document.getElementById('emptySearch');
                  if(emptySearch) emptySearch.classList.toggle('hidden',visible > 0 || query.length === 0);
                  refreshPreviewItems();
                }

                function clearSearchNow(){
                  const search = document.getElementById('search');
                  if(!search) return;
                  search.value = '';
                  applyFilters();
                  search.focus();
                }

                function refreshPreviewItems(){
                  previewItems = Array.from(container.querySelectorAll('.file-card:not(.hidden)')).filter(function(item){
                    return item.dataset.kind !== 'download';
                  });
                }

                function selectWithModifier(event,card){
                  if(!event || !(event.ctrlKey || event.metaKey || event.shiftKey))return false;
                  event.preventDefault();event.stopPropagation();
                  const check=card.querySelector('.itemCheck');
                  if(!check)return true;
                  if(event.shiftKey && lastSelectedCard){
                    const visible=Array.from(container.querySelectorAll('.item:not(.hidden)'));
                    const start=visible.indexOf(lastSelectedCard),end=visible.indexOf(card);
                    if(start>=0 && end>=0){
                      if(!(event.ctrlKey || event.metaKey))clearSelection(true);
                      const from=Math.min(start,end),to=Math.max(start,end);
                      visible.slice(from,to+1).forEach(function(item){const itemCheck=item.querySelector('.itemCheck');if(itemCheck)itemCheck.checked=true;});
                    }else check.checked=true;
                  }else{
                    check.checked=!check.checked;
                  }
                  lastSelectedCard=card;selectionChanged();
                  return true;
                }

                function handleFolderClick(event,element){
                  selectWithModifier(event,element);
                }

                function handleFile(element,event){
                  if(selectWithModifier(event,element))return;
                  if(element.dataset.kind === 'download'){
                    location.href = element.dataset.downloadUrl;
                    return;
                  }
                  refreshPreviewItems();
                  const index = previewItems.indexOf(element);
                  openPreview(index >= 0 ? index : 0);
                }

                function openPreview(index){
                  if(index < 0 || index >= previewItems.length) return;
                  previewIndex = index;
                  const item = previewItems[index];
                  const kind = item.dataset.kind;
                  const url = item.dataset.previewUrl;
                  const viewer = document.getElementById('viewer');
                  viewer.replaceChildren();

                  let media;
                  if(kind === 'image'){
                    media = document.createElement('img');
                    media.src = url;
                    media.alt = item.dataset.name || '';
                  }else if(kind === 'video'){
                    media = document.createElement('video');
                    media.src = url;
                    media.controls = true;
                    media.playsInline = true;
                    media.preload = 'auto';
                  }else if(kind === 'audio'){
                    media = document.createElement('audio');
                    media.src = url;
                    media.controls = true;
                    media.preload = 'metadata';
                  }else if(kind === 'pdf'){
                    media = document.createElement('iframe');
                    media.src = url;
                    media.title = item.dataset.name || 'PDF';
                  }

                  if(media){
                    if(kind === 'video' || kind === 'audio'){
                      media.addEventListener('error',function(){
                        const message=document.createElement('div');
                        message.className='empty';
                        const title=document.createElement('div');
                        title.textContent=t('web_media_load_failed');
                        const help=document.createElement('div');
                        help.className='managerMeta';
                        help.textContent=t('web_media_load_help');
                        const link=document.createElement('a');
                        link.className='emptyAction';
                        link.href=item.dataset.downloadUrl;
                        link.download=item.dataset.name || 'download';
                        link.textContent=t('web_download_original');
                        message.appendChild(title);
                        message.appendChild(help);
                        message.appendChild(link);
                        viewer.replaceChildren(message);
                      });
                    }
                    viewer.appendChild(media);
                    if(typeof media.load === 'function') media.load();
                  }
                  document.getElementById('modalTitle').textContent = item.dataset.name || '';
                  document.getElementById('modalDetails').textContent = item.dataset.details || '';
                  const download = document.getElementById('modalDownload');
                  download.href = item.dataset.downloadUrl;
                  download.setAttribute('download',item.dataset.name || 'download');
                  const previewModal=document.getElementById('modal');
                  if(!previewModal.classList.contains('open'))modalReturnFocus=document.activeElement;
                  previewModal.classList.add('open');
                  syncBodyLock();
                }

                function movePreview(delta){
                  if(previewItems.length === 0) return;
                  let next = previewIndex + delta;
                  if(next < 0) next = previewItems.length - 1;
                  if(next >= previewItems.length) next = 0;
                  openPreview(next);
                }

                function closeModal(){
                  document.getElementById('modal').classList.remove('open');
                  document.getElementById('viewer').replaceChildren();
                  syncBodyLock();
                  restoreFocus(modalReturnFocus);modalReturnFocus=null;
                }

                function modalBackdrop(event){
                  if(event.target.id === 'modal') closeModal();
                }

                document.addEventListener('keydown',function(event){
                  const dialogOpen = document.getElementById('dialogModal').classList.contains('open');
                  if(dialogOpen){
                    if(event.key === 'Escape') closeDialog(null);
                    trapFocus(event,document.getElementById('dialogModal'));
                    return;
                  }
                  const contextMenu=document.getElementById('contextMenu');
                  if(contextMenu && contextMenu.classList.contains('open')){
                    if(event.key==='Escape'){closeContextMenu();restoreFocus(contextMenuCard);}
                    if(event.key==='ArrowDown'){event.preventDefault();moveContextMenuFocus(1);}
                    if(event.key==='ArrowUp'){event.preventDefault();moveContextMenuFocus(-1);}
                    trapFocus(event,contextMenu);
                    return;
                  }
                  const managerModal=document.getElementById('managerModal');
                  if(managerModal && managerModal.classList.contains('open')){
                    if(event.key==='Escape')closeManagerModal();
                    trapFocus(event,managerModal);
                    return;
                  }
                  const modalOpen = document.getElementById('modal').classList.contains('open');
                  if(!modalOpen) return;
                  if(event.key === 'Escape') closeModal();
                  if(event.key === 'ArrowLeft') movePreview(-1);
                  if(event.key === 'ArrowRight') movePreview(1);
                  trapFocus(event,document.getElementById('modal'));
                });

                function humanBytes(value){
                  let size = Number(value || 0);
                  const units = ['B','KB','MB','GB','TB'];
                  let index = 0;
                  while(size >= 1024 && index < units.length - 1){size /= 1024;index++;}
                  return size.toFixed(index === 0 ? 0 : 2) + ' ' + units[index];
                }

                function readAllDirectoryEntries(reader){
                  return new Promise(function(resolve){
                    const entries = [];
                    function readNext(){
                      reader.readEntries(function(batch){
                        if(!batch || batch.length === 0){
                          resolve(entries);
                          return;
                        }
                        entries.push.apply(entries,batch);
                        readNext();
                      },function(){resolve(entries);});
                    }
                    readNext();
                  });
                }

                function fileFromEntry(entry,path){
                  return new Promise(function(resolve){
                    entry.file(function(file){
                      resolve({file:file,path:path + file.name});
                    },function(){resolve(null);});
                  });
                }

                async function walkDroppedEntry(entry,path){
                  if(entry.isFile){
                    const fileEntry = await fileFromEntry(entry,path);
                    return fileEntry ? [fileEntry] : [];
                  }
                  if(entry.isDirectory){
                    const children = await readAllDirectoryEntries(entry.createReader());
                    const nested = await Promise.all(children.map(function(child){
                      return walkDroppedEntry(child,path + entry.name + '/');
                    }));
                    return nested.flat();
                  }
                  return [];
                }

                async function readDroppedUploadEntries(dataTransfer){
                  const items = Array.from(dataTransfer.items || []);
                  const entries = items.map(function(item){
                    return item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
                  }).filter(Boolean);
                  if(entries.length > 0){
                    const nested = await Promise.all(entries.map(function(entry){
                      return walkDroppedEntry(entry,'');
                    }));
                    return nested.flat();
                  }
                  return Array.from(dataTransfer.files || []).map(function(file){
                    return {file:file,path:file.webkitRelativePath || file.name};
                  });
                }

                function installUpload(){
                  const zone = document.getElementById('dropZone');
                  const input = document.getElementById('uploadFiles');
                  const folderInput = document.getElementById('uploadFolder');
                  if(!zone || !input) return;
                  ['dragenter','dragover'].forEach(function(name){
                    zone.addEventListener(name,function(event){
                      event.preventDefault();
                      zone.classList.add('drag');
                      const prompt = document.getElementById('dropPrompt');
                      if(prompt) prompt.textContent = t('web_release_to_upload');
                    });
                  });
                  zone.addEventListener('dragleave',function(event){
                    if(event.relatedTarget && zone.contains(event.relatedTarget)) return;
                    zone.classList.remove('drag');
                    const prompt = document.getElementById('dropPrompt');
                    if(prompt) prompt.textContent = t('web_drop_files_here');
                  });
                  zone.addEventListener('drop',async function(event){
                    event.preventDefault();
                    zone.classList.remove('drag');
                    const prompt = document.getElementById('dropPrompt');
                    if(prompt) prompt.textContent = t('web_drop_files_here');
                    const status = document.getElementById('uploadStatus');
                    if(status) status.textContent = t('web_loading');
                    selectedUploadEntries = await readDroppedUploadEntries(event.dataTransfer);
                    updateUploadSelection();
                  });
                  input.addEventListener('change',function(){
                    selectedUploadEntries = Array.from(input.files || []).map(function(file){return {file:file,path:file.name};});
                    updateUploadSelection();
                  });
                  if(folderInput){
                    folderInput.addEventListener('change',function(){
                      selectedUploadEntries = Array.from(folderInput.files || []).map(function(file){return {file:file,path:file.webkitRelativePath || file.name};});
                      updateUploadSelection();
                    });
                  }
                }

                function safeUploadPath(path){
                  return String(path || '').replace(/\\/g,'/').split('/').filter(function(part){
                    return part && part !== '.' && part !== '..';
                  }).join('/');
                }

                function getUploadEntries(){
                  return selectedUploadEntries.map(function(entry){
                    return {file:entry.file,path:safeUploadPath(entry.path || entry.file.name)};
                  }).filter(function(entry){return entry.path;});
                }

                function removeUploadEntry(index){
                  if(uploadInProgress || index < 0 || index >= selectedUploadEntries.length)return;
                  selectedUploadEntries.splice(index,1);
                  const fileInput=document.getElementById('uploadFiles');
                  const folderInput=document.getElementById('uploadFolder');
                  if(fileInput)fileInput.value='';
                  if(folderInput)folderInput.value='';
                  updateUploadSelection();
                }

                function updateUploadSelection(){
                  const status = document.getElementById('uploadStatus');
                  const entries = getUploadEntries();
                  if(status){
                    status.className = 'uploadStatus';
                    const selectedBytes = entries.reduce(function(sum,entry){return sum + (entry.file.size || 0);},0);
                    status.textContent = entries.length > 0 ? t('web_selected_count',entries.length) + ' · ' + humanBytes(selectedBytes) : t('web_no_files_selected');
                  }
                  renderUploadQueue(entries,entries.map(function(){return 'waiting';}));
                  setUploadProgress(0);
                  const retry = document.getElementById('retryUpload');
                  if(retry) retry.style.display = 'none';
                }

                function setUploadProgress(percent){
                  const bar = document.querySelector('#uploadProgress span');
                  if(bar) bar.style.width = Math.max(0,Math.min(100,percent || 0)) + '%';
                }

                function queueLabel(state){
                  return t('web_queue_' + state);
                }

                function renderUploadQueue(entries,states,percents){
                  const queue = document.getElementById('uploadQueue');
                  if(!queue) return;
                  queue.replaceChildren();
                  entries.forEach(function(entry,index){
                    const state = states[index] || 'waiting';
                    const item = document.createElement('div');
                    item.className = 'uploadQueueItem ' + state;
                    const name = document.createElement('div');
                    name.className = 'uploadQueueName';
                    name.textContent = entry.path || entry.file.name;
                    const status = document.createElement('div');
                    status.className = 'uploadQueueState';
                    const percent = Math.max(0,Math.min(100,Number((percents || [])[index] || 0)));
                    status.textContent = queueLabel(state) + (state === 'uploading' ? ' ' + Math.round(percent) + '%' : '');
                    if(state === 'failed'){
                      const retry = document.createElement('button');
                      retry.className = 'uploadQueueRetry';
                      retry.type = 'button';
                      retry.textContent = t('web_retry_failed');
                      retry.addEventListener('click',function(){retryUploadItem(index);});
                      status.appendChild(retry);
                    }else if(state === 'waiting' && !uploadInProgress){
                      const remove = document.createElement('button');
                      remove.className = 'uploadQueueRetry';
                      remove.type = 'button';
                      remove.textContent = t('web_remove_file');
                      remove.addEventListener('click',function(){removeUploadEntry(index);});
                      status.appendChild(remove);
                    }
                    item.appendChild(name);
                    item.appendChild(status);
                    queue.appendChild(item);
                  });
                }

                function retryUploadItem(index){
                  const entry = lastUploadEntries[index];
                  if(!entry) return;
                  failedUploadEntries = [entry];
                  uploadFilesNow(true);
                }

                function cancelUploadQueue(){
                  uploadCancelled = true;
                  currentUploadXhrs.forEach(function(xhr){xhr.abort();});
                }

                function uploadOne(entry,index,total,onProgress){
                  return new Promise(function(resolve,reject){
                    const file = entry.file;
                    const xhr = new XMLHttpRequest();
                    currentUploadXhrs.add(xhr);
                    const directory = document.getElementById('uploadDirectory').value;
                    const uploadName = entry.path || file.name;
                    const url = '/upload?path=' + encodeURIComponent(directory) + '&name=' + encodeURIComponent(uploadName);
                    const started = performance.now();
                    let lastStatusRenderAt = 0;
                    xhr.open('POST',url);
                    xhr.setRequestHeader('Content-Type','application/octet-stream');
                    xhr.upload.onprogress = function(event){
                      if(!event.lengthComputable) return;
                      const seconds = Math.max((performance.now() - started) / 1000,0.001);
                      const speed = event.loaded / seconds;
                      const percent = Math.round(event.loaded * 100 / event.total);
                      if(onProgress) onProgress(event.loaded,event.total);
                      const now = performance.now();
                      if(now - lastStatusRenderAt >= 120 || event.loaded >= event.total){
                        lastStatusRenderAt = now;
                        document.getElementById('uploadStatus').textContent =
                          t('web_upload_file_progress',index+1,total,uploadName,percent,humanBytes(speed));
                      }
                    };
                    xhr.onload = function(){
                      currentUploadXhrs.delete(xhr);
                      if(xhr.status >= 200 && xhr.status < 300) resolve();
                      else reject(new Error(xhr.responseText || ('HTTP ' + xhr.status)));
                    };
                    xhr.onerror = function(){
                      currentUploadXhrs.delete(xhr);
                      reject(new Error(t('web_network_failed')));
                    };
                    xhr.onabort = function(){
                      currentUploadXhrs.delete(xhr);
                      reject(new Error(t('web_upload_cancelled')));
                    };
                    xhr.send(file);
                  });
                }

                async function uploadFilesNow(retryFailed){
                  const input = document.getElementById('uploadFiles');
                  const status = document.getElementById('uploadStatus');
                  if(!input || !status) return;
                  const entries = retryFailed ? failedUploadEntries.slice() : getUploadEntries();
                  if(entries.length === 0){
                    status.className = 'uploadStatus error';
                    status.textContent = t('web_choose_or_drop');
                    return;
                  }
                  const cancelButton = document.getElementById('cancelUpload');
                  const retryButton = document.getElementById('retryUpload');
                  const startButton = document.getElementById('uploadStartButton');
                  const states = entries.map(function(){return 'waiting';});
                  const percents = entries.map(function(){return 0;});
                  const totalBytes = entries.reduce(function(sum,entry){return sum + (entry.file.size || 0);},0);
                  try{
                    const storageResponse = await fetch('/api/status?t=' + Date.now(),{cache:'no-store'});
                    if(storageResponse.ok){
                      const storageData = await storageResponse.json();
                      renderLiveStatus(storageData);
                    }
                  }catch(_){ }
                  if(latestUploadAvailableBytes !== null && totalBytes > latestUploadAvailableBytes){
                    status.className = 'uploadStatus error';
                    status.textContent = t('web_upload_space_insufficient',humanBytes(totalBytes),humanBytes(latestUploadAvailableBytes),humanBytes(latestUploadReserveBytes));
                    return;
                  }
                  const loadedBytes = entries.map(function(){return 0;});
                  const maxParallelUploads = Math.min(webSettings.uploadParallel,entries.length);
                  let nextUploadIndex = 0;
                  uploadCancelled = false;
                  uploadInProgress = true;
                  failedUploadEntries = [];
                  lastUploadEntries = entries.slice();
                  if(cancelButton) cancelButton.style.display = '';
                  if(retryButton) retryButton.style.display = 'none';
                  if(startButton){startButton.disabled = true;startButton.textContent = t('web_uploading');}
                  renderUploadQueue(entries,states,percents);
                  setUploadProgress(0);
                  try{
                    status.className = 'uploadStatus';
                    async function runUploadWorker(){
                      while(!uploadCancelled){
                        const i = nextUploadIndex++;
                        if(i >= entries.length) return;
                        states[i] = 'uploading';
                        renderUploadQueue(entries,states,percents);
                        try{
                          await uploadOne(entries[i],i,entries.length,function(loaded,total){
                            loadedBytes[i] = loaded;
                            percents[i] = total > 0 ? loaded * 100 / total : 0;
                            const loadedTotal = loadedBytes.reduce(function(sum,value){return sum + value;},0);
                            setUploadProgress(totalBytes > 0 ? (loadedTotal * 100 / totalBytes) : 0);
                            const now = performance.now();
                            if(now - lastUploadQueueRenderAt >= 120){
                              lastUploadQueueRenderAt = now;
                              renderUploadQueue(entries,states,percents);
                            }
                          });
                          loadedBytes[i] = entries[i].file.size || loadedBytes[i];
                          percents[i] = 100;
                          states[i] = 'done';
                          renderUploadQueue(entries,states,percents);
                          const loadedTotal = loadedBytes.reduce(function(sum,value){return sum + value;},0);
                          setUploadProgress(totalBytes > 0 ? (loadedTotal * 100 / totalBytes) : 100);
                        }catch(error){
                          states[i] = uploadCancelled ? 'cancelled' : 'failed';
                          failedUploadEntries.push(entries[i]);
                          renderUploadQueue(entries,states,percents);
                        }
                      }
                    }
                    const workers = Array.from({length:maxParallelUploads},function(){return runUploadWorker();});
                    await Promise.all(workers);
                    if(uploadCancelled){
                      entries.forEach(function(entry,index){
                        if(states[index] === 'waiting'){
                          states[index] = 'cancelled';
                          failedUploadEntries.push(entry);
                        }
                      });
                      renderUploadQueue(entries,states,percents);
                    }
                    if(failedUploadEntries.length > 0){
                      status.textContent = uploadCancelled ? t('web_upload_cancelled') : t('web_upload_failed_count',failedUploadEntries.length);
                      status.className = 'uploadStatus error';
                      if(retryButton && !uploadCancelled) retryButton.style.display = '';
                      return;
                    }
                    status.textContent = t('web_upload_done');
                    status.className = 'uploadStatus success';
                    setUploadProgress(100);
                    status.innerHTML = escapeText(t('web_upload_done')) + ' <button class="uploadQueueRetry" type="button" onclick="location.reload()">' + escapeText(t('web_refresh_now')) + '</button>';
                    contentChangePending = true;
                  }catch(error){
                    status.textContent = t('web_upload_failed',error.message);
                    status.className = 'uploadStatus error';
                  }finally{
                    uploadInProgress = false;
                    currentUploadXhrs.clear();
                    if(cancelButton) cancelButton.style.display = 'none';
                    if(startButton){startButton.disabled = false;startButton.textContent = t('web_start_upload');}
                    if(contentChangePending && failedUploadEntries.length === 0 && !uploadCancelled){
                      contentChangePending=false;
                      setTimeout(function(){location.reload();},800);
                    }
                  }
                }

                function selectedCards(){
                  return Array.from(document.querySelectorAll('.itemCheck:checked')).map(function(check){return check.closest('.item');}).filter(Boolean);
                }

                function selectedPaths(){
                  return selectedCards().map(function(card){return card.dataset.path || '';}).filter(Boolean);
                }

                function selectionChanged(anchor){
                  if(anchor)lastSelectedCard=anchor;
                  const cards = Array.from(document.querySelectorAll('.item'));
                  cards.forEach(function(card){
                    const check = card.querySelector('.itemCheck');
                    card.classList.toggle('selected',Boolean(check && check.checked));
                  });
                  const count = selectedCards().length;
                  const bar = document.getElementById('selectionBar');
                  const text = document.getElementById('selectionCount');
                  if(text) text.textContent = t('web_selected_count',count);
                  if(bar) bar.classList.toggle('hidden',count === 0);
                }

                function clearSelection(keepAnchor){
                  document.querySelectorAll('.itemCheck').forEach(function(check){check.checked=false;});
                  if(!keepAnchor)lastSelectedCard=null;
                  selectionChanged();
                }

                function toggleSelectAll(){
                  const visible = Array.from(document.querySelectorAll('.item')).filter(function(item){return !item.classList.contains('hidden');});
                  const shouldSelect = visible.some(function(item){const check=item.querySelector('.itemCheck');return check && !check.checked;});
                  visible.forEach(function(item){const check=item.querySelector('.itemCheck');if(check) check.checked=shouldSelect;});
                  selectionChanged();
                }

                container.addEventListener('click',function(event){
                  if(event.target===container)clearSelection();
                });

                async function apiPost(url, lines){
                  const body = (lines || []).map(function(value){return encodeURIComponent(value);}).join('\n');
                  const response = await fetch(url,{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:body});
                  const text = await response.text();
                  if(!response.ok) throw new Error(text || ('HTTP ' + response.status));
                  if(!text) return {};
                  try{return JSON.parse(text);}catch(_){return {text:text};}
                }

                function openWebSettings(){
                  openManagerModal(t('web_settings'));
                  const body=document.getElementById('managerBody');
                  body.innerHTML =
                    '<div class="managerRow"><div class="managerRowMain"><div class="managerName">'+escapeText(t('web_settings_upload_parallel'))+'</div><div class="managerMeta">'+escapeText(t('web_settings_upload_parallel_hint'))+'</div></div><select id="settingUploadParallel" class="control"><option value="1">1</option><option value="2">2</option><option value="3">3</option><option value="4">4</option><option value="6">6</option></select></div>' +
                    '<div class="managerRow"><div class="managerRowMain"><div class="managerName">'+escapeText(t('web_settings_download_threshold'))+'</div><div class="managerMeta">'+escapeText(t('web_settings_download_threshold_hint'))+'</div></div><select id="settingDownloadThreshold" class="control"><option value="1048576">1 MB</option><option value="8388608">8 MB</option><option value="16777216">16 MB</option><option value="67108864">64 MB</option><option value="0">'+escapeText(t('web_settings_disable_parallel_download'))+'</option></select></div>' +
                    '<div class="managerRow"><div class="managerRowMain"><div class="managerName">'+escapeText(t('web_settings_download_parallel'))+'</div><div class="managerMeta">'+escapeText(t('web_settings_download_parallel_hint'))+'</div></div><select id="settingDownloadParallel" class="control"><option value="1">1</option><option value="2">2</option><option value="3">3</option><option value="4">4</option><option value="6">6</option></select></div>' +
                    '<div class="managerActions" style="justify-content:flex-end;margin-top:12px"><button onclick="resetWebSettings()">'+escapeText(t('web_reset_settings'))+'</button><button onclick="saveWebSettingsFromPanel()">'+escapeText(t('web_save_settings'))+'</button></div>';
                  document.getElementById('settingUploadParallel').value=String(webSettings.uploadParallel);
                  document.getElementById('settingDownloadThreshold').value=String(webSettings.smallDownloadBytes);
                  document.getElementById('settingDownloadParallel').value=String(webSettings.downloadParallel);
                }

                function saveWebSettingsFromPanel(){
                  saveWebSettings({
                    uploadParallel: document.getElementById('settingUploadParallel').value,
                    smallDownloadBytes: document.getElementById('settingDownloadThreshold').value,
                    downloadParallel: document.getElementById('settingDownloadParallel').value
                  });
                  closeManagerModal();
                  showConnectionBanner(t('web_settings_saved'),'ok',false);
                }

                function resetWebSettings(){
                  saveWebSettings(Object.assign({},DEFAULT_WEB_SETTINGS));
                  openWebSettings();
                  showConnectionBanner(t('web_settings_reset'),'ok',false);
                }

                async function sendClipboardToPhone(){
                  if(!CLIPBOARD_SYNC)return;
                  const input=document.getElementById('clipboardInput');
                  if(!input)return;
                  try{
                    const response=await fetch('/api/clipboard',{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:input.value || ''});
                    const text=await response.text();
                    if(!response.ok)throw new Error(text || ('HTTP '+response.status));
                    showConnectionBanner(t('web_clipboard_sent'),'ok',false);
                    refreshPhoneClipboard();
                  }catch(error){alert(t('web_clipboard_failed',error.message));}
                }

                async function refreshPhoneClipboard(){
                  if(!CLIPBOARD_SYNC)return;
                  const output=document.getElementById('phoneClipboardText');
                  if(!output)return;
                  try{
                    const response=await fetch('/api/clipboard?t='+Date.now(),{cache:'no-store'});
                    const data=await response.json();
                    if(!response.ok)throw new Error(data.message || ('HTTP '+response.status));
                    output.textContent=data.text || t('web_clipboard_empty');
                  }catch(error){output.textContent=t('web_clipboard_failed',error.message);}
                }

                async function pollPhoneClipboard(){
                  if(!CLIPBOARD_SYNC)return;
                  await refreshPhoneClipboard();
                  clipboardPollTimer=setTimeout(pollPhoneClipboard,2500);
                }

                function downloadSelectedSeparately(){
                  const cards = selectedCards().filter(function(card){return card.dataset.dir !== '1';});
                  if(cards.length === 0){alert(t('web_select_file_zip_folder'));return;}
                  const smallThreshold = Number(webSettings.smallDownloadBytes || 0);
                  const parallel = Math.max(1,Number(webSettings.downloadParallel || 3));
                  cards.forEach(function(card,index){
                    const size = Number(card.dataset.size || 0);
                    const fastLane = smallThreshold > 0 && size > 0 && size <= smallThreshold;
                    const delay = fastLane ? Math.floor(index / parallel) * 180 : index * 500;
                    setTimeout(function(){
                      const link=document.createElement('a');
                      link.href=card.dataset.downloadUrl || '';
                      link.download=card.dataset.name || '';
                      document.body.appendChild(link);link.click();link.remove();
                    },delay);
                  });
                  showConnectionBanner(t('web_downloads_started',cards.length),'ok',false);
                }

                async function downloadSelectedZip(compress){
                  const paths=selectedPaths();
                  if(paths.length===0){alert(t('web_select_items'));return;}
                  const defaultName='SpeedShareWeb_' + new Date().toISOString().slice(0,19).replace(/[:T]/g,'-') + '.zip';
                  const name=await openDialog({title:t('web_zip_name'),message:t('web_zip_hint'),input:true,value:defaultName,okText:compress?t('web_zip_compress'):t('web_zip_fast')});
                  if(name===null)return;
                  try{
                    const endpoint=DIRECTORY_MODE?'/api/zip/prepare':'/api/selected-zip/prepare';
                    const result=await apiPost(endpoint+'?mode='+(compress?'compress':'store')+'&name='+encodeURIComponent(name),paths);
                    if(!result.url)throw new Error(t('web_no_download_url'));
                    location.href=result.url;
                  }catch(error){alert(t('web_zip_failed',error.message));}
                }

                async function createFolderNow(){
                  if(!REMOTE_MANAGEMENT)return;
                  const name=await openDialog({title:t('web_new_folder'),message:t('web_new_folder_name'),input:true,placeholder:t('web_new_folder')});
                  if(!name)return;
                  apiPost('/api/mkdir?path='+encodeURIComponent(CURRENT_PATH)+'&name='+encodeURIComponent(name),[])
                    .then(function(){showConnectionBanner(t('web_folder_created'),'ok',false);setTimeout(function(){location.reload();},300);})
                    .catch(function(error){alert(t('web_create_failed',error.message));});
                }

                let contextMenuCard = null;
                let contextMenuX = 8;
                let contextMenuY = 8;

                function positionContextMenu(){
                  const menu=document.getElementById('contextMenu');
                  if(!menu || !menu.classList.contains('open'))return;
                  menu.style.left='0px';menu.style.top='0px';
                  const rect=menu.getBoundingClientRect();
                  menu.style.left=Math.max(8,Math.min(contextMenuX,window.innerWidth-rect.width-8))+'px';
                  menu.style.top=Math.max(8,Math.min(contextMenuY,window.innerHeight-rect.height-8))+'px';
                }

                function openContextMenuAt(card,x,y){
                  if(!REMOTE_MANAGEMENT || !card)return;
                  const menu=document.getElementById('contextMenu');
                  if(!menu)return;
                  contextMenuCard=card;
                  contextMenuX=x;contextMenuY=y;
                  const isFolder=card.dataset.dir==='1';
                  menu.querySelectorAll('.contextFileOnly').forEach(function(item){item.classList.toggle('hidden',isFolder);});
                  menu.querySelectorAll('.contextFolderOnly').forEach(function(item){item.classList.toggle('hidden',!isFolder);});
                  document.getElementById('contextOpenLabel').textContent=t(isFolder?'web_open':'web_preview');
                  const more=document.getElementById('contextMore');
                  if(more)more.classList.add('hidden');
                  const moreButton=menu.querySelector('[aria-expanded]');
                  if(moreButton)moreButton.setAttribute('aria-expanded','false');
                  menu.classList.add('open');
                  positionContextMenu();
                  const firstButton=menu.querySelector('button:not(.hidden)');
                  if(firstButton)firstButton.focus();
                }

                function openContextMenu(event,card){
                  event.preventDefault();event.stopPropagation();
                  openContextMenuAt(card,event.clientX,event.clientY);
                }

                function openContextMenuFromButton(event,card){
                  event.preventDefault();event.stopPropagation();
                  const rect=event.currentTarget.getBoundingClientRect();
                  openContextMenuAt(card,rect.right-225,rect.bottom+5);
                }

                function closeContextMenu(){
                  const menu=document.getElementById('contextMenu');
                  if(menu)menu.classList.remove('open');
                }

                function toggleContextMore(button){
                  const more=document.getElementById('contextMore');
                  if(!more)return;
                  const expanded=more.classList.toggle('hidden')===false;
                  button.setAttribute('aria-expanded',String(expanded));
                  positionContextMenu();
                }

                function moveContextMenuFocus(delta){
                  const menu=document.getElementById('contextMenu');
                  if(!menu)return;
                  const buttons=Array.from(menu.querySelectorAll('button')).filter(function(item){return item.offsetParent!==null;});
                  if(buttons.length===0)return;
                  const current=buttons.indexOf(document.activeElement);
                  const base=current>=0?current:(delta>0?-1:0);
                  const next=(base+delta+buttons.length)%buttons.length;
                  buttons[next].focus();
                }

                function selectOnlyCard(card){
                  const check=card ? card.querySelector('.itemCheck') : null;
                  if(!check)return false;
                  clearSelection();check.checked=true;selectionChanged();
                  return true;
                }

                async function copyTextValue(value){
                  try{
                    if(navigator.clipboard && window.isSecureContext){
                      await navigator.clipboard.writeText(value);
                      return;
                    }
                    const field=document.createElement('textarea');
                    field.value=value;field.setAttribute('readonly','');
                    field.style.position='fixed';field.style.opacity='0';
                    document.body.appendChild(field);field.select();
                    const copied=document.execCommand('copy');field.remove();
                    if(!copied)throw new Error('Clipboard unavailable');
                  }catch(error){throw error;}
                }

                async function renameItem(card){
                  if(!card)return;
                  const name=card.dataset.name || '';
                  const newName=await openDialog({title:t('web_action_rename'),message:t('web_new_name'),input:true,value:name});
                  if(!newName || newName===name)return;
                  apiPost('/api/rename?path='+encodeURIComponent(card.dataset.path || '')+'&name='+encodeURIComponent(newName),[])
                    .then(function(){location.reload();}).catch(function(error){alert(t('web_rename_failed',error.message));});
                }

                function showItemDetails(card){
                  if(!card)return;
                  const isFolder=card.dataset.dir==='1';
                  const rows=[
                    [t('web_detail_name'),card.dataset.name || ''],
                    [t('web_detail_path'),'/' + (card.dataset.path || '')],
                    [t('web_detail_size'),isFolder?'—':humanBytes(card.dataset.size || 0)],
                    [t('web_detail_type'),isFolder?t('web_folder'):(card.dataset.type || '')],
                    [t('web_detail_modified'),new Date(Number(card.dataset.time || 0)).toLocaleString()]
                  ];
                  openManagerModal(t('web_details'));
                  document.getElementById('managerBody').innerHTML=rows.map(function(row){
                    return '<div class="managerRow"><div class="managerRowMain"><div class="managerMeta">'+escapeText(row[0])+'</div><div class="managerName">'+escapeText(row[1])+'</div></div></div>';
                  }).join('');
                }

                async function runContextAction(action){
                  const card=contextMenuCard;
                  closeContextMenu();
                  if(!card)return;
                  if(action==='open'){
                    if(card.dataset.dir==='1')location.href=card.href;
                    else handleFile(card);
                    return;
                  }
                  if(action==='download'){
                    const link=document.createElement('a');link.href=card.dataset.downloadUrl || '';
                    link.download=card.dataset.name || '';document.body.appendChild(link);link.click();link.remove();return;
                  }
                  if(action==='copy-link'){
                    try{await copyTextValue(new URL(card.dataset.downloadUrl || '',location.href).href);showConnectionBanner(t('web_link_copied'),'ok',false);}
                    catch(error){alert(t('web_copy_failed',error.message));}
                    return;
                  }
                  if(action==='copy-name'){
                    try{await copyTextValue(card.dataset.name || '');showConnectionBanner(t('web_name_copied'),'ok',false);}
                    catch(error){alert(t('web_copy_failed',error.message));}
                    return;
                  }
                  if(action==='details'){showItemDetails(card);return;}
                  if(action==='rename'){renameItem(card);return;}
                  if(!selectOnlyCard(card))return;
                  if(action==='copy' || action==='move'){startTransferOperation(action);return;}
                  if(action==='zip'){downloadSelectedZip(false);return;}
                  if(action==='trash'){deleteSelected(false);return;}
                }

                function handleCardKey(event,card){
                  if(REMOTE_MANAGEMENT && (event.key==='ContextMenu' || (event.shiftKey && event.key==='F10'))){
                    event.preventDefault();
                    const rect=card.getBoundingClientRect();
                    openContextMenuAt(card,rect.left+Math.min(36,rect.width/2),rect.top+Math.min(36,rect.height/2));
                    return;
                  }
                  if(card.dataset.dir!=='1' && event.target===card && (event.key==='Enter' || event.key===' ')){
                    event.preventDefault();handleFile(card);
                  }
                }

                document.addEventListener('click',function(event){
                  const menu=document.getElementById('contextMenu');
                  if(menu && menu.classList.contains('open') && !menu.contains(event.target))closeContextMenu();
                });
                window.addEventListener('resize',closeContextMenu);
                window.addEventListener('scroll',closeContextMenu,true);

                let pendingTransferKind=null;
                let destinationPath='';
                function startTransferOperation(kind){
                  if(!REMOTE_MANAGEMENT)return;
                  if(selectedPaths().length===0){alert(t('web_select_items'));return;}
                  pendingTransferKind=kind;
                  destinationPath=CURRENT_PATH;
                  openManagerModal(kind==='copy'?t('web_choose_copy_target'):t('web_choose_move_target'));
                  loadDestination(destinationPath);
                }

                function openManagerModal(title){
                  const modal=document.getElementById('managerModal');
                  if(!modal)return;
                  if(!modal.classList.contains('open'))managerReturnFocus=document.activeElement;
                  document.getElementById('managerTitle').textContent=title;
                  modal.classList.add('open');syncBodyLock();
                  setTimeout(function(){const close=modal.querySelector('.iconBtn');if(close)close.focus();},30);
                }
                function closeManagerModal(){
                  const modal=document.getElementById('managerModal');if(modal)modal.classList.remove('open');
                  if(operationPollTimer){clearTimeout(operationPollTimer);operationPollTimer=null;}
                  syncBodyLock();restoreFocus(managerReturnFocus);managerReturnFocus=null;
                }
                function managerBackdrop(event){if(event.target.id==='managerModal')closeManagerModal();}

                async function loadDestination(path){
                  const body=document.getElementById('managerBody');
                  body.innerHTML='<div class="managerMeta">'+escapeText(t('web_loading_folders'))+'</div>';
                  try{
                    const response=await fetch('/api/tree?path='+encodeURIComponent(path),{cache:'no-store'});
                    const data=await response.json();
                    if(!response.ok)throw new Error(data.message||('HTTP '+response.status));
                    destinationPath=data.path||'';
                    const parent=destinationPath.split('/').slice(0,-1).join('/');
                    let html='<div class="managerRow"><div class="managerRowMain"><div class="managerName">'+escapeText(t('web_target',destinationPath))+'</div><div class="managerMeta">'+escapeText(t('web_target_hint'))+'</div></div><div class="managerActions"><button onclick="confirmDestination()">'+escapeText(t('web_use_here'))+'</button>';
                    if(destinationPath)html+='<button onclick="loadDestination('+JSON.stringify(parent)+')">'+escapeText(t('web_parent'))+'</button>';
                    html+='</div></div>';
                    (data.items||[]).forEach(function(item){
                      html+='<div class="managerRow"><div class="managerRowMain"><div class="managerName">📁 '+escapeText(item.name)+'</div><div class="managerMeta">/'+escapeText(item.path)+'</div></div><div class="managerActions"><button onclick="loadDestination('+JSON.stringify(item.path)+')">'+escapeText(t('web_open_folder'))+'</button></div></div>';
                    });
                    body.innerHTML=html;
                  }catch(error){body.innerHTML='<div class="managerMeta">'+escapeText(t('web_load_failed',error.message))+'</div>';}
                }

                async function conflictPolicy(){
                  const value=await openDialog({
                    title:t('web_conflict_title'),
                    message:t('web_conflict_prompt'),
                    choices:[
                      {value:'rename',label:t('web_conflict_rename')},
                      {value:'overwrite',label:t('web_conflict_overwrite')},
                      {value:'skip',label:t('web_conflict_skip')}
                    ],
                    choice:'rename'
                  });
                  if(value===null)return null;
                  const normalized=value.toLowerCase().trim();
                  return ['rename','overwrite','skip'].includes(normalized)?normalized:'rename';
                }

                async function confirmDestination(){
                  const policy=await conflictPolicy();if(policy===null)return;
                  const kind=pendingTransferKind;
                  const paths=selectedPaths();
                  closeManagerModal();
                  try{
                    await apiPost('/api/'+kind+'?dest='+encodeURIComponent(destinationPath)+'&conflict='+policy,paths);
                    showConnectionBanner(kind==='copy'?t('web_copy_started'):t('web_move_started'),'ok',false);
                    clearSelection();
                    setTimeout(openOperations,350);
                  }catch(error){alert(t('web_submit_failed',error.message));}
                }

                async function deleteSelected(permanent){
                  if(!REMOTE_MANAGEMENT)return;
                  const paths=selectedPaths();
                  if(paths.length===0){alert(t('web_select_items'));return;}
                  const message=permanent?t('web_delete_permanent_confirm'):t('web_delete_trash_confirm');
                  if(!await confirmDialog(permanent?t('web_permanent_delete'):t('web_move_trash'),message,permanent))return;
                  try{
                    await apiPost('/api/delete?permanent='+(permanent?'1':'0'),paths);
                    showConnectionBanner(t('web_delete_started'),'ok',false);clearSelection();setTimeout(openOperations,350);
                  }catch(error){alert(t('web_delete_failed',error.message));}
                }

                async function openTrash(){
                  if(!REMOTE_MANAGEMENT)return;
                  openManagerModal(t('web_trash'));
                  const body=document.getElementById('managerBody');body.innerHTML='<div class="managerMeta">'+escapeText(t('web_loading'))+'</div>';
                  try{
                    const response=await fetch('/api/trash',{cache:'no-store'});const data=await response.json();
                    let html='<div class="managerRow"><div class="managerRowMain"><div class="managerName">'+escapeText(t('web_trash_count',(data.items||[]).length))+'</div><div class="managerMeta">'+escapeText(t('web_restore_hint'))+'</div></div><div class="managerActions"><button onclick="emptyTrashNow()">'+escapeText(t('web_empty_trash_action'))+'</button></div></div>';
                    if((data.items||[]).length===0)html+='<div class="managerMeta">'+escapeText(t('web_empty_trash'))+'</div>';
                    (data.items||[]).forEach(function(item){
                      html+='<div class="managerRow"><div class="managerRowMain"><div class="managerName">'+(item.isDirectory?'📁 ':'📄 ')+escapeText(item.name)+'</div><div class="managerMeta">'+escapeText(t('web_original_location',item.originalPath,humanBytes(item.size),new Date(item.deletedAtMs).toLocaleString()))+'</div></div><div class="managerActions"><button onclick="restoreTrash('+JSON.stringify(item.id)+')">'+escapeText(t('web_restore'))+'</button><button onclick="deleteTrashPermanently('+JSON.stringify(item.id)+')">'+escapeText(t('web_delete_forever'))+'</button></div></div>';
                    });body.innerHTML=html;
                  }catch(error){body.innerHTML='<div class="managerMeta">'+escapeText(t('web_load_failed',error.message))+'</div>';}
                }

                async function restoreTrash(id){
                  try{await apiPost('/api/restore?conflict=rename',[id]);showConnectionBanner(t('web_restore_started'),'ok',false);setTimeout(openOperations,350);}catch(error){alert(t('web_restore_failed',error.message));}
                }
                async function deleteTrashPermanently(id){
                  if(!await confirmDialog(t('web_delete_forever'),t('web_delete_trash_item_confirm'),true))return;
                  try{await apiPost('/api/trash/delete',[id]);openTrash();}catch(error){alert(t('web_delete_failed',error.message));}
                }
                async function emptyTrashNow(){
                  if(!await confirmDialog(t('web_empty_trash_action'),t('web_empty_trash_confirm'),true))return;
                  try{await apiPost('/api/trash/empty',[]);openTrash();}catch(error){alert(t('web_empty_failed',error.message));}
                }

                let operationPollTimer=null;
                async function openOperations(){
                  openManagerModal(t('web_task_center'));
                  await refreshOperations();
                }
                async function refreshOperations(){
                  const body=document.getElementById('managerBody');
                  try{
                    const response=await fetch('/api/operations',{cache:'no-store'});const data=await response.json();
                    let active=false;let html='';
                    if((data.items||[]).length===0)html='<div class="managerMeta">'+escapeText(t('web_no_operations'))+'</div>';
                    (data.items||[]).forEach(function(item){
                      if(item.cancellable)active=true;
                      const percent=item.totalBytes>0?Math.min(100,item.processedBytes*100/item.totalBytes):0;
                      html+='<div class="managerRow"><div class="managerRowMain"><div class="managerName">'+escapeText(item.kindName)+' · '+escapeText(t('web_state_'+item.state))+'</div><div class="managerMeta">'+escapeText(item.message)+' · '+item.processedItems+'/'+item.totalItems+' '+escapeText(t('web_items',item.totalItems))+' · '+humanBytes(item.processedBytes)+' / '+humanBytes(item.totalBytes)+' · '+humanBytes(item.bytesPerSecond)+'/s</div><div class="progressTrack"><div class="progressBar" style="width:'+percent.toFixed(1)+'%"></div></div></div><div class="managerActions">'+(item.cancellable?'<button onclick="cancelOperation('+item.id+')">'+escapeText(t('web_cancel'))+'</button>':'')+'</div></div>';
                    });
                    body.innerHTML=html;
                    if(operationPollTimer)clearTimeout(operationPollTimer);
                    if(active && document.getElementById('managerModal').classList.contains('open'))operationPollTimer=setTimeout(refreshOperations,700);
                  }catch(error){body.innerHTML='<div class="managerMeta">'+escapeText(t('web_load_failed',error.message))+'</div>';}
                }
                async function cancelOperation(id){
                  try{await apiPost('/api/operations/cancel?id='+id,[]);refreshOperations();}catch(error){alert(t('web_cancel_failed',error.message));}
                }

                async function openHistory(){
                  openManagerModal(t('web_history_title'));
                  const body=document.getElementById('managerBody');
                  body.innerHTML='<div class="managerMeta">'+escapeText(t('web_loading'))+'</div>';
                  try{
                    const response=await fetch('/api/history',{cache:'no-store'});
                    const data=await response.json();
                    if(!response.ok)throw new Error(data.message||('HTTP '+response.status));
                    const items=data.items||[];
                    if(items.length===0){body.innerHTML='<div class="managerMeta">'+escapeText(t('web_history_empty'))+'</div>';return;}
                    body.innerHTML=items.map(function(item){
                      const time=new Date(item.timestampMs||0).toLocaleString();
                      const meta=[
                        time,
                        item.path ? '/' + item.path : '',
                        item.clientAddress || '',
                        item.bytes ? humanBytes(item.bytes) : '',
                        item.itemCount > 1 ? t('web_items',item.itemCount) : ''
                      ].filter(Boolean).join(' · ');
                      return '<div class="managerRow"><div class="managerRowMain"><div class="managerName">'+escapeText(item.kindName || item.kind)+' · '+escapeText(item.name || '')+'</div><div class="managerMeta">'+escapeText(meta)+'</div></div></div>';
                    }).join('');
                  }catch(error){body.innerHTML='<div class="managerMeta">'+escapeText(t('web_load_failed',error.message))+'</div>';}
                }

                window.addEventListener('offline',function(){
                  wasDisconnected = true;
                  setSyncState(t('web_device_offline'),false);
                  showConnectionBanner(t('web_network_offline'),'error',true);
                });
                window.addEventListener('online',function(){
                  setSyncState(t('web_reconnecting'),false);
                  if(livePollTimer){clearTimeout(livePollTimer);livePollTimer=null;}
                  pollLiveStatus();
                });

                applyTheme(localStorage.getItem('speedshare-theme') || 'auto');
                setView(localStorage.getItem('speedshare-view') || 'grid');
                const savedSort = localStorage.getItem('speedshare-sort');
                if(savedSort && document.getElementById('sort')) document.getElementById('sort').value = savedSort;
                applyFilters();
                installUpload();
                pollPhoneClipboard();
                connectLiveEvents();
                pollLiveStatus();
                window.addEventListener('beforeunload',function(){
                  if(liveEvents) liveEvents.close();
                  if(livePollTimer) clearTimeout(livePollTimer);
                  if(clipboardPollTimer) clearTimeout(clipboardPollTimer);
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildItem(
        item: WebItem,
        allowSelection: Boolean,
        remoteManagementEnabled: Boolean,
        tr: Translator
    ): String {
        val escapedName = escapeHtml(item.name)
        val escapedMime = escapeHtml(item.mimeType)
        val escapedPath = escapeHtml(item.relativePath)
        val details = escapeHtml(
            "${item.displayPath} · ${formatBytes(item.size)} · ${formatDate(item.modifiedAt)} · ${item.mimeType}"
        )
        val contextMenuAction = if (remoteManagementEnabled) {
            " oncontextmenu=\"openContextMenu(event,this)\""
        } else {
            ""
        }
        val selector = if (allowSelection) {
            "<label class=\"selectBox\" onclick=\"event.stopPropagation()\"><input type=\"checkbox\" class=\"itemCheck\" onclick=\"event.stopPropagation()\" onchange=\"selectionChanged(this.closest('.item'))\"><span>✓</span></label>"
        } else {
            ""
        }

        if (item.isDirectory) {
            val manageButton = if (remoteManagementEnabled) {
                "<button class=\"miniManage\" type=\"button\" onclick=\"openContextMenuFromButton(event,this.closest('.item'))\">${escapeHtml(tr.text("web_manage"))}</button>"
            } else {
                ""
            }
            return """
                <a class="card item folder-card" data-dir="1" data-name="$escapedName" data-path="$escapedPath" data-size="0" data-time="${item.modifiedAt}" data-type="folder" data-kind="folder" href="${escapeHtml(item.openUrl)}"$contextMenuAction onclick="handleFolderClick(event,this)" onkeydown="handleCardKey(event,this)">
                  $selector
                  <div class="folderIcon">📁</div>
                  <div class="folderInfo">
                    <div class="name" title="$escapedName">$escapedName</div>
                    <div class="sub">${escapeHtml(tr.text("web_folder"))} · ${escapeHtml(formatDate(item.modifiedAt))}</div>
                  </div>
                  $manageButton
                  <div class="folderArrow">›</div>
                </a>
            """.trimIndent()
        }

        val thumbnail = if (item.thumbnailUrl != null) {
            """
                <img loading="lazy" src="${escapeHtml(item.thumbnailUrl)}" alt="$escapedName" onload="this.parentElement.classList.remove('loading')" onerror="this.parentElement.classList.remove('loading');this.style.display='none';this.nextElementSibling.style.display='flex'">
                <div class="fallback" style="display:none">${iconForName(item.name, item.mimeType)}</div>
            """.trimIndent()
        } else {
            "<div class=\"fallback\">${iconForName(item.name, item.mimeType)}</div>"
        }
        val thumbnailClass = if (item.thumbnailUrl != null) "thumb loading" else "thumb"

        val badge = when (item.previewKind) {
            PreviewKind.VIDEO -> "<div class=\"mediaBadge\">${escapeHtml(tr.text("web_video"))}</div>"
            PreviewKind.AUDIO -> "<div class=\"mediaBadge\">${escapeHtml(tr.text("web_audio"))}</div>"
            PreviewKind.PDF -> "<div class=\"mediaBadge\">PDF</div>"
            else -> ""
        }

        val previewAction = if (item.previewKind == PreviewKind.DOWNLOAD) {
            "<a class=\"secondary\" href=\"${escapeHtml(item.downloadUrl.orEmpty())}\">${escapeHtml(tr.text("web_open"))}</a>"
        } else {
            "<button type=\"button\" onclick=\"event.stopPropagation();handleFile(this.closest('.file-card'))\">${escapeHtml(tr.text("web_preview"))}</button>"
        }
        val manageAction = if (remoteManagementEnabled) {
            "<button type=\"button\" onclick=\"openContextMenuFromButton(event,this.closest('.file-card'))\">${escapeHtml(tr.text("web_manage"))}</button>"
        } else {
            ""
        }

        return """
            <article class="card item file-card"
                     role="button"
                     tabindex="0"
                     aria-label="$escapedName"
                     data-dir="0"
                     data-name="$escapedName"
                     data-path="$escapedPath"
                     data-size="${item.size.coerceAtLeast(0L)}"
                     data-time="${item.modifiedAt}"
                     data-type="$escapedMime"
                     data-kind="${item.previewKind.webValue}"
                     data-preview-url="${escapeHtml(item.previewUrl.orEmpty())}"
                     data-download-url="${escapeHtml(item.downloadUrl.orEmpty())}"
                     data-details="$details"
                     $contextMenuAction
                     onkeydown="handleCardKey(event,this)"
                     onclick="handleFile(this,event)">
              $selector
              <div class="$thumbnailClass">
                $thumbnail
                $badge
              </div>
              <div class="info">
                <div class="name" title="$escapedName">$escapedName</div>
                <div class="sub">${escapeHtml(formatBytes(item.size))} · ${escapeHtml(formatDate(item.modifiedAt))}</div>
                <div class="actions">
                  $previewAction
                  <a class="download" href="${escapeHtml(item.downloadUrl.orEmpty())}" download="$escapedName" onclick="event.stopPropagation()">${escapeHtml(tr.text("web_download"))}</a>
                  $manageAction
                </div>
              </div>
            </article>
        """.trimIndent()
    }

    private fun buildSelectedDownloadPanel(tr: Translator): String {
        return """
          <section class="managementBox">
            <div class="managementTop">
              <div>
                <div class="managementTitle">${escapeHtml(tr.text("web_batch_download_title"))}</div>
                <div class="uploadHint">${escapeHtml(tr.text("web_batch_download_hint"))}</div>
              </div>
              <div class="managementButtons">
                <button class="secondary" type="button" onclick="toggleSelectAll()">${escapeHtml(tr.text("web_select_all"))}</button>
                <button class="secondary" type="button" onclick="openHistory()">${escapeHtml(tr.text("web_history_short"))}</button>
              </div>
            </div>
            <div id="selectionBar" class="selectionBar hidden">
              <strong id="selectionCount">${escapeHtml(tr.text("web_selected_count", 0))}</strong>
              <button type="button" onclick="downloadSelectedSeparately()">${escapeHtml(tr.text("web_download_separately"))}</button>
              <button type="button" onclick="downloadSelectedZip(false)">${escapeHtml(tr.text("web_zip_fast"))}</button>
              <button type="button" onclick="downloadSelectedZip(true)">${escapeHtml(tr.text("web_zip_compress"))}</button>
              <button type="button" onclick="clearSelection()">${escapeHtml(tr.text("web_clear_selection"))}</button>
            </div>
          </section>
        """.trimIndent()
    }

    private fun buildManagementPanel(
        relativePath: String,
        remoteManagementEnabled: Boolean,
        tr: Translator
    ): String {
        val managementButtons = if (remoteManagementEnabled) {
            """
              <button class="secondary" type="button" onclick="createFolderNow()">${escapeHtml(tr.text("web_new_folder"))}</button>
              <button class="secondary" type="button" onclick="openTrash()">${escapeHtml(tr.text("web_trash"))}</button>
              <button class="secondary" type="button" onclick="openOperations()">${escapeHtml(tr.text("web_task_center_short"))}</button>
              <button class="secondary" type="button" onclick="openHistory()">${escapeHtml(tr.text("web_history_short"))}</button>
            """.trimIndent()
        } else {
            """
              <button class="secondary" type="button" onclick="openHistory()">${escapeHtml(tr.text("web_history_short"))}</button>
              <span class="readOnlyHint">${escapeHtml(tr.text("web_readonly_hint"))}</span>
            """.trimIndent()
        }

        val destructiveButtons = if (remoteManagementEnabled) {
            """
              <button type="button" onclick="startTransferOperation('copy')">${escapeHtml(tr.text("op_copy"))}</button>
              <button type="button" onclick="startTransferOperation('move')">${escapeHtml(tr.text("web_move"))}</button>
              <button class="danger" type="button" onclick="deleteSelected(false)">${escapeHtml(tr.text("web_move_trash"))}</button>
              <button class="dangerGhost" type="button" onclick="deleteSelected(true)">${escapeHtml(tr.text("web_permanent_delete"))}</button>
            """.trimIndent()
        } else {
            ""
        }
        val contextMenu = if (remoteManagementEnabled) {
            """
              <div id="contextMenu" class="contextMenu" role="menu" aria-label="${escapeHtml(tr.text("web_manage_title"))}">
                <button type="button" role="menuitem" onclick="runContextAction('open')"><span class="contextIcon">${svgIcon("open")}</span><span id="contextOpenLabel">${escapeHtml(tr.text("web_open"))}</span></button>
                <button class="contextFileOnly" type="button" role="menuitem" onclick="runContextAction('download')"><span class="contextIcon">${svgIcon("download")}</span><span>${escapeHtml(tr.text("web_download"))}</span></button>
                <button class="contextFileOnly" type="button" role="menuitem" onclick="runContextAction('copy-link')"><span class="contextIcon">${svgIcon("link")}</span><span>${escapeHtml(tr.text("web_copy_link"))}</span></button>
                <button type="button" role="menuitem" onclick="runContextAction('rename')"><span class="contextIcon">${svgIcon("edit")}</span><span>${escapeHtml(tr.text("web_action_rename"))}</span></button>
                <button type="button" role="menuitem" onclick="runContextAction('move')"><span class="contextIcon">${svgIcon("move")}</span><span>${escapeHtml(tr.text("web_action_move"))}</span></button>
                <button type="button" role="menuitem" aria-expanded="false" onclick="toggleContextMore(this)"><span class="contextIcon">${svgIcon("more")}</span><span>${escapeHtml(tr.text("web_context_more"))}</span></button>
                <div id="contextMore" class="contextMore hidden">
                  <div class="contextSeparator"></div>
                  <button type="button" role="menuitem" onclick="runContextAction('copy')"><span class="contextIcon">${svgIcon("copy")}</span><span>${escapeHtml(tr.text("web_action_copy"))}</span></button>
                  <button type="button" role="menuitem" onclick="runContextAction('copy-name')"><span class="contextIcon">${svgIcon("text")}</span><span>${escapeHtml(tr.text("web_copy_name"))}</span></button>
                  <button class="contextFolderOnly" type="button" role="menuitem" onclick="runContextAction('zip')"><span class="contextIcon">${svgIcon("archive")}</span><span>${escapeHtml(tr.text("web_zip_fast"))}</span></button>
                  <button type="button" role="menuitem" onclick="runContextAction('details')"><span class="contextIcon">${svgIcon("info")}</span><span>${escapeHtml(tr.text("web_details"))}</span></button>
                  <div class="contextSeparator"></div>
                  <button class="danger" type="button" role="menuitem" onclick="runContextAction('trash')"><span class="contextIcon">${svgIcon("trash")}</span><span>${escapeHtml(tr.text("web_action_trash"))}</span></button>
                </div>
              </div>
            """.trimIndent()
        } else {
            ""
        }

        return """
          <section class="managementBox">
            <div class="managementTop">
              <div>
                <div class="managementTitle">${escapeHtml(tr.text("web_management_title"))}</div>
                <div class="uploadHint">${escapeHtml(tr.text("web_current_folder", relativePath))}</div>
              </div>
              <div class="managementButtons">
                <button class="secondary" type="button" onclick="toggleSelectAll()">${escapeHtml(tr.text("web_select_all"))}</button>
                $managementButtons
              </div>
            </div>
            <div id="selectionBar" class="selectionBar hidden">
              <strong id="selectionCount">${escapeHtml(tr.text("web_selected_count", 0))}</strong>
              <button type="button" onclick="downloadSelectedSeparately()">${escapeHtml(tr.text("web_download_separately"))}</button>
              <button type="button" onclick="downloadSelectedZip(false)">${escapeHtml(tr.text("web_zip_fast"))}</button>
              <button type="button" onclick="downloadSelectedZip(true)">${escapeHtml(tr.text("web_zip_compress"))}</button>
              $destructiveButtons
              <button type="button" onclick="clearSelection()">${escapeHtml(tr.text("web_clear_selection"))}</button>
            </div>
          </section>

          $contextMenu
        """.trimIndent()
    }

    private fun buildBreadcrumbs(relativePath: String, tr: Translator): String {
        val normalized = relativePath.trim('/').replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotBlank() }
        val links = mutableListOf<String>()
        links += "<a href=\"/\">${escapeHtml(tr.text("web_internal_storage"))}</a>"

        var current = ""
        parts.forEach { part ->
            current = if (current.isEmpty()) part else "$current/$part"
            links += "<span>›</span><a href=\"/?path=${urlEncode(current)}\">${escapeHtml(part)}</a>"
        }

        return "<nav class=\"breadcrumbs\">${links.joinToString("")}</nav>"
    }

    private fun buildUploadPanel(relativePath: String, tr: Translator): String {
        return """
            <section class="uploadBox">
              <div class="uploadTitle">${escapeHtml(tr.text("web_upload_here"))}</div>
              <div id="dropZone" class="dropZone">
                <div id="dropPrompt" class="dropPrompt">${escapeHtml(tr.text("web_drop_files_here"))}</div>
                <div class="uploadHint">${escapeHtml(tr.text("web_upload_hint"))}</div>
                <div class="uploadActions">
                  <label class="secondary" style="cursor:pointer;flex:1;text-align:center">
                    ${escapeHtml(tr.text("web_choose_files"))}
                    <input id="uploadFiles" type="file" multiple style="display:none">
                  </label>
                  <label class="secondary" style="cursor:pointer;flex:1;text-align:center">
                    ${escapeHtml(tr.text("web_choose_folder"))}
                    <input id="uploadFolder" type="file" multiple webkitdirectory directory style="display:none">
                  </label>
                  <button id="uploadStartButton" class="primary" type="button" style="flex:1" onclick="uploadFilesNow()">${escapeHtml(tr.text("web_start_upload"))}</button>
                </div>
                <input id="uploadDirectory" type="hidden" value="${escapeHtml(relativePath)}">
                <div id="uploadProgress" class="uploadProgress" aria-hidden="true"><span></span></div>
                <div id="uploadStatus" class="uploadStatus" style="margin-top:10px">${escapeHtml(tr.text("web_no_files_selected"))}</div>
                <div class="uploadActions">
                  <button id="cancelUpload" class="secondary" type="button" style="display:none;flex:1" onclick="cancelUploadQueue()">${escapeHtml(tr.text("web_cancel_upload"))}</button>
                  <button id="retryUpload" class="secondary" type="button" style="display:none;flex:1" onclick="uploadFilesNow(true)">${escapeHtml(tr.text("web_retry_failed"))}</button>
                </div>
                <div id="uploadQueue" class="uploadQueue"></div>
              </div>
            </section>
        """.trimIndent()
    }

    private fun buildClipboardPanel(tr: Translator): String {
        return """
            <section class="managementBox">
              <div class="managementTitle">${escapeHtml(tr.text("web_clipboard_sync"))}</div>
              <div class="uploadHint">${escapeHtml(tr.text("web_clipboard_hint"))}</div>
              <textarea id="clipboardInput" class="clipboardText" maxlength="65536" placeholder="${escapeHtml(tr.text("web_clipboard_placeholder"))}"></textarea>
              <div class="uploadActions">
                <button class="primary" type="button" onclick="sendClipboardToPhone()">${escapeHtml(tr.text("web_clipboard_to_phone"))}</button>
                <button class="secondary" type="button" onclick="refreshPhoneClipboard()">${escapeHtml(tr.text("web_clipboard_from_phone"))}</button>
              </div>
              <div class="uploadHint" style="margin-top:10px">${escapeHtml(tr.text("web_clipboard_phone_text"))}</div>
              <div id="phoneClipboardText" class="clipboardOutput">${escapeHtml(tr.text("web_clipboard_empty"))}</div>
            </section>
        """.trimIndent()
    }

    private fun escapeJavaScript(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun svgIcon(name: String): String {
        val body = when (name) {
            "settings" -> "<circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.6v-.2h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z\"/>"
            "open" -> "<path d=\"M14 3h7v7M10 14 21 3M21 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h6\"/>"
            "download" -> "<path d=\"M12 3v12m0 0 5-5m-5 5-5-5M5 21h14\"/>"
            "link" -> "<path d=\"M10 13a5 5 0 0 0 7.1.1l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1M14 11a5 5 0 0 0-7.1-.1l-2 2A5 5 0 0 0 12 20l1.1-1.1\"/>"
            "edit" -> "<path d=\"M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z\"/>"
            "move" -> "<path d=\"M5 12h14m-6-6 6 6-6 6\"/>"
            "more" -> "<circle cx=\"5\" cy=\"12\" r=\"1\"/><circle cx=\"12\" cy=\"12\" r=\"1\"/><circle cx=\"19\" cy=\"12\" r=\"1\"/>"
            "copy" -> "<rect x=\"8\" y=\"8\" width=\"12\" height=\"12\" rx=\"2\"/><path d=\"M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2\"/>"
            "text" -> "<path d=\"M4 7V4h16v3M9 20h6M12 4v16\"/>"
            "archive" -> "<path d=\"M4 7h16v13H4zM3 3h18v4H3zM9 11h6\"/>"
            "info" -> "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 11v5m0-8h.01\"/>"
            "trash" -> "<path d=\"M3 6h18M8 6V4h8v2m3 0-1 14H6L5 6M10 10v6m4-6v6\"/>"
            else -> ""
        }
        return "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">$body</svg>"
    }

}
