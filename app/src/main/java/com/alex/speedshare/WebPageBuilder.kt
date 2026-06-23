package com.alex.speedshare

object WebPageBuilder {

    fun buildSelectedPage(items: List<WebItem>, language: ResolvedLanguage, pageVersion: String): String {
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
            deleteToTrashByDefault = true,
            language = language,
            pageVersion = pageVersion
        )
    }

    fun buildDirectoryPage(
        displayPath: String,
        relativePath: String,
        items: List<WebItem>,
        uploadEnabled: Boolean,
        remoteManagementEnabled: Boolean,
        deleteToTrashByDefault: Boolean,
        language: ResolvedLanguage,
        pageVersion: String
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
            deleteToTrashByDefault = deleteToTrashByDefault,
            language = language,
            pageVersion = pageVersion
        )
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
        deleteToTrashByDefault: Boolean,
        language: ResolvedLanguage,
        pageVersion: String
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
              <meta name="theme-color" content="#4057d6">
              <title>${escapeHtml(title)} - SpeedShareWeb</title>
              <style>
                :root{color-scheme:light dark;--bg:#f4f6fc;--bg2:#eef1fb;--panel:rgba(255,255,255,.92);--panelSolid:#fff;--panel2:#f1f3fa;--text:#151a2d;--muted:#6d7488;--line:rgba(65,77,119,.14);--brand:#4057d6;--brand2:#7656b8;--success:#0b9f6e;--danger:#d04444;--shadow:0 12px 32px rgba(43,54,99,.09);--shadowHover:0 18px 42px rgba(43,54,99,.15);--radius:17px}
                @media(prefers-color-scheme:dark){:root{--bg:#090e1b;--bg2:#11172a;--panel:rgba(18,24,42,.9);--panelSolid:#12182a;--panel2:#1b2237;--text:#f1f3ff;--muted:#a3aac0;--line:rgba(190,199,235,.13);--brand:#8f9dff;--brand2:#c2a2ff;--success:#56d6a7;--danger:#ff7979;--shadow:0 14px 38px rgba(0,0,0,.28);--shadowHover:0 20px 48px rgba(0,0,0,.38)}}
                *{box-sizing:border-box}
                html{scroll-behavior:smooth}
                html,body{margin:0;min-height:100%;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans SC","Microsoft YaHei",sans-serif}
                body{background-image:radial-gradient(circle at 12% -8%,rgba(64,87,214,.14),transparent 32%),radial-gradient(circle at 94% 2%,rgba(118,86,184,.13),transparent 30%),linear-gradient(180deg,var(--bg2),var(--bg) 280px)}
                button,input,select{font:inherit}
                button,a{-webkit-tap-highlight-color:transparent}
                a{color:inherit}
                .wrap{max-width:1380px;margin:0 auto;padding:12px 14px 72px}
                .header{position:sticky;top:8px;z-index:30;background:var(--panel);backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);border:1px solid var(--line);border-radius:20px;padding:12px 14px;box-shadow:var(--shadow)}
                .titleRow{display:flex;align-items:center;justify-content:space-between;gap:12px}
                h1{font-size:22px;line-height:1.15;margin:0 0 3px;font-weight:850;letter-spacing:-.35px;background:linear-gradient(110deg,var(--brand),var(--brand2));-webkit-background-clip:text;background-clip:text;color:transparent}
                .subtitle{font-size:12px;color:var(--muted);overflow-wrap:anywhere}
                .badge{flex:0 0 auto;padding:6px 9px;border-radius:999px;background:var(--panel2);border:1px solid var(--line);font-size:11px;color:var(--muted);white-space:nowrap}
                .syncDot{display:inline-block;width:7px;height:7px;border-radius:50%;background:var(--success);margin-right:6px;box-shadow:0 0 0 4px rgba(11,159,110,.1)}
                .breadcrumbs{display:flex;align-items:center;gap:5px;flex-wrap:wrap;margin:9px 0 0;font-size:12px}
                .breadcrumbs a{text-decoration:none;color:var(--brand);background:var(--panel2);border:1px solid var(--line);padding:6px 9px;border-radius:9px;transition:.16s}
                .breadcrumbs a:hover{transform:translateY(-1px);border-color:var(--brand)}

                .livePanel{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:7px;margin:10px 0;background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:8px;box-shadow:var(--shadow)}
                .liveCard{background:var(--panel2);border:1px solid transparent;border-radius:12px;padding:8px 10px;min-width:0}
                .liveLabel{font-size:10px;color:var(--muted);margin-bottom:2px}
                .liveValue{font-size:14px;font-weight:850;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                .liveTransfers{grid-column:1/-1;font-size:11px;color:var(--muted);line-height:1.55;overflow-wrap:anywhere;padding:7px 10px}

                .connectionBanner{position:fixed;left:50%;bottom:16px;z-index:240;transform:translate(-50%,130%);opacity:0;transition:.22s;background:#151a2d;color:#fff;border-radius:12px;padding:10px 14px;box-shadow:0 14px 42px rgba(0,0,0,.34);font-size:12px;max-width:min(92vw,620px);text-align:center;pointer-events:none}
                .connectionBanner.show{transform:translate(-50%,0);opacity:1}
                .connectionBanner.error{background:#9f3030}.connectionBanner.ok{background:#087d58}

                .toolbar{display:grid;grid-template-columns:minmax(170px,1fr) auto auto;gap:7px;margin:10px 0}
                .control{min-height:38px;border:1px solid var(--line);background:var(--panel);color:var(--text);border-radius:11px;padding:0 11px;outline:none;box-shadow:0 4px 14px rgba(43,54,99,.04)}
                .control:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(64,87,214,.13)}
                .viewButtons{display:flex;border:1px solid var(--line);border-radius:11px;overflow:hidden;background:var(--panel)}
                .viewButtons button{border:0;background:transparent;color:var(--muted);padding:0 12px;cursor:pointer;transition:.15s}
                .viewButtons button.active{background:linear-gradient(120deg,var(--brand),var(--brand2));color:#fff}

                .uploadBox,.managementBox{background:var(--panel);border:1px solid var(--line);border-radius:17px;padding:10px;margin:10px 0;box-shadow:var(--shadow)}
                .dropZone{border:1.5px dashed var(--line);border-radius:13px;padding:12px;text-align:center;transition:.16s}
                .dropZone.drag{border-color:var(--brand);background:rgba(64,87,214,.08);transform:scale(.995)}
                .uploadTitle,.managementTitle{font-weight:800;margin-bottom:3px}
                .uploadHint,.uploadStatus,.readOnlyHint{font-size:12px;color:var(--muted);overflow-wrap:anywhere}
                .uploadActions,.managementButtons{display:flex;gap:7px;margin-top:9px;flex-wrap:wrap}
                .primary,.secondary{border:0;border-radius:10px;padding:9px 12px;font-weight:750;cursor:pointer;transition:.15s}
                .primary{background:linear-gradient(120deg,var(--brand),var(--brand2));color:#fff;box-shadow:0 7px 18px rgba(64,87,214,.2)}
                .secondary{background:var(--panel2);color:var(--text);border:1px solid var(--line)}
                .primary:active,.secondary:active,.actions a:active,.actions button:active{transform:scale(.97)}
                .managementTop{display:flex;align-items:center;justify-content:space-between;gap:10px}
                .selectionBar{position:sticky;bottom:10px;z-index:80;display:flex;align-items:center;gap:6px;flex-wrap:wrap;background:rgba(21,26,45,.94);color:#fff;border-radius:14px;padding:9px 10px;margin-top:9px;box-shadow:0 16px 44px rgba(0,0,0,.27);backdrop-filter:blur(14px)}
                .selectionBar.hidden{display:none}.selectionBar strong{margin-right:auto;font-size:12px}.selectionBar button{border:0;border-radius:9px;padding:7px 9px;background:rgba(255,255,255,.13);color:#fff;cursor:pointer;font-size:11px}.selectionBar .danger{background:#b83b3b}.selectionBar .dangerGhost{border:1px solid rgba(255,121,121,.55);color:#ffb2b2;background:transparent}

                .items{display:grid;gap:10px}
                .items.grid{grid-template-columns:repeat(auto-fill,minmax(150px,1fr))}
                .card{position:relative;background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);overflow:hidden;box-shadow:var(--shadow);min-width:0;transition:transform .17s ease,box-shadow .17s ease,border-color .17s ease}
                .card:hover{transform:translateY(-3px);box-shadow:var(--shadowHover);border-color:rgba(64,87,214,.28)}
                .card.selected{outline:2px solid var(--brand);outline-offset:-2px}
                .selectBox{position:absolute;top:8px;left:8px;z-index:12;width:27px;height:27px;border-radius:9px;background:rgba(15,20,35,.58);display:grid;place-items:center;color:#fff;backdrop-filter:blur(8px);cursor:pointer;border:1px solid rgba(255,255,255,.22)}
                .selectBox input{position:absolute;opacity:0}.selectBox span{opacity:.3;font-weight:900}.selectBox input:checked+span{opacity:1;color:#94f3ca}
                .folder-card{text-decoration:none;display:flex;align-items:center;gap:10px;padding:12px;min-height:72px}
                .folderIcon{width:42px;height:42px;display:grid;place-items:center;font-size:28px;background:linear-gradient(145deg,rgba(64,87,214,.13),rgba(118,86,184,.13));border-radius:12px;flex:0 0 auto}
                .folderInfo{min-width:0;flex:1}.folderArrow{font-size:21px;color:var(--muted);flex:0 0 auto}
                .items.grid .folder-card{display:grid;grid-template-columns:minmax(0,1fr) auto;grid-template-rows:auto auto;align-items:center;gap:8px;padding:10px;min-height:160px}
                .items.grid .folderIcon{grid-column:1/-1;width:100%;height:88px;font-size:46px;border-radius:13px}
                .items.grid .folderInfo{grid-column:1;grid-row:2;min-width:0}.items.grid .folder-card .miniManage{grid-column:2;grid-row:2;align-self:center}.items.grid .folderArrow{display:none}
                .thumb{position:relative;width:100%;aspect-ratio:4/3;background:linear-gradient(145deg,var(--panel2),rgba(64,87,214,.08));overflow:hidden;display:grid;place-items:center;cursor:pointer}
                .thumb img{width:100%;height:100%;object-fit:cover;display:block;animation:thumbIn .24s ease both}
                @keyframes thumbIn{from{opacity:0;transform:scale(1.025)}to{opacity:1;transform:scale(1)}}
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

                .modal{position:fixed;inset:0;z-index:150;background:rgba(3,6,15,.8);display:none;align-items:center;justify-content:center;padding:18px;backdrop-filter:blur(10px)}.modal.open{display:flex}
                .modalPanel{width:min(1050px,100%);height:min(90vh,840px);background:var(--panelSolid);border:1px solid var(--line);border-radius:20px;overflow:hidden;display:grid;grid-template-rows:auto minmax(0,1fr) auto;box-shadow:0 30px 100px rgba(0,0,0,.5)}
                .modalHeader,.modalFooter{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid var(--line)}.modalFooter{border-bottom:0;border-top:1px solid var(--line)}
                .modalTitle{flex:1;min-width:0;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.iconBtn{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:9px;padding:7px 9px;cursor:pointer;text-decoration:none;font-size:12px}
                .viewer{min-height:0;display:flex;align-items:center;justify-content:center;background:#05070d;overflow:auto}.viewer img,.viewer video{max-width:100%;max-height:100%;object-fit:contain}.viewer audio{width:min(720px,92%)}.viewer iframe{width:100%;height:100%;border:0;background:#fff}.details{flex:1;min-width:0;font-size:11px;color:var(--muted);overflow-wrap:anywhere}

                .managerModal{position:fixed;inset:0;z-index:180;background:rgba(3,6,15,.76);display:none;align-items:center;justify-content:center;padding:18px;backdrop-filter:blur(10px)}.managerModal.open{display:flex}
                .managerPanel{width:min(880px,100%);max-height:88vh;background:var(--panelSolid);border:1px solid var(--line);border-radius:20px;overflow:hidden;display:grid;grid-template-rows:auto minmax(0,1fr);box-shadow:0 28px 90px rgba(0,0,0,.45)}
                .managerHeader{display:flex;align-items:center;gap:10px;padding:11px 12px;border-bottom:1px solid var(--line)}.managerBody{overflow:auto;padding:12px}
                .managerRow{display:flex;align-items:center;gap:9px;padding:9px 0;border-bottom:1px solid var(--line)}.managerRow:last-child{border-bottom:0}.managerRowMain{flex:1;min-width:0}.managerName{font-weight:760;overflow-wrap:anywhere}.managerMeta{font-size:11px;color:var(--muted);margin-top:2px;overflow-wrap:anywhere}.managerActions{display:flex;gap:6px;flex-wrap:wrap}.managerActions button{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:8px;padding:6px 8px;cursor:pointer;font-size:11px}
                .progressTrack{height:6px;background:var(--panel2);border-radius:999px;overflow:hidden;margin-top:6px}.progressBar{height:100%;background:linear-gradient(90deg,var(--brand),var(--brand2));width:0%;transition:width .18s}
                .hidden{display:none!important}

                @media(max-width:760px){.wrap{padding:8px 8px 64px}.header{top:5px;padding:10px 11px;border-radius:16px}.badge{display:none}h1{font-size:20px}.livePanel{grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;padding:7px}.toolbar{grid-template-columns:1fr auto}.toolbar select{grid-column:1/-1;grid-row:2}.items.grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.items.grid .folder-card{min-height:145px}.items.grid .folderIcon{height:76px}.managementTop{align-items:flex-start;flex-direction:column}.managementButtons{margin-top:0}.selectionBar{bottom:7px}.modal,.managerModal{padding:0}.modalPanel,.managerPanel{width:100%;height:100%;max-height:none;border-radius:0}.actions{gap:4px}.actions a,.actions button{padding:6px 3px}.liveTransfers{font-size:10.5px}}
                @media(max-width:390px){.items.grid{grid-template-columns:repeat(2,minmax(0,1fr))}.info{padding:8px}.name{font-size:12px}.sub{font-size:10px}.actions a,.actions button{font-size:10px}.folderIcon{font-size:26px}}
                @media(hover:none){.card:hover{transform:none;box-shadow:var(--shadow)}}
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
                    <div class="badge"><span class="syncDot"></span><span id="syncState">${escapeHtml(tr.text("web_live"))}</span></div>
                  </div>
                  $breadcrumbs
                </header>

                <section class="livePanel" aria-label="${escapeHtml(tr.text("web_live"))}">
                  <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_connections"))}</div><div id="liveConnections" class="liveValue">0</div></div>
                  <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_send_speed"))}</div><div id="liveDownload" class="liveValue">0 B/s</div></div>
                  <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_receive_speed"))}</div><div id="liveUpload" class="liveValue">0 B/s</div></div>
                  <div class="liveCard"><div class="liveLabel">${escapeHtml(tr.text("web_active_tasks"))}</div><div id="liveTaskCount" class="liveValue">0</div></div>
                  <div id="liveTransfers" class="liveCard liveTransfers">${escapeHtml(tr.text("web_no_transfers"))}</div>
                </section>

                $uploadPanel
                $managementPanel

                <section class="toolbar">
                  <input id="search" class="control" type="search" placeholder="${escapeHtml(tr.text("web_search"))}" oninput="applyFilters()">
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
              </main>

              <div id="connectionBanner" class="connectionBanner">${escapeHtml(tr.text("web_connecting"))}</div>

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
                const DELETE_TO_TRASH_DEFAULT = ${deleteToTrashByDefault};
                let liveEvents = null;
                let livePollTimer = null;
                let consecutiveFailures = 0;
                let wasDisconnected = false;
                let bannerTimer = null;

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
                  document.getElementById('liveTaskCount').textContent = String(transfers.length);
                  const panel = document.getElementById('liveTransfers');
                  if(transfers.length === 0){panel.textContent = t('web_no_transfers');return;}
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
                    livePollTimer = setTimeout(pollLiveStatus,700);
                  }
                }

                const container = document.getElementById('items');
                let previewItems = [];
                let previewIndex = -1;
                let droppedUploadFiles = [];

                function setView(mode){
                  const chosen = mode === 'list' ? 'list' : 'grid';
                  container.classList.remove('grid','list');
                  container.classList.add(chosen);
                  document.getElementById('gridBtn').classList.toggle('active',chosen === 'grid');
                  document.getElementById('listBtn').classList.toggle('active',chosen === 'list');
                  localStorage.setItem('speedshare-view',chosen);
                }

                function applyFilters(){
                  const query = (document.getElementById('search').value || '').trim().toLocaleLowerCase();
                  const sort = document.getElementById('sort').value;
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
                  children.forEach(function(item){
                    container.appendChild(item);
                    const match = String(item.dataset.name || '').toLocaleLowerCase().includes(query);
                    item.classList.toggle('hidden',!match);
                    if(match) visible++;
                  });
                  document.getElementById('visibleCount').textContent = t('web_visible',visible);
                  refreshPreviewItems();
                }

                function refreshPreviewItems(){
                  previewItems = Array.from(container.querySelectorAll('.file-card:not(.hidden)')).filter(function(item){
                    return item.dataset.kind !== 'download';
                  });
                }

                function handleFile(element){
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
                    media.preload = 'metadata';
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

                  if(media) viewer.appendChild(media);
                  document.getElementById('modalTitle').textContent = item.dataset.name || '';
                  document.getElementById('modalDetails').textContent = item.dataset.details || '';
                  const download = document.getElementById('modalDownload');
                  download.href = item.dataset.downloadUrl;
                  download.setAttribute('download',item.dataset.name || 'download');
                  document.getElementById('modal').classList.add('open');
                  document.body.style.overflow = 'hidden';
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
                  document.body.style.overflow = '';
                }

                function modalBackdrop(event){
                  if(event.target.id === 'modal') closeModal();
                }

                document.addEventListener('keydown',function(event){
                  const modalOpen = document.getElementById('modal').classList.contains('open');
                  if(!modalOpen) return;
                  if(event.key === 'Escape') closeModal();
                  if(event.key === 'ArrowLeft') movePreview(-1);
                  if(event.key === 'ArrowRight') movePreview(1);
                });

                function humanBytes(value){
                  let size = Number(value || 0);
                  const units = ['B','KB','MB','GB','TB'];
                  let index = 0;
                  while(size >= 1024 && index < units.length - 1){size /= 1024;index++;}
                  return size.toFixed(index === 0 ? 0 : 2) + ' ' + units[index];
                }

                function installUpload(){
                  const zone = document.getElementById('dropZone');
                  const input = document.getElementById('uploadFiles');
                  if(!zone || !input) return;
                  ['dragenter','dragover'].forEach(function(name){
                    zone.addEventListener(name,function(event){event.preventDefault();zone.classList.add('drag');});
                  });
                  ['dragleave','drop'].forEach(function(name){
                    zone.addEventListener(name,function(event){event.preventDefault();zone.classList.remove('drag');});
                  });
                  zone.addEventListener('drop',function(event){
                    droppedUploadFiles = Array.from(event.dataTransfer.files || []);
                    updateUploadSelection();
                  });
                  input.addEventListener('change',function(){
                    droppedUploadFiles = [];
                    updateUploadSelection();
                  });
                }

                function getUploadFiles(){
                  const input = document.getElementById('uploadFiles');
                  if(droppedUploadFiles.length > 0) return droppedUploadFiles;
                  return input ? Array.from(input.files || []) : [];
                }

                function updateUploadSelection(){
                  const status = document.getElementById('uploadStatus');
                  const files = getUploadFiles();
                  if(status) status.textContent = t('web_selected_count',files.length);
                }

                function uploadOne(file,index,total){
                  return new Promise(function(resolve,reject){
                    const xhr = new XMLHttpRequest();
                    const directory = document.getElementById('uploadDirectory').value;
                    const url = '/upload?path=' + encodeURIComponent(directory) + '&name=' + encodeURIComponent(file.name);
                    const started = performance.now();
                    xhr.open('POST',url);
                    xhr.setRequestHeader('Content-Type','application/octet-stream');
                    xhr.upload.onprogress = function(event){
                      if(!event.lengthComputable) return;
                      const seconds = Math.max((performance.now() - started) / 1000,0.001);
                      const speed = event.loaded / seconds;
                      const percent = Math.round(event.loaded * 100 / event.total);
                      document.getElementById('uploadStatus').textContent =
                        t('web_upload_file_progress',index+1,total,file.name,percent,humanBytes(speed));
                    };
                    xhr.onload = function(){
                      if(xhr.status >= 200 && xhr.status < 300) resolve();
                      else reject(new Error(xhr.responseText || ('HTTP ' + xhr.status)));
                    };
                    xhr.onerror = function(){reject(new Error(t('web_network_failed')));};
                    xhr.send(file);
                  });
                }

                async function uploadFilesNow(){
                  const input = document.getElementById('uploadFiles');
                  const status = document.getElementById('uploadStatus');
                  if(!input || !status) return;
                  const files = getUploadFiles();
                  if(files.length === 0){status.textContent = t('web_choose_or_drop');return;}
                  try{
                    for(let i = 0;i < files.length;i++) await uploadOne(files[i],i,files.length);
                    status.textContent = t('web_upload_done');
                    setTimeout(function(){ location.reload(); },1500);
                  }catch(error){status.textContent = t('web_upload_failed',error.message);}
                }

                function selectedCards(){
                  return Array.from(document.querySelectorAll('.itemCheck:checked')).map(function(check){return check.closest('.item');}).filter(Boolean);
                }

                function selectedPaths(){
                  return selectedCards().map(function(card){return card.dataset.path || '';}).filter(Boolean);
                }

                function selectionChanged(){
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

                function clearSelection(){
                  document.querySelectorAll('.itemCheck').forEach(function(check){check.checked=false;});
                  selectionChanged();
                }

                function toggleSelectAll(){
                  const visible = Array.from(document.querySelectorAll('.item')).filter(function(item){return !item.classList.contains('hidden');});
                  const shouldSelect = visible.some(function(item){const check=item.querySelector('.itemCheck');return check && !check.checked;});
                  visible.forEach(function(item){const check=item.querySelector('.itemCheck');if(check) check.checked=shouldSelect;});
                  selectionChanged();
                }

                async function apiPost(url, lines){
                  const body = (lines || []).map(function(value){return encodeURIComponent(value);}).join('\n');
                  const response = await fetch(url,{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:body});
                  const text = await response.text();
                  if(!response.ok) throw new Error(text || ('HTTP ' + response.status));
                  if(!text) return {};
                  try{return JSON.parse(text);}catch(_){return {text:text};}
                }

                function downloadSelectedSeparately(){
                  const cards = selectedCards().filter(function(card){return card.dataset.dir !== '1';});
                  if(cards.length === 0){alert(t('web_select_file_zip_folder'));return;}
                  cards.forEach(function(card,index){
                    setTimeout(function(){
                      const link=document.createElement('a');
                      link.href=card.dataset.downloadUrl || '';
                      link.download=card.dataset.name || '';
                      document.body.appendChild(link);link.click();link.remove();
                    },index*350);
                  });
                }

                async function downloadSelectedZip(compress){
                  const paths=selectedPaths();
                  if(paths.length===0){alert(t('web_select_items'));return;}
                  const defaultName='SpeedShareWeb_' + new Date().toISOString().slice(0,19).replace(/[:T]/g,'-') + '.zip';
                  const name=prompt(t('web_zip_name'),defaultName);
                  if(name===null)return;
                  try{
                    const endpoint=DIRECTORY_MODE?'/api/zip/prepare':'/api/selected-zip/prepare';
                    const result=await apiPost(endpoint+'?mode='+(compress?'compress':'store')+'&name='+encodeURIComponent(name),paths);
                    if(!result.url)throw new Error(t('web_no_download_url'));
                    location.href=result.url;
                  }catch(error){alert(t('web_zip_failed',error.message));}
                }

                function createFolderNow(){
                  if(!REMOTE_MANAGEMENT)return;
                  const name=prompt(t('web_new_folder_name'));
                  if(!name)return;
                  apiPost('/api/mkdir?path='+encodeURIComponent(CURRENT_PATH)+'&name='+encodeURIComponent(name),[])
                    .then(function(){showConnectionBanner(t('web_folder_created'),'ok',false);setTimeout(function(){location.reload();},300);})
                    .catch(function(error){alert(t('web_create_failed',error.message));});
                }

                function manageOne(card){
                  if(!REMOTE_MANAGEMENT || !card)return;
                  const path=card.dataset.path || '';
                  const name=card.dataset.name || '';
                  const action=prompt(t('web_manage_prompt',name),DELETE_TO_TRASH_DEFAULT?'trash':'rename');
                  if(!action)return;
                  const normalized=action.toLowerCase().trim();
                  if(normalized==='rename'){
                    const newName=prompt(t('web_new_name'),name);
                    if(!newName || newName===name)return;
                    apiPost('/api/rename?path='+encodeURIComponent(path)+'&name='+encodeURIComponent(newName),[])
                      .then(function(){location.reload();}).catch(function(error){alert(t('web_rename_failed',error.message));});
                    return;
                  }
                  const check=card.querySelector('.itemCheck');
                  if(check){clearSelection();check.checked=true;selectionChanged();}
                  if(normalized==='copy' || normalized==='move'){startTransferOperation(normalized);return;}
                  if(normalized==='trash'){deleteSelected(false);return;}
                  if(normalized==='delete'){deleteSelected(true);return;}
                  alert(t('web_unsupported_action'));
                }

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
                  document.getElementById('managerTitle').textContent=title;
                  document.getElementById('managerModal').classList.add('open');
                }
                function closeManagerModal(){document.getElementById('managerModal').classList.remove('open');if(operationPollTimer){clearTimeout(operationPollTimer);operationPollTimer=null;}}

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

                function conflictPolicy(){
                  const value=prompt(t('web_conflict_prompt'),'rename');
                  if(value===null)return null;
                  const normalized=value.toLowerCase().trim();
                  return ['rename','overwrite','skip'].includes(normalized)?normalized:'rename';
                }

                async function confirmDestination(){
                  const policy=conflictPolicy();if(policy===null)return;
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
                  if(!confirm(message))return;
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
                  if(!confirm(t('web_delete_trash_item_confirm')))return;
                  try{await apiPost('/api/trash/delete',[id]);openTrash();}catch(error){alert(t('web_delete_failed',error.message));}
                }
                async function emptyTrashNow(){
                  if(!confirm(t('web_empty_trash_confirm')))return;
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

                setView(localStorage.getItem('speedshare-view') || 'grid');
                applyFilters();
                installUpload();
                connectLiveEvents();
                pollLiveStatus();
                window.addEventListener('beforeunload',function(){
                  if(liveEvents) liveEvents.close();
                  if(livePollTimer) clearTimeout(livePollTimer);
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
        val selector = if (allowSelection) {
            "<label class=\"selectBox\" onclick=\"event.stopPropagation()\"><input type=\"checkbox\" class=\"itemCheck\" onclick=\"event.stopPropagation()\" onchange=\"selectionChanged()\"><span>✓</span></label>"
        } else {
            ""
        }

        if (item.isDirectory) {
            val manageButton = if (remoteManagementEnabled) {
                "<button class=\"miniManage\" type=\"button\" onclick=\"event.preventDefault();event.stopPropagation();manageOne(this.closest('.item'))\">${escapeHtml(tr.text("web_manage"))}</button>"
            } else {
                ""
            }
            return """
                <a class="card item folder-card" data-dir="1" data-name="$escapedName" data-path="$escapedPath" data-size="0" data-time="${item.modifiedAt}" data-type="folder" href="${escapeHtml(item.openUrl)}">
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
                <img loading="lazy" src="${escapeHtml(item.thumbnailUrl)}" alt="$escapedName" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">
                <div class="fallback" style="display:none">${iconForName(item.name, item.mimeType)}</div>
            """.trimIndent()
        } else {
            "<div class=\"fallback\">${iconForName(item.name, item.mimeType)}</div>"
        }

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
            "<button type=\"button\" onclick=\"event.stopPropagation();manageOne(this.closest('.file-card'))\">${escapeHtml(tr.text("web_manage"))}</button>"
        } else {
            ""
        }

        return """
            <article class="card item file-card"
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
                     onclick="handleFile(this)">
              $selector
              <div class="thumb">
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
            """.trimIndent()
        } else {
            "<span class=\"readOnlyHint\">${escapeHtml(tr.text("web_readonly_hint"))}</span>"
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

          <div id="managerModal" class="managerModal">
            <div class="managerPanel">
              <div class="managerHeader">
                <div id="managerTitle" class="modalTitle">${escapeHtml(tr.text("web_file_management"))}</div>
                <button class="iconBtn" type="button" onclick="closeManagerModal()">${escapeHtml(tr.text("web_close"))}</button>
              </div>
              <div id="managerBody" class="managerBody"></div>
            </div>
          </div>
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
              <div id="dropZone" class="dropZone">
                <div class="uploadTitle">${escapeHtml(tr.text("web_upload_here"))}</div>
                <div class="uploadHint">${escapeHtml(tr.text("web_upload_hint"))}</div>
                <div class="uploadActions">
                  <label class="secondary" style="cursor:pointer;flex:1;text-align:center">
                    ${escapeHtml(tr.text("web_choose_files"))}
                    <input id="uploadFiles" type="file" multiple style="display:none">
                  </label>
                  <button class="primary" type="button" style="flex:1" onclick="uploadFilesNow()">${escapeHtml(tr.text("web_start_upload"))}</button>
                </div>
                <input id="uploadDirectory" type="hidden" value="${escapeHtml(relativePath)}">
                <div id="uploadStatus" class="uploadStatus" style="margin-top:10px">${escapeHtml(tr.text("web_no_files_selected"))}</div>
              </div>
            </section>
        """.trimIndent()
    }

    private fun escapeJavaScript(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

}
