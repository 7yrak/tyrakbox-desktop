const { app, BrowserWindow, Menu, ipcMain } = require('electron');
const path = require('path');
const DEFAULT_SERVER_URL = 'http://10.51.10.17:8083';
let store;

function getTargetUrl() {
  return store.get('serverUrl', DEFAULT_SERVER_URL);
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1200,
    minHeight: 780,
    backgroundColor: '#f5f7fb',
    title: 'Tyrak Box',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  const target = getTargetUrl().replace(/\/$/, '');
  win.loadURL(target);

  const menu = Menu.buildFromTemplate([
    {
      label: 'Tyrak Box',
      submenu: [
        {
          label: 'Configurar servidor',
          click: () => openServerConfigWindow(win)
        },
        { type: 'separator' },
        { role: 'reload', label: 'Recargar' },
        { role: 'toggleDevTools', label: 'Herramientas de desarrollo' },
        { type: 'separator' },
        { role: 'quit', label: 'Salir' }
      ]
    }
  ]);

  Menu.setApplicationMenu(menu);
}

function openServerConfigWindow(parent) {
  const configWindow = new BrowserWindow({
    width: 520,
    height: 220,
    parent,
    modal: true,
    resizable: false,
    title: 'Configurar servidor',
    webPreferences: {
      contextIsolation: false,
      nodeIntegration: true,
    }
  });

  const current = getTargetUrl().replace(/"/g, '&quot;');
  const defaultUrl = DEFAULT_SERVER_URL.replace(/"/g, '&quot;');
  configWindow.loadURL(`data:text/html;charset=utf-8,
    <html>
      <body style="font-family:Segoe UI,Arial,sans-serif;background:#f5f7fb;margin:0;padding:24px;">
        <h2 style="margin:0 0 12px 0;color:#0f172a;">Servidor Tyrak Box</h2>
        <p style="margin:0 0 8px 0;color:#475569;">Ingresa la URL del backend o frontend que quieres abrir.</p>
        <p style="margin:0 0 14px 0;color:#64748b;font-size:13px;">Valor recomendado: ${defaultUrl}</p>
        <input id="serverUrl" value="${current}" style="width:100%;padding:12px 14px;border:1px solid #cbd5e1;border-radius:10px;font-size:14px;box-sizing:border-box;" />
        <div style="display:flex;justify-content:flex-end;gap:10px;margin-top:18px;">
          <button id="reset" style="padding:10px 16px;border:none;border-radius:10px;background:#cbd5e1;cursor:pointer;">Usar recomendado</button>
          <button id="cancel" style="padding:10px 16px;border:none;border-radius:10px;background:#e2e8f0;cursor:pointer;">Cancelar</button>
          <button id="save" style="padding:10px 16px;border:none;border-radius:10px;background:#0ea5e9;color:white;cursor:pointer;">Guardar</button>
        </div>
        <script>
          const { ipcRenderer } = require('electron');
          const defaultUrl = '${defaultUrl}';
          document.getElementById('cancel').onclick = () => window.close();
          document.getElementById('reset').onclick = () => {
            document.getElementById('serverUrl').value = defaultUrl;
          };
          document.getElementById('save').onclick = () => {
            ipcRenderer.send('save-server-url', document.getElementById('serverUrl').value);
            window.close();
          };
        </script>
      </body>
    </html>`);
}

ipcMain.on('save-server-url', (_event, serverUrl) => {
  if (typeof serverUrl === 'string' && serverUrl.trim()) {
    store.set('serverUrl', serverUrl.trim());
  }
});

async function init() {
  const { default: Store } = await import('electron-store');
  store = new Store({ name: 'tyrakbox-desktop' });

  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.whenReady().then(init);
