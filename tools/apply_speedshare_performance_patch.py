from pathlib import Path

path = Path('app/src/main/java/com/alex/speedshare/SpeedShareServer.kt')
text = path.read_text(encoding='utf-8')

replacements = [
    (
        '    private val translator = Localization.translator(language)\n',
        '    private val translator = Localization.translator(language)\n'
        '    private val performance = TransferPerformanceSettingsStore.load(context)\n'
        '        .resolved(context)\n'
        '        .speedShare\n'
    ),
    (
        '    private val clientExecutor = ThreadPoolExecutor(\n'
        '        4,\n'
        '        MAX_CLIENTS,\n',
        '    private val clientExecutor = ThreadPoolExecutor(\n'
        '        min(4, performance.maxClients),\n'
        '        performance.maxClients,\n'
    ),
    (
        '                    socket.socket().sendBufferSize = 1024 * 1024\n'
        '                    socket.socket().receiveBufferSize = 1024 * 1024\n',
        '                    val socketBufferBytes = performance.socketBufferMb * 1024 * 1024\n'
        '                    socket.socket().sendBufferSize = socketBufferBytes\n'
        '                    socket.socket().receiveBufferSize = socketBufferBytes\n'
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'Expected exactly one match, found {count}: {old[:80]!r}')
    text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
print('SpeedShareServer performance patch applied successfully')
