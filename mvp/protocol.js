/* GL Bluetooth Caliper MVP - protocol & data layer.
 * No DOM access: can be required from Node for executable checks.
 * Source of truth: 广陆蓝牙设备数字化接口_通用.doc
 */
(function (root, factory) {
  var api = factory();
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GL = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  var SERVICE_UUID = "0000ffb0-0000-1000-8000-00805f9b34fb";
  var CHAR_UUID = "0000ffb2-0000-1000-8000-00805f9b34fb";
  var NAME_PREFIX = "GL-BLE#";
  var MAX_CHANNELS = 7;
  var UM_PER_MM = 1000;
  var UM_PER_INCH = 25400; // 1 inch = 25.4 mm

  /* Fixed 7-colour coding. Colour alone is never the only cue: every badge
     also carries CHn text and every CSV row carries the CHANNEL column. */
  var CH_COLORS = [
    "#1668E3", "#0F9D58", "#E8912A", "#8E44AD", "#00A6B8",
    "#E5384A", "#D24AA0"
  ];

  /* 15 base columns from templates/gl_caliper_default.csv + CHANNEL (M0 change) */
  var CSV_HEADERS = [
    "DATE", "TIME", "SEQ", "BATCH", "ITEM", "NOMINAL", "LOWER_TOL",
    "UPPER_TOL", "VALUE_MM", "RAW_VALUE", "RAW_UNIT", "VERDICT",
    "SOURCE", "DEVICE_MAC", "NOTE", "CHANNEL"
  ];
  var ALIASES = {
    "日期": "DATE", "时间": "TIME", "序号": "SEQ", "批次": "BATCH",
    "检验项目": "ITEM", "公称值": "NOMINAL", "下偏差": "LOWER_TOL",
    "上偏差": "UPPER_TOL", "实测值": "VALUE_MM", "测量值": "VALUE_MM",
    "原始值": "RAW_VALUE", "原始单位": "RAW_UNIT", "判定": "VERDICT",
    "来源": "SOURCE", "设备MAC": "DEVICE_MAC", "备注": "NOTE", "通道": "CHANNEL"
  };

  var ACK_RE = /(MEAS|CLR|MOD|NAM):(OK|ERR)/g;
  var FRAME_HEAD_RE = /DAT[01]:/;
  var FRAME_RE = /^DAT([01]):([mi])([+-])(\d+(?:\.\d+)?)/;
  var FRAME_FULL_RE = /^DAT([01]):([mi])([+-])(\d+(?:\.\d+)?)$/;
  var PARTIAL_HEAD_RE = /(?:^|[^A-Za-z0-9])(D|DA|DAT|DAT0|DAT1|DAT0:|DAT1:)$/;

  /** Parse one complete data frame. Returns null when the frame is incomplete. */
  function parseFrame(raw) {
    var m = FRAME_FULL_RE.exec(String(raw).trim());
    if (!m) return null;
    var n = parseFloat(m[4]);
    if (!isFinite(n)) return null;
    var factor = m[2] === "m" ? UM_PER_MM : UM_PER_INCH;
    var um = Math.round(n * factor) * (m[3] === "-" ? -1 : 1);
    return {
      kind: "DAT" + m[1],
      unit: m[2],
      sign: m[3],
      raw_value: n,
      value_um: um,
      raw: m[0]
    };
  }

  /**
   * Byte-stream reassembly. A notify payload may contain a partial frame,
   * several frames glued together, or device ACKs mixed in.
   * push() returns every complete frame/ack parsed so far.
   */
  function FrameBuffer() { this.buf = ""; }
  FrameBuffer.prototype.reset = function () { this.buf = ""; };
  FrameBuffer.prototype.pending = function () { return this.buf; };
  function segmentFrame(seg) {
    var m = FRAME_RE.exec(seg);
    return m ? parseFrame(m[0]) : null;
  }
  /**
   * A frame has no terminator in the Guanglu protocol, so the tail of every
   * chunk may still be truncated. Frames followed by another DAT/ACK head are
   * complete and are returned immediately; the last one is held until either
   * the next chunk arrives or flush() commits it after a short idle window.
   */
  FrameBuffer.prototype.push = function (chunk) {
    this.buf += String(chunk == null ? "" : chunk);
    var out = [], m;
    ACK_RE.lastIndex = 0;
    while ((m = ACK_RE.exec(this.buf)) !== null) {
      out.push({ type: "ack", cmd: m[1], status: m[2] });
    }
    this.buf = this.buf.replace(ACK_RE, "");

    var starts = [], re = /DAT[01]:/g;
    while ((m = re.exec(this.buf)) !== null) starts.push(m.index);
    if (!starts.length) {
      var pm = PARTIAL_HEAD_RE.exec(this.buf);
      this.buf = pm ? pm[1] : "";
      return out;
    }
    for (var i = 0; i < starts.length; i++) {
      var seg = this.buf.slice(starts[i], i + 1 < starts.length ? starts[i + 1] : this.buf.length);
      if (i < starts.length - 1) {
        var f = segmentFrame(seg);
        if (f) out.push({ type: "data", frame: f });
      } else {
        this.buf = seg;                   // possibly truncated tail
      }
    }
    return out;
  };
  /** Commit the held tail frame once no further chunk arrives. */
  FrameBuffer.prototype.flush = function () {
    var seg = this.buf;
    this.buf = "";
    var f = segmentFrame(seg);
    return f ? [{ type: "data", frame: f }] : [];
  };

  function toMm(um) { return um / UM_PER_MM; }
  function toInch(um) { return um / UM_PER_INCH; }
  function fmtMm(um, digits) { return (um / UM_PER_MM).toFixed(digits == null ? 2 : digits); }
  function fmtSignedMm(um, digits) {
    var s = (um / UM_PER_MM).toFixed(digits == null ? 2 : digits);
    return (um >= 0 ? "+" : "") + s;
  }

  /** Debounce: <=0.01mm AND <=300ms, per channel only. Cross-channel never dedups. */
  function shouldDrop(prev, cur, maxDeltaUm, maxMs) {
    if (!prev || prev.ch !== cur.ch) return false;
    if (Math.abs(cur.value_um - prev.value_um) > (maxDeltaUm == null ? 10 : maxDeltaUm)) return false;
    if ((cur.ts - prev.ts) > (maxMs == null ? 300 : maxMs)) return false;
    return true;
  }

  function verdict(um, lowerUm, upperUm) {
    return (um >= lowerUm && um <= upperUm) ? "合格" : "超差";
  }

  function stats(recs, lowerUm, upperUm) {
    var a = recs.map(function (r) { return r.value_um; });
    if (!a.length) return { n: 0, mean_um: 0, max_um: 0, min_um: 0, sd_um: 0, ok: 0, okRate: 0 };
    var sum = a.reduce(function (x, y) { return x + y; }, 0);
    var mean = sum / a.length;
    var ok = a.filter(function (v) { return verdict(v, lowerUm, upperUm) === "合格"; }).length;
    var variance = a.reduce(function (s, v) { return s + Math.pow(v - mean, 2); }, 0) / a.length;
    return {
      n: a.length,
      mean_um: mean,
      max_um: Math.max.apply(null, a),
      min_um: Math.min.apply(null, a),
      sd_um: Math.sqrt(variance),
      ok: ok,
      okRate: Math.round(ok / a.length * 100)
    };
  }

  function csvEscape(v) {
    var s = v == null ? "" : String(v);
    return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  /** RFC-4180-ish parser; strips UTF-8 BOM from the first header cell. */
  function csvParse(text) {
    if (text == null) text = "";
    var bomRemoved = text.charCodeAt(0) === 0xFEFF;
    if (bomRemoved) text = text.slice(1);
    var rows = [], row = [], cell = "", quoted = false;
    for (var i = 0; i < text.length; i++) {
      var c = text.charAt(i);
      if (quoted) {
        if (c === '"') {
          if (text.charAt(i + 1) === '"') { cell += '"'; i++; }
          else quoted = false;
        } else cell += c;
      } else if (c === '"' && cell === "") quoted = true;
      else if (c === ",") { row.push(cell); cell = ""; }
      else if (c === "\n") { row.push(cell); rows.push(row); row = []; cell = ""; }
      else if (c === "\r") { /* ignore, \n terminates the row */ }
      else cell += c;
    }
    if (cell !== "" || row.length) { row.push(cell); rows.push(row); }
    while (rows.length && rows[0].every(function (x) { return String(x).trim() === ""; })) rows.shift();
    if (!rows.length) return { headers: [], rawHeaders: [], skipped: [], rows: [], bomRemoved: bomRemoved };

    var rawHeaders = rows[0].map(function (h) { return String(h).trim(); });
    var headers = [], skipped = [];
    rawHeaders.forEach(function (h, idx) {
      var clean = h.replace(/^\uFEFF/, "").trim();
      var key = clean.toUpperCase();
      if (CSV_HEADERS.indexOf(key) >= 0) headers[idx] = key;
      else if (ALIASES[clean]) headers[idx] = ALIASES[clean];
      else { headers[idx] = null; skipped.push(clean || "(空列" + idx + ")"); }
    });

    var objects = rows.slice(1).map(function (r) {
      var o = {};
      headers.forEach(function (key, idx) { if (key) o[key] = r[idx] == null ? "" : String(r[idx]); });
      return o;
    }).filter(function (o) { return Object.keys(o).some(function (k) { return o[k] !== ""; }); });

    return { headers: headers, rawHeaders: rawHeaders, skipped: skipped, rows: objects, bomRemoved: bomRemoved };
  }

  /** Imported rows become the same record shape as live measurements. */
  function csvToRecords(parsed, defaults) {
    defaults = defaults || {};
    var seen = {}, out = [], badRows = 0;
    parsed.rows.forEach(function (o) {
      var mm = parseFloat(o.VALUE_MM);
      if (!isFinite(mm)) { badRows++; return; }
      var um = Math.round(mm * UM_PER_MM);
      var ch = parseInt(String(o.CHANNEL || "").replace(/^CH/i, ""), 10);
      if (!isFinite(ch) || ch < 1) ch = defaults.channel_no || 1;
      /* v1.1: CH8-CH10 from older exports stay as legacy channels; never clamp
         to 1 and never drop the row. */
      var legacy = ch > MAX_CHANNELS;
      var key = (o.DATE || "") + "|" + (o.TIME || "") + "|" + ch + "|" + um;
      if (seen[key]) return;
      seen[key] = 1;
      out.push({
        date: o.DATE || defaults.date || "",
        t: o.TIME || "00:00:00",
        seq: parseInt(o.SEQ, 10) || out.length + 1,
        batch: o.BATCH || defaults.batch || "",
        value_um: um,
        v: mm,                              // rendering layer reads r.v
        raw: o.RAW_VALUE || (o.SOURCE || "IMPORT") + ":m" + mm,
        raw_value: o.RAW_VALUE || String(mm),
        raw_unit: o.RAW_UNIT || "m",
        verdict: o.VERDICT || verdict(um, defaults.lowerUm || 0, defaults.upperUm || 0),
        src: o.SOURCE || "IMPORT",
        device_mac: o.DEVICE_MAC || "",
        note: o.NOTE || "",
        ch: ch,
        legacy: legacy,
        inch: (o.RAW_UNIT === "i"),
        ts: Date.now()
      });
    });
    return { records: out, badRows: badRows };
  }

  function recordsToCsv(recs, meta) {
    meta = meta || {};
    var lines = [CSV_HEADERS.join(",")];
    recs.forEach(function (r, i) {
      var d = r.date || meta.date || "";
      var row = {
        DATE: d,
        TIME: r.t || "",
        SEQ: r.seq || i + 1,
        BATCH: r.batch || meta.batch || "",
        ITEM: r.item || meta.item || "",
        NOMINAL: meta.nominal == null ? "" : meta.nominal,
        LOWER_TOL: meta.lowerTol == null ? "" : meta.lowerTol,
        UPPER_TOL: meta.upperTol == null ? "" : meta.upperTol,
        VALUE_MM: fmtMm(r.value_um, 4),
        RAW_VALUE: r.raw_value == null ? "" : r.raw_value,
        RAW_UNIT: r.raw_unit || "m",
        VERDICT: r.verdict || verdict(r.value_um, meta.lowerUm || 0, meta.upperUm || 0),
        SOURCE: r.src || "",
        DEVICE_MAC: r.device_mac || "",
        NOTE: r.note || "",
        CHANNEL: "CH" + r.ch
      };
      lines.push(CSV_HEADERS.map(function (k) { return csvEscape(row[k]); }).join(","));
    });
    return "\uFEFF" + lines.join("\r\n") + "\r\n";
  }

  /** NAM is the only configuration command the interface document defines. */
  function validateNam(name) {
    var s = String(name == null ? "" : name).trim();
    if (!s) return { ok: false, reason: "名称不能为空" };
    if (s.length > 16) return { ok: false, reason: "名称最长 16 字符（当前 " + s.length + "）" };
    if (s.indexOf(NAME_PREFIX) !== 0) return { ok: false, reason: "必须保留 " + NAME_PREFIX + " 前缀" };
    var suffix = s.slice(NAME_PREFIX.length);
    if (!suffix) return { ok: false, reason: "前缀后需有自定义名称" };
    if (!/^[A-Za-z0-9_-]+$/.test(suffix)) return { ok: false, reason: "仅支持字母、数字、下划线与连字符" };
    return { ok: true, cmd: "NAM:" + s };
  }

  /**
   * HID keyboard mode: the caliper types a value followed by CR or Tab.
   * Split on the configured terminator first, then tolerate the other one so
   * a mismatched device setting never silently merges two readings.
   */
  function hidSplit(text, terminator) {
    var s = String(text == null ? "" : text).replace(/\r\n/g, "\r");
    var primary = terminator === "TAB" ? /\t/ : /\r/;
    var out = [];
    s.split(primary).forEach(function (part) {
      part.split(/[\r\n\t]/).forEach(function (x) {
        var tok = x.trim();
        if (tok) out.push(tok);
      });
    });
    return out;
  }

  /** Turn one HID token into the same DAT0 frame the BLE chain consumes. */
  function hidToFrame(token) {
    var m = /^([+-]?)(\d+(?:\.\d+)?)\s*(mm|inch|in|m|i)?$/i.exec(String(token || "").trim());
    if (!m) return null;
    var unit = /^(i|inch|in)$/i.test(m[3] || "") ? "i" : "m";
    return "DAT0:" + unit + (m[1] === "-" ? "-" : "+") + m[2];
  }

  function fileName(batch, stamp, scope, ext) {
    return (batch || "BATCH") + "_" + (stamp || nowStamp()) + "_" + (scope || "ALL") + "." + ext;
  }
  function nowStamp() {
    var d = new Date(), p = function (n) { return String(n).padStart(2, "0"); };
    return "" + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + "_" +
      p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
  }
  function today() {
    var d = new Date(), p = function (n) { return String(n).padStart(2, "0"); };
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
  }
  function clock() {
    var d = new Date(), p = function (n) { return String(n).padStart(2, "0"); };
    return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }

  return {
    SERVICE_UUID: SERVICE_UUID, CHAR_UUID: CHAR_UUID, NAME_PREFIX: NAME_PREFIX,
    MAX_CHANNELS: MAX_CHANNELS, CH_COLORS: CH_COLORS, CSV_HEADERS: CSV_HEADERS,
    UM_PER_MM: UM_PER_MM, UM_PER_INCH: UM_PER_INCH,
    FrameBuffer: FrameBuffer, parseFrame: parseFrame,
    toMm: toMm, toInch: toInch, fmtMm: fmtMm, fmtSignedMm: fmtSignedMm,
    shouldDrop: shouldDrop, verdict: verdict, stats: stats,
    validateNam: validateNam, hidSplit: hidSplit, hidToFrame: hidToFrame,
    csvEscape: csvEscape, csvParse: csvParse, csvToRecords: csvToRecords,
    recordsToCsv: recordsToCsv, fileName: fileName,
    nowStamp: nowStamp, today: today, clock: clock
  };
});
