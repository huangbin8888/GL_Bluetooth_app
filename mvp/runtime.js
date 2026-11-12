/* GL Bluetooth Caliper MVP - runtime adapters.
 * IndexedDB persistence, Web Bluetooth channels, file download, wake lock.
 * No UI logic here; app.js wires these into the approved P1-P7 shell.
 */
(function (root) {
  "use strict";
  var P = root.GL;
  var DB_NAME = "gl_caliper_mvp";
  var LS_KEY = "gl_caliper_mvp_records";

  /* ---------- persistence: IndexedDB -> localStorage -> memory ---------- */
  var Store = {
    backend: "memory",
    _db: null,
    _mem: [],
    /* A small mirror makes backend changes non-destructive: if a session had
       to degrade to localStorage and a later session gets IndexedDB back,
       the records are migrated instead of silently replaced by seed data. */
    _mirrorGet: function () {
      try {
        var a = JSON.parse(root.localStorage.getItem(LS_KEY + "_mirror") || "[]");
        return Array.isArray(a) ? a : [];
      } catch (e) { return []; }
    },
    _mirrorSet: function (recs) {
      try { root.localStorage.setItem(LS_KEY + "_mirror", JSON.stringify(recs)); }
      catch (e) { /* quota exceeded or storage blocked: primary backend still has the data */ }
    },
    init: function () {
      var self = this;
      return new Promise(function (resolve) {
        var settled = false, guard = null;
        var done = function (backend, db) {
          if (settled) return;
          settled = true;
          if (guard) root.clearTimeout(guard);
          self.backend = backend; self._db = db; resolve(backend);
        };
        var degrade = function () {
          try {
            root.localStorage.setItem("__gl_probe__", "1");
            root.localStorage.removeItem("__gl_probe__");
            done("localStorage", null);
          } catch (e) { done("memory", null); }
        };
        /* Private mode, blocked storage and some headless engines never fire
           the IDB callbacks. Never let startup hang on them. */
        guard = root.setTimeout(degrade, 400);
        if (!root.indexedDB) return degrade();
        var req;
        try { req = root.indexedDB.open(DB_NAME, 1); }
        catch (e) { return degrade(); }
        req.onupgradeneeded = function (ev) {
          var db = ev.target.result;
          if (!db.objectStoreNames.contains("measurements")) {
            db.createObjectStore("measurements", { keyPath: "id", autoIncrement: true });
          }
        };
        req.onsuccess = function () { done("indexeddb", req.result); };
        req.onerror = degrade;
        req.onblocked = degrade;
      });
    },
    load: function () {
      var self = this, primary;
      if (this.backend === "indexeddb") {
        primary = new Promise(function (resolve) {
          var settled = false;
          var finish = function (v) { if (settled) return; settled = true; root.clearTimeout(timer); resolve(v); };
          var timer = root.setTimeout(function () { finish([]); }, 300);
          try {
            var tx = self._db.transaction("measurements", "readonly");
            var get = tx.objectStore("measurements").getAll();
            get.onsuccess = function () { finish(get.result || []); };
            get.onerror = function () { finish([]); };
          } catch (e) { finish([]); }
        });
      } else if (this.backend === "localStorage") {
        try { primary = Promise.resolve(JSON.parse(root.localStorage.getItem(LS_KEY) || "[]")); }
        catch (e) { primary = Promise.resolve([]); }
      } else {
        primary = Promise.resolve(this._mem.slice());
      }
      return primary.then(function (rows) {
        if (rows && rows.length) return rows;
        var mirror = self._mirrorGet();
        if (mirror.length) {
          return self.replaceAll(mirror).then(function () { return mirror; });
        }
        return [];
      });
    },
    put: function (rec) {
      var self = this;
      if (this.backend === "indexeddb") {
        return new Promise(function (resolve) {
          try {
            var tx = self._db.transaction("measurements", "readwrite");
            var add = tx.objectStore("measurements").add(rec);
            add.onsuccess = function () { rec.id = add.result; resolve(rec); };
            add.onerror = function () { resolve(rec); };
          } catch (e) { resolve(rec); }
        });
      }
      if (this.backend === "localStorage") {
        try {
          var a = JSON.parse(root.localStorage.getItem(LS_KEY) || "[]");
          a.push(rec); root.localStorage.setItem(LS_KEY, JSON.stringify(a));
        } catch (e) { this._mem.push(rec); }
        return Promise.resolve(rec);
      }
      this._mem.push(rec);
      return Promise.resolve(rec);
    },
    replaceAll: function (recs) {
      var self = this;
      this._mirrorSet(recs);           // write-through mirror on every commit
      if (this.backend === "indexeddb") {
        return new Promise(function (resolve) {
          var settled = false;
          var finish = function (v) { if (settled) return; settled = true; root.clearTimeout(timer); resolve(v); };
          var timer = root.setTimeout(function () { finish(false); }, 300);
          try {
            var tx = self._db.transaction("measurements", "readwrite");
            var os = tx.objectStore("measurements");
            os.clear();
            recs.forEach(function (r) { os.add(r); });
            tx.oncomplete = function () { finish(true); };
            tx.onerror = function () { finish(false); };
          } catch (e) { finish(false); }
        });
      }
      if (this.backend === "localStorage") {
        try { root.localStorage.setItem(LS_KEY, JSON.stringify(recs)); } catch (e) { self._mem = recs.slice(); }
        return Promise.resolve(true);
      }
      this._mem = recs.slice();
      return Promise.resolve(true);
    }
  };

  /* ---------- Web Bluetooth: one GATT instance per channel slot ---------- */
  var BLE = {
    supported: function () { return !!(root.navigator && root.navigator.bluetooth); },
    secure: function () { return !!root.isSecureContext; },
    slots: {},                 // slotId -> { device, server, characteristic, buffer }
    onPayload: null,           // function(slotId, text)
    onDrop: null,              // function(slotId, reason)
    onLog: null,               // function(line)

    log: function (s) { if (this.onLog) this.onLog(s); },

    /** Ask the user to pick one GL-BLE# caliper and bind it to a free slot. */
    bind: function (slotId) {
      var self = this;
      if (!this.supported()) return Promise.reject(new Error("当前浏览器不支持 Web Bluetooth（需 Chrome / Edge 且 https 或 localhost）"));
      return root.navigator.bluetooth.requestDevice({
        filters: [{ namePrefix: P.NAME_PREFIX }],
        optionalServices: [P.SERVICE_UUID, 0xffb0]
      }).then(function (device) {
        self.log("CH" + slotId + " 选择设备 " + (device.name || device.id));
        return self._open(slotId, device);
      });
    },

    _open: function (slotId, device) {
      var self = this;
      self.log("CH" + slotId + " connectGatt");
      return device.gatt.connect()
        .then(function (server) {
          self.log("CH" + slotId + " discoverServices " + P.SERVICE_UUID);
          return server.getPrimaryService(P.SERVICE_UUID).then(function (svc) {
            return svc.getCharacteristic(P.CHAR_UUID).then(function (ch) { return { server: server, ch: ch }; });
          });
        })
        .then(function (r) {
          var slot = { device: device, server: r.server, characteristic: r.ch, buffer: new P.FrameBuffer() };
          self.slots[slotId] = slot;
          if (!self._handlers) self._handlers = {};
          if (self._handlers[slotId]) r.ch.removeEventListener("characteristicvaluechanged", self._handlers[slotId]);
          var handler = function (ev) {
            var v = ev.target.value;
            var bytes = (v instanceof Uint8Array) ? v
              : new Uint8Array(v.buffer, v.byteOffset || 0, v.byteLength);
            var text = new TextDecoder("utf-8").decode(bytes);
            self.log("CH" + slotId + " notify " + JSON.stringify(text));
            if (self.onPayload) self.onPayload(slotId, text);
          };
          self._handlers[slotId] = handler;
          r.ch.addEventListener("characteristicvaluechanged", handler);
          device.removeEventListener("gattserverdisconnected", self._dropHandlers && self._dropHandlers[slotId]);
          if (!self._dropHandlers) self._dropHandlers = {};
          var drop = function () {
            self.log("CH" + slotId + " gattserverdisconnected");
            if (self.onDrop) self.onDrop(slotId, "设备断开");
          };
          self._dropHandlers[slotId] = drop;
          device.addEventListener("gattserverdisconnected", drop);
          return r.ch.startNotifications().then(function () {
            self.log("CH" + slotId + " notify 订阅成功");
            return { name: device.name || device.id, id: device.id };
          });
        });
    },

    /** Re-attach after a drop: discoverServices + notify subscription again. */
    reconnect: function (slotId) {
      var slot = this.slots[slotId];
      if (!slot || !slot.device) return Promise.reject(new Error("该通道没有已绑定设备"));
      slot.buffer.reset();
      return this._open(slotId, slot.device);
    },

    write: function (slotId, cmd) {
      var slot = this.slots[slotId];
      if (!slot) return Promise.reject(new Error("通道未连接"));
      var data = new TextEncoder().encode(cmd);
      this.log("CH" + slotId + " write " + cmd);
      var ch = slot.characteristic;
      if (ch.writeValueWithoutResponse) {
        return ch.writeValueWithoutResponse(data).catch(function () {
          return ch.writeValueWithResponse ? ch.writeValueWithResponse(data) : Promise.resolve();
        });
      }
      return ch.writeValueWithResponse(data);
    },

    drop: function (slotId) {
      var slot = this.slots[slotId];
      if (!slot) return;
      try { slot.device.gatt.disconnect(); } catch (e) { }
      delete this.slots[slotId];
      this.log("CH" + slotId + " 主动断开");
    }
  };

  /* ---------- file export helpers ---------- */
  function download(filename, content, mime) {
    var blob = (content instanceof root.Blob) ? content : new root.Blob([content], { type: mime || "text/plain;charset=utf-8" });
    var url = root.URL.createObjectURL(blob);
    var a = root.document.createElement("a");
    a.href = url; a.download = filename;
    root.document.body.appendChild(a); a.click();
    root.setTimeout(function () { root.document.body.removeChild(a); root.URL.revokeObjectURL(url); }, 400);
  }

  function downloadCsv(filename, csv) { download(filename, csv, "text/csv;charset=utf-8"); }

  function downloadPng(svgNode, filename) {
    if (!svgNode) return Promise.reject(new Error("图表尚未渲染"));
    var xml = new root.XMLSerializer().serializeToString(svgNode);
    var src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(xml);
    return new Promise(function (resolve, reject) {
      var img = new root.Image();
      img.onload = function () {
        var scale = 2, canvas = root.document.createElement("canvas");
        canvas.width = img.width * scale; canvas.height = img.height * scale;
        var ctx = canvas.getContext("2d");
        ctx.fillStyle = "#ffffff"; ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        canvas.toBlob(function (blob) {
          if (!blob) return reject(new Error("PNG 编码失败"));
          download(filename, blob, "image/png"); resolve(true);
        }, "image/png");
      };
      img.onerror = function () { reject(new Error("SVG 转 PNG 失败")); };
      img.src = src;
    });
  }

  /* ---------- keep the screen on while capturing ---------- */
  var Wake = {
    _lock: null,
    supported: function () { return !!(root.navigator && root.navigator.wakeLock); },
    request: function () {
      var self = this;
      if (!this.supported() || this._lock) return Promise.resolve(false);
      return root.navigator.wakeLock.request("screen").then(function (lock) {
        self._lock = lock;
        lock.addEventListener("release", function () { self._lock = null; });
        return true;
      }).catch(function () { return false; });
    },
    release: function () {
      if (this._lock) { try { this._lock.release(); } catch (e) { } this._lock = null; }
    }
  };

  root.GLRuntime = { Store: Store, BLE: BLE, Wake: Wake, download: download, downloadCsv: downloadCsv, downloadPng: downloadPng };
})(typeof globalThis !== "undefined" ? globalThis : this);
