
(function(){
  "use strict";
  var P = window.GL;
  var MAX_CH = P.MAX_CHANNELS;
  var CH_COLORS = P.CH_COLORS;
  var CH_DEFS = [
    ["GL-BLE#0187","A4:C1:38:01:87",52,155.15],
    ["GL-BLE#2043","A4:C1:38:02:43",71,154.96],
    ["GL-BLE#3312","A4:C1:38:03:12",63,155.08],
    ["GL-BLE#4458","A4:C1:38:04:58",78,155.31],
    ["GL-BLE#5091","A4:C1:38:05:91",66,155.11],
    ["GL-BLE#6A24","A4:C1:38:06:24",83,154.92],
    ["GL-BLE#7B66","A4:C1:38:07:66",74,155.18]
  ];
  function defaultChannels(){
    return CH_DEFS.map(function(d,i){
      return {id:i+1,name:d[0],mac:d[1],rssi:d[2],value:d[3],color:CH_COLORS[i],
        connected:true,last:"09:17:44",src:"DAT0",bound:false,lastFrame:null};
    });
  }
  var SEED_VALS = [155.02,155.08,154.96,155.15,155.31,155.11,155.08,154.92,154.72,155.22,155.18,155.06,155.10];
  var SEED_CHS  = [1,2,3,4,5,6,7,1,2,3,4,5,6];
  var SEED_TIME = ["09:12:04","09:12:31","09:13:02","09:13:40","09:14:22","09:15:05","09:15:48","09:16:20","09:17:01","09:17:44","09:18:12","09:18:35","09:19:02"];
  var BASE = [];
  var S = {
    page:"P2", overview:false, connected:true, scanned:true, mode:"manual", unit:"mm",
    current:155.15, records:[], channels:defaultChannels(), focusCh:0, activeCh:1,
    dedup:0, lost:0, disconnected:false, chartType:"line", expPng:true, expCsv:true,
    exportSplit:false, lower:154.80, upper:155.20, batch:"B20261112-01",
    switches:{screen:true,reconnect:true,devSleep:false}, deviceCfg:{}, noteIndex:-1
  };
  var TITLES = {P1:"连接卡尺",P2:"实时检测 · 记录",P3:"多通道",P4:"表格模板",P5:"数据分析",P6:"导出到本地",P7:"设置"};
  var NOTES = {
    P1:{sub:"README ④-P1 · 蓝牙连接层 / 7 通道槽位",items:[["⑤-1","扫描 GL-BLE# 前缀、连接、订阅 notify、断开重连"],["新增","最多 7 台并发；通道槽位 1–7，第 8 台拒绝并提示"],["A6","7 路各自记住上次成功 MAC 并自动重连"],["A10","权限永久拒绝 / 蓝牙关闭 / 定位开关关闭要有引导页"],["新增","7 路 GATT 串行命令队列；单通道断连不影响其他通道"],["机型","连接前 stopScan()、30 秒 ≤5 次扫描、重连后重新订阅"]]},
    P2:{sub:"README ④-P2 + ④-P3 · 合并为一屏",items:[["新增","实时检测与检测记录合并到同一界面，不再有两页跳转"],["新增","实时检测改成表格，只保留「时间 + 数值」两列"],["新增","通道用行内色标、判定用左侧色条、备注点行查看，均不占列"],["新增","顶部通道 chips 筛选，表格 / 统计 / 图表同步"],["⑤-2/3","解析 DAT0 / DAT1；完整帧解析成功后才回 DAT0:OK"],["⑤-4","MEAS / CLR / MOD:0 / MOD:1 指令与响应"],["⑤-5/6","mm / inch 统一换算；收到即落库"],["⑤-11","点行编辑备注或删除该条（保留，收进弹窗）"],["⑤-12","公差判定，超差标红、合格标绿"],["⑤-14","n / 均值 / 最大 / 最小 / 合格率"],["A1","采集页屏幕常亮，防息屏断连丢数"],["A4","去重量化：≤0.01mm 且 ≤300ms，按通道独立"],["A12","MEAS:ERR / MOD:ERR 指数退避重试 2 次并提示"],["B1","主按钮 ≥56dp、读数 ≥48sp，戴手套可点"]]},
    P3:{sub:"新增页 · 7 通道并发与多通道显示",items:[["新增","一台手机最多同时连接 7 台卡尺"],["新增","7 色固定编码：连接列表 / 表格 / 通道卡 / 图表 / 导出全局一致"],["新增","7 路读数用卡片墙平铺，不堆 7 个大数字"],["新增","每路独立帧缓冲、去重与落库；跨通道不去重"],["新增","单通道断连只灰掉该卡，其余 9 路继续采集"],["新增","设为主通道：MEAS / 记录一条归属该通道"],["C3 作废","原开发基线 C3「多台卡尺并发连接放 V2」按本次需求改为 V1 必做"]]},
    P4:{sub:"README ④-P4 · V1 简版模板",items:[["⑤-13","内置默认模板 + CSV 表头模板上传与字段映射"],["4.2","15 列稳定大写 key + 中文别名；导入先剥离 BOM"],["新增","导出 CSV 增 CHANNEL 列（CH1–CH7），导入按该列还原通道"],["4.1","未识别列整列跳过并记入导入报告"],["V2","xlsx 合并单元格 / 公式 / 样式延后"]]},
    P5:{sub:"README ④-P5 · 图表分析 / 通道叠加",items:[["⑤-15","折线图（按测量序号 / 时间）"],["⑤-16","柱状图（按序号对比 / 数值分布）"],["新增","按通道筛选或 7 路叠加，数据点按通道色着色"],["④-P5","公差上下限虚线参考线、统计卡片"],["P5","点折线点显示该条数值、时间与通道"]]},
    P6:{sub:"README ④-P6 · 导出 / 按通道分文件",items:[["⑤-17","图表导出 PNG 到手机本地"],["⑤-18","原始数据导出 CSV 到手机本地"],["新增","合并一个 CSV（含 CHANNEL 列）或按通道分 7 个 CSV"],["A11","默认同时勾 PNG + CSV；CSV 可再导回 App"],["2.4","走 SAF，不申请 MANAGE_EXTERNAL_STORAGE"],["B6","同名文件不覆盖"]]},
    P7:{sub:"README ④-P7 · 配置层 / 通道上限",items:[["④-P7","默认单位 / 小数位 / 公差上下限 / 去抖阈值 / 广播名前缀"],["新增","最大并发通道数 7（只读），通道命名 / 改色留 V2"],["A7","导出诊断日志（原始帧、指令、回包、错误码）"],["B2","省电白名单引导；V1 仍只承诺前台"],["B5","App 内显示版本；升级前提醒先导出 CSV"],["新增","蓝牙配置：NAM 改名可下发；自动发射 / 休眠 / HID 结束符仅登记"],["边界","Android 第三方 App 无法配置卡尺 HID 键盘模式参数；无厂商指令集，V1 不假下发"]]}
  };
  function $(id){return document.getElementById(id);}
  function q(sel,root){return (root||document).querySelector(sel);}
  function qa(sel,root){return Array.prototype.slice.call((root||document).querySelectorAll(sel));}
  function toast(msg){var t=$("toast");t.textContent=msg;t.classList.add("show");
    clearTimeout(t._tm);t._tm=setTimeout(function(){t.classList.remove("show");},2100);}
  function fmt(v){return (v>=0?"+":"")+v.toFixed(2);}
  function verdict(v){return v>=S.lower && v<=S.upper ? "合格" : "超差";}
  function online(){return S.channels.filter(function(c){return c.connected;});}
  function visRecords(){return S.records.filter(function(r){
    if(S.focusCh===-1) return r.ch>MAX_CH || r.legacy;
    return S.focusCh===0 || r.ch===S.focusCh;});}
  function vals(){return visRecords().map(function(r){return r.v;});}
  function chColor(ch){var c=S.channels[ch-1];return c?c.color:"#6B778C";}
  function nowTime(){var d=new Date();return String(d.getHours()).padStart(2,"0")+":"+
    String(d.getMinutes()).padStart(2,"0")+":"+String(d.getSeconds()).padStart(2,"0");}
  function mean(a){return a.reduce(function(x,y){return x+y;},0)/a.length;}

  function renderStats(){
    var a=vals();
    if(!a.length){
      $("statStrip").innerHTML="<div class='stat'><b>0</b><span>暂无数据</span></div>";
      $("chartStats").innerHTML="<div class='stat-box'><span>暂无数据</span><b>—</b></div>";
      return;
    }
    var avg=mean(a), ok=a.filter(function(v){return verdict(v)==="合格";}).length;
    $("statStrip").innerHTML =
      "<div class='stat'><b>"+a.length+"</b><span>n</span></div>"+
      "<div class='stat'><b>"+avg.toFixed(2)+"</b><span>均值</span></div>"+
      "<div class='stat'><b>"+Math.max.apply(null,a).toFixed(2)+"</b><span>最大</span></div>"+
      "<div class='stat'><b>"+Math.min.apply(null,a).toFixed(2)+"</b><span>最小</span></div>"+
      "<div class='stat'><b>"+Math.round(ok/a.length*100)+"%</b><span>合格率</span></div>";
    var sd=Math.sqrt(a.reduce(function(s,v){return s+Math.pow(v-avg,2);},0)/a.length);
    $("chartStats").innerHTML =
      "<div class='stat-box'><span>均值 mm</span><b>"+avg.toFixed(3)+"</b></div>"+
      "<div class='stat-box'><span>极差 mm</span><b>"+(Math.max.apply(null,a)-Math.min.apply(null,a)).toFixed(3)+"</b></div>"+
      "<div class='stat-box'><span>标准差 mm</span><b>"+sd.toFixed(3)+"</b></div>"+
      "<div class='stat-box'><span>合格率</span><b style='color:var(--ok)'>"+Math.round(ok/a.length*100)+"%</b></div>";
  }
  function renderRecords(){
    var list=visRecords();
    $("liveBody").innerHTML = list.length ? list.map(function(r){
      var i=S.records.indexOf(r), bad=verdict(r.v)==="超差";
      return "<tr class='"+(bad?"bad":"ok")+"' data-action='row' data-i='"+i+"'>"+
        "<td class='t'>"+r.t+"</td>"+
        "<td class='v'><span class='ch-mark' style='background:"+chColor(r.ch)+"'>CH"+r.ch+"</span>"+r.v.toFixed(2)+"</td>"+
      "</tr>";
    }).join("") : "<tr><td colspan='2' class='t' style='text-align:center;padding:18px;border-left:0'>该通道暂无数据</td></tr>";
    $("countVal").textContent=list.length+" 条";
    $("tableScope").textContent=S.focusCh===0?"全部通道":"CH"+S.focusCh;
  }
  function renderChips(){
    var html="<button class='ch-chip"+(S.focusCh===0?" on":"")+"' data-action='ch-filter' data-ch='0'>全部 "+MAX_CH+" 路</button>"+
      S.channels.map(function(c){
        return "<button class='ch-chip"+(S.focusCh===c.id?" on":"")+"' data-action='ch-filter' data-ch='"+c.id+"'>"+
          "<span style='width:8px;height:8px;border-radius:50%;background:"+c.color+";display:inline-block'></span>CH"+c.id+"</button>";
      }).join("");
    var legacyN=S.records.filter(function(r){return r.ch>MAX_CH || r.legacy;}).length;
    if(legacyN) html+="<button class='ch-chip"+(S.focusCh===-1?" on":"")+"' data-action='ch-filter' data-ch='-1'>历史通道 "+legacyN+"</button>";
    ["p2Chips","p5Chips"].forEach(function(id){var e=$(id);if(e)e.innerHTML=html;});
  }
  function renderDevices(){
    $("deviceList").innerHTML=S.channels.map(function(c){
      return "<div class='row between' data-action='ch-toggle' data-ch='"+c.id+"' style='padding:7px 0;border-bottom:1px solid #F0F3F8;cursor:pointer'>"+
        "<div class='row' style='gap:7px'>"+
          "<span class='ch-slot"+(c.connected?" on":"")+"'"+(c.connected?" style='background:"+c.color+"'":"")+">CH"+c.id+"</span>"+
          "<div><b style='font-size:12.5px'>"+c.name+"</b><div class='tiny muted mono'>"+c.mac+" · -"+c.rssi+" dBm</div></div>"+
        "</div>"+
        "<span class='chip "+(c.connected?"ok":"")+"'>"+(c.connected?"在线":"未连接")+"</span></div>";
    }).join("");
    var n=online().length;
    $("p1state").textContent="已连接 "+n+" / "+MAX_CH+" 通道";
    $("p1dot").className=n>0?"dot ok":"dot bad";
  }
  function renderChannels(){
    var n=online().length;
    $("chCount").textContent=n+" / "+MAX_CH+" 在线";
    $("chLegend").innerHTML=S.channels.map(function(c){
      return "<span style='background:"+c.color+";opacity:"+(c.connected?"1":".4")+"'>CH"+c.id+"</span>";
    }).join("");
    $("channelGrid").innerHTML=S.channels.map(function(c){
      return "<div class='ch-card"+(c.connected?"":" off")+(S.activeCh===c.id?" focus":"")+"' style='--ch:"+c.color+"'>"+
        "<div class='ch-h'><b style='font-size:11px;color:"+c.color+"'>CH"+c.id+"</b>"+
          "<span class='chip "+(c.connected?"ok":"bad")+"' style='padding:2px 6px;font-size:9px'>"+(c.connected?"在线":"断连")+"</span></div>"+
        "<div class='ch-v'>"+(c.value>=0?"+":"")+c.value.toFixed(2)+"</div>"+
        "<div class='ch-m'>"+c.name+"<br>"+c.mac.slice(-5)+" · "+c.last+" · "+c.src+"</div>"+
        "<button class='btn sm' style='width:100%;height:28px;margin-top:7px;font-size:10.5px' data-action='ch-focus' data-ch='"+c.id+"'>"+
          (S.activeCh===c.id?"主通道 ✓":"设为主通道")+"</button>"+
        "<button class='btn sm' style='width:100%;height:26px;margin-top:5px;font-size:10.5px' data-action='ch-toggle' data-ch='"+c.id+"'>"+
          (c.connected?"断开该通道":"重连该通道")+"</button>"+
      "</div>";
    }).join("");
    $("onlineChip").innerHTML="<span class='dot "+(n>0?"ok":"bad")+"'></span>"+n+" 通道在线";
    $("mainChLabel").textContent="CH"+S.activeCh;
  }
  function renderChart(){
    var recs=visRecords(), a=recs.map(function(r){return r.v;}), W=320,H=210, L=38,R=8,T=14,B=26;
    var lo=Math.min.apply(null,a.concat([S.lower])) - 0.08;
    var hi=Math.max.apply(null,a.concat([S.upper])) + 0.08;
    var x=function(i){return L + (W-L-R) * (a.length===1?0.5:i/(a.length-1));};
    var y=function(v){return T + (H-T-B) * (1-(v-lo)/(hi-lo));};
    var grid="", lines="";
    [lo+0.1, (lo+hi)/2, hi-0.1].forEach(function(v){
      grid += "<line x1='"+L+"' y1='"+y(v).toFixed(1)+"' x2='"+(W-R)+"' y2='"+y(v).toFixed(1)+"' stroke='#EDF0F6' stroke-width='1'/>"+
              "<text x='2' y='"+(y(v)+3).toFixed(1)+"' font-size='9' fill='#8A97AC'>"+v.toFixed(2)+"</text>";
    });
    lines += "<line x1='"+L+"' y1='"+y(S.upper).toFixed(1)+"' x2='"+(W-R)+"' y2='"+y(S.upper).toFixed(1)+"' stroke='#E8912A' stroke-width='1.4' stroke-dasharray='5 4'/>"+
             "<text x='"+(W-R-2)+"' y='"+(y(S.upper)-4).toFixed(1)+"' text-anchor='end' font-size='9' fill='#C57A16'>USL "+S.upper.toFixed(2)+"</text>"+
             "<line x1='"+L+"' y1='"+y(S.lower).toFixed(1)+"' x2='"+(W-R)+"' y2='"+y(S.lower).toFixed(1)+"' stroke='#E8912A' stroke-width='1.4' stroke-dasharray='5 4'/>"+
             "<text x='"+(W-R-2)+"' y='"+(y(S.lower)-4).toFixed(1)+"' text-anchor='end' font-size='9' fill='#C57A16'>LSL "+S.lower.toFixed(2)+"</text>";
    var body="";
    if(S.chartType==="line" && a.length){
      var pts=a.map(function(v,i){return x(i).toFixed(1)+","+y(v).toFixed(1);}).join(" ");
      body += "<polyline points='"+pts+"' fill='none' stroke='#1668E3' stroke-width='2.4' stroke-linejoin='round'/>";
      a.forEach(function(v,i){
        var c=verdict(v)==="超差"?"#E5384A":chColor(recs[i].ch);
        body += "<circle cx='"+x(i).toFixed(1)+"' cy='"+y(v).toFixed(1)+"' r='4.5' fill='#fff' stroke='"+c+"' stroke-width='2.2' "+
                "data-action='point' data-i='"+S.records.indexOf(recs[i])+"' style='cursor:pointer'/>";
      });
    }else if(a.length){
      var bw=(W-L-R)/a.length*0.56;
      a.forEach(function(v,i){
        var c=verdict(v)==="超差"?"#E5384A":chColor(recs[i].ch);
        var top=y(v), base=y(lo);
        body += "<rect x='"+(x(i)-bw/2).toFixed(1)+"' y='"+top.toFixed(1)+"' width='"+bw.toFixed(1)+"' height='"+Math.max(1,base-top).toFixed(1)+"' rx='2' fill='"+c+"'/>";
      });
    }else{
      body="<text x='"+(W/2)+"' y='"+(H/2)+"' text-anchor='middle' font-size='11' fill='#8A97AC'>该通道暂无数据</text>";
    }
    var axis="";
    a.forEach(function(_,i){ if(i%2===0) axis += "<text x='"+x(i).toFixed(1)+"' y='"+(H-8)+"' text-anchor='middle' font-size='9' fill='#8A97AC'>"+(i+1)+"</text>"; });
    $("chartBox").innerHTML = "<svg viewBox='0 0 "+W+" "+H+"' xmlns='http://www.w3.org/2000/svg'>"+grid+lines+body+axis+"</svg>";
  }
  function renderExportFiles(){
    var base="B20261112-01_20261112_0950";
    var scope=S.focusCh===0?"_ALL":"_CH"+S.focusCh;
    $("filePng").textContent=base+scope+".png";
    if(S.exportSplit){
      var names=S.channels.filter(function(c){return c.connected;}).map(function(c){return base+"_CH"+c.id+".csv";});
      $("fileCsv").innerHTML=names.length?names.join("<br>"):"无在线通道，暂无 CSV 文件";
    }else{
      $("fileCsv").textContent=base+scope+".csv";
    }
    $("boxMerge").classList.toggle("on",!S.exportSplit);$("boxMerge").textContent=S.exportSplit?"":"✓";
    $("boxSplit").classList.toggle("on",S.exportSplit);$("boxSplit").textContent=S.exportSplit?"✓":"";
  }
  function renderNotes(){
    var n=NOTES[S.page];
    $("noteTitle").textContent=S.page+" "+TITLES[S.page];
    $("noteSub").textContent=n.sub;
    $("noteBody").innerHTML="<h3>对应需求与补充项</h3><ul>"+n.items.map(function(it){
      return "<li><span class='tag'>"+it[0]+"</span>"+it[1]+"</li>";
    }).join("")+"</ul>";
  }
  function renderPage(){
    var phone=$("phone")||document;
    qa(".screen",phone).forEach(function(s){s.classList.toggle("active",s.dataset.id===S.page);});
    qa(".nav-item").forEach(function(b){b.classList.toggle("active",b.dataset.page===S.page);});
    qa(".tab",phone).forEach(function(b){b.classList.toggle("active",b.dataset.page===S.page);});
    $("appTitle").textContent=TITLES[S.page];
    $("appBadge").textContent=S.mode==="manual"?"手动":"连续";
    renderNotes();
    var vp=$("viewport"); if(vp) vp.scrollTop=0;
  }
  function renderAll(){
    $("readingVal").textContent=S.unit==="mm"?fmt(S.current):fmt(S.current/25.4);
    $("readingUnit").textContent=S.unit==="mm"?"mm":"inch";
    $("modeChip").textContent=S.mode==="manual"?"手动 · DAT0":"连续 · DAT1";
    $("modeBtn").textContent=S.mode==="manual"?"切换为连续":"切换为手动";
    $("discBanner").classList.toggle("hidden",!S.disconnected);
    $("discBtn").textContent=S.disconnected?"恢复连接":"模拟断连";
    $("lostText").textContent="已丢失 "+S.lost+" 次上报";
    $("recordBtn").disabled=S.disconnected;
    $("recordBtn").style.opacity=S.disconnected?".5":"1";
    $("demoLog").textContent="去重丢弃 "+S.dedup+" 帧 · 未落库 "+S.lost+" 条 · 在线 "+online().length+" / "+MAX_CH+" 通道";
    $("boxPng").classList.toggle("on",S.expPng); $("boxPng").textContent=S.expPng?"✓":"";
    $("boxCsv").classList.toggle("on",S.expCsv); $("boxCsv").textContent=S.expCsv?"✓":"";
    $("filePng").style.display=S.expPng?"block":"none";
    $("fileCsv").style.display=S.expCsv?"block":"none";
    $("segLine").classList.toggle("on",S.chartType==="line");
    $("segBar").classList.toggle("on",S.chartType==="bar");
    renderDevices(); renderChips(); renderChannels(); renderStats();
    renderRecords(); renderChart(); renderExportFiles(); renderPage();
  }
  function setPage(p){ S.page=p; if(S.overview) toggleOverview(); renderAll(); }
  function buildOverview(){
    var host=$("overview"); host.innerHTML="";
    ["P1","P2","P3","P4","P5","P6","P7"].forEach(function(id){
      var wrap=document.createElement("div"); wrap.className="mini-wrap";
      var p=$("phone").cloneNode(true);
      p.removeAttribute("id"); p.classList.add("mini-phone");
      qa(".screen",p).forEach(function(s){s.classList.toggle("active",s.dataset.id===id);});
      qa(".tab",p).forEach(function(b){b.classList.toggle("active",b.dataset.page===id);});
      qa("[id]",p).forEach(function(e){e.removeAttribute("id");});
      q(".appbar .title",p).textContent=TITLES[id];
      wrap.appendChild(p);
      var lab=document.createElement("div"); lab.className="mini-label"; lab.textContent=id+" "+TITLES[id];
      wrap.appendChild(lab);
      host.appendChild(wrap);
    });
  }
  function toggleOverview(){
    S.overview=!S.overview;
    $("phoneWrap").classList.toggle("hidden",S.overview);
    $("overview").classList.toggle("hidden",!S.overview);
    $("overviewBtn").classList.toggle("on",S.overview);
    $("overviewBtn").textContent=S.overview?"返回单屏交互":"总览 7 屏";
    $("stageHint").textContent=S.overview?"当前：7 屏总览 · 点击「返回单屏交互」操作按钮":"当前：单屏交互模式 · 点击左侧导航切换页面";
    if(S.overview) buildOverview();
  }
  function openModal(i){
    var r=S.records[i]; if(!r) return;
    S.noteIndex=i;
    $("modalHost").innerHTML="<div class='modal-mask' data-action='modal-close'>"+
      "<div class='modal' data-stop='1'>"+
        "<h3>第 "+(i+1)+" 条记录 · CH"+r.ch+"</h3>"+
        "<p>"+r.t+" · "+r.v.toFixed(2)+" mm · "+verdict(r.v)+" · "+r.src+" · "+r.raw+
          (r.inch?" · 6.1055 i × 25.4 换算":"")+"</p>"+
        "<input id='noteInput' placeholder='编辑备注（不占表格列）' value='"+r.note.replace(/'/g,"&#39;")+"'>"+
        "<div class='btn-row'><button class='btn primary' style='height:42px;font-size:13px' data-action='note-save'>保存备注</button>"+
        "<button class='btn danger' data-action='row-delete'>删除该条</button></div>"+
      "</div></div>";
  }
  function closeModal(){$("modalHost").innerHTML="";S.noteIndex=-1;}
  function pushRecord(ch,v,src,t){
    S.records.push({t:t||nowTime(),v:v,ch:ch,src:src,
      raw:(src==="DAT0"?"DAT0:m":"DAT1:m")+fmt(v),note:""});
    var c=S.channels[ch-1]; if(c){c.value=v;c.last=t||nowTime();c.src=src;}
  }

  document.addEventListener("click",function(e){
    var el=e.target.closest("[data-action]"); if(!el) return;
    var a=el.dataset.action;
    if(el.closest(".mini-phone")) return;
    if(a==="go"){ setPage(el.dataset.page); return; }
    if(a==="overview"){ toggleOverview(); return; }
    if(a==="reset-demo"){
      S.records=seedRecords();S.current=155.15;S.dedup=0;S.lost=0;S.disconnected=false;
      S.connected=true;S.scanned=true;S.mode="manual";S.unit="mm";S.chartType="line";
      S.channels=defaultChannels();S.focusCh=0;S.activeCh=1;S.exportSplit=false;
      S.expPng=true;S.expCsv=true;S.page="P2";
      $("rawFrame").textContent="DAT0:m+155.15";$("ackFrame").textContent="DAT0:OK";
      qa(".seg button").forEach(function(b){b.classList.remove("on");});
      q('[data-action="unit"][data-unit="mm"]').classList.add("on");
      q('[data-action="chart"][data-type="line"]').classList.add("on");
      renderAll();toast("演示数据已重置 · 7 通道恢复全在线");return;
    }
    if(a==="scan"){
      S.scanned=true;$("p1state").textContent="扫描中";$("p1dot").className="dot blue";
      $("deviceList").innerHTML="<div class='tiny muted' style='text-align:center;padding:14px 0'>正在扫描 GL-BLE# ...</div>";
      setTimeout(function(){
        renderDevices();
        toast("扫描完成 · 发现 7 台；通道上限 7，第 8 台不连接");
      },650);
      return;
    }
    if(a==="connect-all"){
      S.channels.forEach(function(c){c.connected=true;});
      S.connected=true;S.disconnected=false;renderAll();
      toast("已连接 "+MAX_CH+" / "+MAX_CH+" 通道 · 每路各自订阅 notify");return;
    }
    if(a==="disconnect"){
      S.channels.forEach(function(c){c.connected=false;});
      S.connected=false;S.disconnected=true;S.lost+=1;
      renderAll();toast("全部断开 · 卡尺不保存历史数据，断连期间无法补录");return;
    }
    if(a==="ch-toggle"){
      var id=parseInt(el.dataset.ch,10), c=S.channels[id-1];
      c.connected=!c.connected;
      if(!c.connected && S.activeCh===id){
        var next=online()[0]; S.activeCh=next?next.id:id;
        S.current=next?next.value:S.current;
      }
      S.lost+=c.connected?0:1;
      renderAll();
      toast("CH"+id+(c.connected?" 已重连 · 重新发现服务并订阅 notify":" 已断连 · 其余通道继续采集"));return;
    }
    if(a==="ch-focus"){
      S.activeCh=parseInt(el.dataset.ch,10);
      S.current=S.channels[S.activeCh-1].value;
      renderAll();toast("主通道已设为 CH"+S.activeCh+" · MEAS / 记录一条归属该通道");return;
    }
    if(a==="ch-filter"){
      S.focusCh=parseInt(el.dataset.ch,10);
      renderAll();
      toast(S.focusCh===0?"显示全部 "+MAX_CH+" 路（按时间混排）":S.focusCh===-1?"只看历史通道（旧版 CH8–CH10 导出）":"只看 CH"+S.focusCh+" · 表格 / 统计 / 图表已同步");return;
    }
    if(a==="ch-burst"){
      var d=new Date();
      online().forEach(function(c,k){
        var t=new Date(d.getTime()+k*1000);
        var hh=String(t.getHours()).padStart(2,"0"),mm=String(t.getMinutes()).padStart(2,"0"),ss=String(t.getSeconds()).padStart(2,"0");
        var v=+(155.00+(Math.random()*0.36-0.15)).toFixed(2);
        pushRecord(c.id,v,"DAT1",hh+":"+mm+":"+ss);
      });
      S.dedup+=3;
      renderAll();toast("7 路并发上报 · 按通道独立去抖，同值不同通道不会互相丢弃");return;
    }
    if(a==="ch-drop"){
      var c3=S.channels[2];c3.connected=!c3.connected;
      if(!c3.connected)S.lost+=1;
      renderAll();toast(c3.connected?"CH3 已重连":"CH3 断连 · 其余 9 路继续采集");return;
    }
    if(a==="perm"){toast("演示：跳转系统设置（权限 / 蓝牙 / 定位开关）");return;}
    if(a==="unit"){
      S.unit=el.dataset.unit;
      qa('[data-action="unit"]').forEach(function(b){b.classList.toggle("on",b.dataset.unit===S.unit);});
      renderAll();return;
    }
    if(a==="record"){
      if(S.disconnected){toast("断连中，按钮已置灰");return;}
      pushRecord(S.activeCh,S.current,S.mode==="manual"?"DAT0":"DAT1");
      renderAll();toast("CH"+S.activeCh+" 已记录第 "+S.records.length+" 条 · 收到即落库");return;
    }
    if(a==="meas"||a==="frame"){
      S.current=+(155.00+(Math.random()*0.36-0.15)).toFixed(2);
      $("rawFrame").textContent="CH"+S.activeCh+"  DAT0:m"+fmt(S.current);
      var mc=S.channels[S.activeCh-1];mc.value=S.current;mc.last=nowTime();mc.src="DAT0";
      renderAll();toast("CH"+S.activeCh+" 收到 DAT0:m"+fmt(S.current)+" → 已回 DAT0:OK");return;
    }
    if(a==="burst"){
      S.dedup+=7;S.current=+(155.00+(Math.random()*0.3-0.12)).toFixed(2);
      $("rawFrame").textContent="CH"+S.activeCh+"  DAT1:m"+fmt(S.current);
      var bc=S.channels[S.activeCh-1];bc.value=S.current;bc.last=nowTime();bc.src="DAT1";
      renderAll();toast("CH"+S.activeCh+" DAT1 连发 8 帧 · 按 ≤0.01mm / 300ms 去重丢弃 7 帧");return;
    }
    if(a==="clr"){toast("CLR 已发送 · 设备回 CLR:OK");return;}
    if(a==="toggle-mode"){
      S.mode=S.mode==="manual"?"continuous":"manual";
      $("ackFrame").textContent=S.mode==="manual"?"DAT0:OK":"无需响应";
      renderAll();toast(S.mode==="manual"?"MOD:0 手动模式 · DAT0 需回 DAT0:OK":"MOD:1 连续模式 · DAT1 无需响应");return;
    }
    if(a==="toggle-disconnect"){
      S.disconnected=!S.disconnected;if(S.disconnected)S.lost+=2;
      renderAll();toast(S.disconnected?"已断连 · 已丢失 "+S.lost+" 次上报":"已重连 · 7 路重新发现服务并订阅 notify");return;
    }
    if(a==="err"){toast("MEAS:ERR（设备忙）→ 指数退避重试 2 次后仍失败，已提示");return;}
    if(a==="row"){openModal(parseInt(el.dataset.i,10));return;}
    if(a==="modal-close"){if(!e.target.closest("[data-stop]"))closeModal();return;}
    if(a==="note-save"){
      var v=$("noteInput").value;if(S.noteIndex>=0)S.records[S.noteIndex].note=v;
      closeModal();renderAll();toast("备注已保存 · 不占表格列");return;
    }
    if(a==="row-delete"){
      if(S.noteIndex>=0)S.records.splice(S.noteIndex,1);
      closeModal();renderAll();toast("已删除该条记录");return;
    }
    if(a==="chart"){S.chartType=el.dataset.type;renderAll();return;}
    if(a==="point"){
      var i=parseInt(el.dataset.i,10),r=S.records[i];
      toast("第 "+(i+1)+" 条 · CH"+r.ch+" · "+r.t+" · "+r.v.toFixed(2)+" mm · "+verdict(r.v));return;
    }
    if(a==="upload-template"){$("mapPreview").classList.remove("hidden");toast("已解析表头 · 15 列 / 跳过 2 列 · 含 CHANNEL 通道列");return;}
    if(a==="set-template"){toast("已设为当前模板 · 返回实时检测按新列展示");return;}
    if(a==="delete-template"){toast("演示：删除模板需二次确认");return;}
    if(a==="export-toggle"){
      if(el.dataset.kind==="png")S.expPng=!S.expPng;else S.expCsv=!S.expCsv;
      renderAll();return;
    }
    if(a==="export-mode"){
      S.exportSplit=el.dataset.mode==="split";renderAll();
      toast(S.exportSplit?"按通道分文件：每个在线通道一个 CSV":"合并为一个 CSV（含 CHANNEL 列）");return;
    }
    if(a==="save-export"){
      var parts=[];if(S.expPng)parts.push("PNG");if(S.expCsv)parts.push("CSV");
      if(!parts.length){toast("至少勾选一项导出内容");return;}
      toast("已保存到 下载/Documents："+parts.join(" + ")+(S.exportSplit?"（按通道分文件）":""));return;
    }
    if(a==="share"){toast("演示：调起系统分享面板");return;}
    if(a==="switch"){
      var k=el.dataset.key;S.switches[k]=!S.switches[k];el.classList.toggle("on",S.switches[k]);return;
    }
    if(a==="diag"){toast("诊断日志已导出：7 路原始帧 / 指令 / 回包 / 错误码");return;}
    if(a==="battery"){toast("演示：跳转电池优化白名单");return;}
    if(a==="settings-save"){
      S.lower=parseFloat($("setLower").value)||154.80;S.upper=parseFloat($("setUpper").value)||155.20;
      renderAll();toast("设置已保存到本地");return;
    }
    if(a==="settings-reset"){
      $("setUnit").value="mm";$("setDigits").value="4";$("setNominal").value="155.00";
      $("setLower").value="154.80";$("setUpper").value="155.20";$("setDebounce").value="300 ms";
      $("setPrefix").value="GL-BLE#";S.lower=154.80;S.upper=155.20;
      renderAll();toast("已恢复默认设置");return;
    }
  });

  renderAll();
  if(location.search.indexOf("overview=1")>=0) toggleOverview();

  /* ================= MVP runtime overlay =================
     The approved P1-P7 shell above is unchanged. Everything below is additive:
     real protocol parsing, real persistence, real BLE, real import/export. */
  var RT = window.GLRuntime;
  var FEED = {}, LOG = [], _syncTimer = null;
  function log(line){ LOG.push(P.clock()+"  "+line); if(LOG.length>3000) LOG.shift(); }
  function lowerUm(){ return Math.round(S.lower*1000); }
  function upperUm(){ return Math.round(S.upper*1000); }
  function meta(){
    return { date:P.today(), batch:S.batch, item:"外径", nominal:"155.00",
      lowerTol:(S.lower-155).toFixed(2), upperTol:(S.upper-155).toFixed(2),
      lowerUm:lowerUm(), upperUm:upperUm() };
  }
  function seedRecords(){
    return SEED_VALS.map(function(v,i){
      var inch=(i===6), ch=SEED_CHS[i], c=S.channels[ch-1];
      var src=i%3===1?"DAT1":"DAT0";
      var um=inch?Math.round(6.1055*P.UM_PER_INCH):Math.round(v*1000);
      var vv=inch?um/P.UM_PER_MM:v;
      return { date:P.today(), t:SEED_TIME[i], seq:i+1, batch:S.batch,
        value_um:um, v:vv, raw_value:inch?"6.1055":String(v), raw_unit:inch?"i":"m",
        verdict:P.verdict(um,lowerUm(),upperUm()), src:src,
        device_mac:c?c.mac:"", note:i===0?"首件":(i===4?"复测":""), ch:ch,
        raw:inch?"DAT1:i+6.1055":src+":m"+fmt(v),
        ts:Date.now()-(13-i)*30000, inch:inch };
    });
  }
  function scheduleSync(){
    if(_syncTimer) clearTimeout(_syncTimer);
    _syncTimer=setTimeout(function(){
      var clean=S.records.map(function(r){ var o={}; for(var k in r){ if(k!=="id") o[k]=r[k]; } return o; });
      RT.Store.replaceAll(clean).then(function(){ log("批量落库 "+clean.length+" 条"); });
    },220);
  }
  function recFromFrame(ch,f){
    var c=S.channels[ch-1];
    return { date:P.today(), t:P.clock(), seq:S.records.length+1, batch:S.batch,
      value_um:f.value_um, v:P.toMm(f.value_um),
      raw_value:String(f.raw_value), raw_unit:f.unit,
      verdict:P.verdict(f.value_um,lowerUm(),upperUm()), src:f.kind,
      device_mac:c?c.mac:"", note:"", ch:ch, raw:f.raw,
      ts:Date.now(), inch:f.unit==="i" };
  }
  function onFrame(ch,f){
    var c=S.channels[ch-1]; if(!c || !f) return;
    log("CH"+ch+" 完整帧 "+f.raw+" → "+f.value_um+" um");
    var cur={ch:ch,value_um:f.value_um,ts:Date.now()};
    if(P.shouldDrop(c.lastFrame,cur)){
      S.dedup+=1; log("CH"+ch+" 去抖丢弃 "+f.raw); renderAll(); return;
    }
    c.lastFrame=cur;
    var r=recFromFrame(ch,f);
    S.records.push(r);
    c.value=r.v; c.last=r.t; c.src=f.kind;
    if(ch===S.activeCh) S.current=r.v;
    if(f.kind==="DAT0"){
      if(c.bound && RT.BLE.slots[ch]){
        RT.BLE.write(ch,"DAT0:OK").catch(function(e){ log("DAT0:OK 回包失败 "+e.message); });
        log("CH"+ch+" 已回 DAT0:OK");
      } else log("CH"+ch+" 演示通道 · DAT0:OK 不发往设备");
    }
    renderAll();
  }
  /* Both real notify payloads and demo injections enter through this one function. */
  var FLUSH = {};
  function handleEvents(ch,events){
    events.forEach(function(ev){
      if(ev.type==="ack"){ log("CH"+ch+" ACK "+ev.cmd+":"+ev.status); toast(ev.cmd+":"+ev.status); }
      else if(ev.type==="data" && ev.frame) onFrame(ch,ev.frame);
    });
  }
  function feed(ch,text){
    if(!FEED[ch]) FEED[ch]=new P.FrameBuffer();
    handleEvents(ch,FEED[ch].push(text));
    if(FEED[ch].pending()){
      if(FLUSH[ch]) clearTimeout(FLUSH[ch]);
      FLUSH[ch]=setTimeout(function(){ handleEvents(ch,FEED[ch].flush()); },60);
    }
  }
  RT.BLE.onLog=log;
  RT.BLE.onPayload=feed;
  RT.BLE.onDrop=function(ch,reason){
    var c=S.channels[ch-1]; if(!c) return;
    c.connected=false; S.lost+=1; log("CH"+ch+" 断连 "+reason);
    renderAll(); toast("CH"+ch+" 断连 · 其余通道继续采集");
  };

  function renderStatus(){
    var bar=document.getElementById("runtimeBar"); if(!bar) return;
    var bound=S.channels.filter(function(c){return c.bound;}).length;
    var bleOK=RT.BLE.supported() && RT.BLE.secure();
    var bleTxt=bleOK?"<b style='color:var(--ok)'>Web Bluetooth 可用</b>":"<b style='color:var(--warn)'>演示模式（无 Web Bluetooth）</b>";
    bar.innerHTML=
      "<span>形态：<b>可运行 MVP v1.1</b></span>"+
      "<span>蓝牙："+bleTxt+" · 真实绑定 <b>"+bound+"</b> / "+MAX_CH+"</span>"+
      "<span>本地库：<b>"+RT.Store.backend+"</b> · 已存 <b>"+S.records.length+"</b> 条</span>"+
      "<span>通道：<b>"+online().length+" / "+MAX_CH+"</b> 在线</span>"+
      "<span class='rb-hint'>刷新页面数据不丢 · 支持真实 CSV 导入 / PNG 导出</span>";
  }
  var _renderAll=renderAll;
  renderAll=function(){
    _renderAll();
    renderStatus();
    var dl=$("demoLog");
    if(dl) dl.textContent="去抖丢弃 "+S.dedup+" 帧 · 未落库 "+S.lost+" 条 · 已存 "+S.records.length+" 条 · "+RT.Store.backend;
    if(S.page==="P2" && !S.overview) RT.Wake.request(); else RT.Wake.release();
    scheduleSync();
  };

  function simVal(){ return +(155.00+(Math.random()*0.36-0.15)).toFixed(2); }
  function resetDebounce(ch){ var c=S.channels[ch-1]; if(c) c.lastFrame=null; }
  function writeOrDemo(ch,cmd,fallback){
    var c=S.channels[ch-1];
    if(c && c.bound && RT.BLE.slots[ch]){
      RT.BLE.write(ch,cmd).then(function(){ toast(cmd+" 已发送"); })
        .catch(function(e){ toast(cmd+" 发送失败："+e.message); });
    } else { if(fallback) fallback(); toast("演示模式 · "+cmd+"（未绑定真实设备）"); }
  }

  /* ---- Bluetooth configuration (v1.1) ---------------------------------
     NAM is the only real, document-defined configuration command. The other
     three fields are device-side parameters with no vendor command set: they
     are REGISTERED locally per channel and NEVER written to the caliper. */
  function cfgCh(){
    var el=$("cfgChannel");
    var n=el?parseInt(String(el.value).replace(/^CH/i,""),10):S.activeCh;
    return (isFinite(n)&&n>=1&&n<=MAX_CH)?n:S.activeCh;
  }
  function currentCfg(ch){
    ch=ch||cfgCh();
    var c=S.channels[ch-1];
    return S.deviceCfg[ch]||{name:c?c.name:"GL-BLE#",autoEmit:"30",sleep:false,sleepMin:"5",terminator:"CR"};
  }
  function saveCfg(){
    var ch=cfgCh(), sw=q('[data-action="switch"][data-key="devSleep"]');
    S.deviceCfg[ch]={
      name:$("cfgName")?$("cfgName").value:"",
      autoEmit:$("cfgAutoEmit")?$("cfgAutoEmit").value:"30",
      sleep:!!(sw&&sw.classList.contains("on")),
      sleepMin:$("cfgSleepMin")?$("cfgSleepMin").value:"5",
      terminator:$("cfgTerminator")?$("cfgTerminator").value:"CR"
    };
    return ch;
  }
  function syncCfgForm(){
    var cfg=currentCfg();
    if($("cfgName")) $("cfgName").value=cfg.name;
    if($("cfgAutoEmit")) $("cfgAutoEmit").value=cfg.autoEmit;
    if($("cfgSleepMin")) $("cfgSleepMin").value=cfg.sleepMin;
    if($("cfgTerminator")) $("cfgTerminator").value=cfg.terminator;
    var sw=q('[data-action="switch"][data-key="devSleep"]');
    if(sw) sw.classList.toggle("on",!!cfg.sleep);
  }
  function handleHidInput(text){
    var cfg=currentCfg(), tokens=P.hidSplit(text,cfg.terminator), n=0;
    tokens.forEach(function(tok){
      var frame=P.hidToFrame(tok);
      if(frame){ feed(cfgCh(),frame); n++; }
    });
    log("HID 录入 "+tokens.length+" 段 → "+n+" 条有效读数 · 分隔符 "+cfg.terminator);
    return n;
  }
  var hidEl=$("hidInput");
  if(hidEl){
    hidEl.addEventListener("keydown",function(e){
      if(e.key==="Enter"||e.key==="Tab"){
        var v=hidEl.value.trim();
        if(v){ var n=handleHidInput(v+(e.key==="Enter"?"\r":"\t")); toast("HID 录入 "+n+" 条 · 已按 "+currentCfg().terminator+" 切分落库"); }
        hidEl.value=""; e.preventDefault();
      }
    });
    hidEl.addEventListener("input",function(){
      if(/[\r\n\t]/.test(hidEl.value)){
        var n=handleHidInput(hidEl.value); hidEl.value="";
        toast("HID 粘贴录入 "+n+" 条 · 已落库");
      }
    });
  }
  var cfgSel=$("cfgChannel");
  if(cfgSel) cfgSel.addEventListener("change",syncCfgForm);
  syncCfgForm();

  var OVERRIDE={
    "nam-apply":function(){
      var ch=saveCfg(), res=P.validateNam($("cfgName").value);
      if(!res.ok){ toast("名称不合法："+res.reason); log("CH"+ch+" NAM 校验失败 "+res.reason); return; }
      log("CH"+ch+" NAM 校验通过 "+res.cmd);
      writeOrDemo(ch,res.cmd);
    },
    "nam-read":function(){
      log("CH"+cfgCh()+" NAM 查询未实现 · 待 M0 抓包确认帧格式");
      toast("文档「获取模块广播名称」的帧格式有歧义，M0 实机抓包后再实现（不猜指令）");
    },
    "cfg-save":function(){
      var ch=saveCfg();
      log("CH"+ch+" 登记设备端参数（未下发）");
      toast("已登记 CH"+ch+" 的设备端参数 · 未下发到卡尺，需厂商指令集或 PC 工具");
    },
    "hid-sample":function(){
      var cfg=currentCfg(), sep=cfg.terminator==="TAB"?"\t":"\r";
      var text=["155.15","155.08","154.96"].join(sep)+sep;
      var n=handleHidInput(text);
      toast("示例 HID 输入：3 段 → "+n+" 条落库 · 分隔符 "+cfg.terminator);
    },
    scan:function(){
      if(!RT.BLE.supported() || !RT.BLE.secure()){
        S.scanned=true; renderAll();
        feed(S.activeCh,"DAT0:m"+fmt(simVal()));
        toast("无 Web Bluetooth，已走演示模式 · 7 通道可用，数据仍真实落库");
        return;
      }
      var free=null;
      for(var i=0;i<S.channels.length;i++){ if(!S.channels[i].bound){ free=S.channels[i]; break; } }
      if(!free){ toast("7 个通道已满，第 8 台不连接"); return; }
      $("p1state").textContent="等待系统设备选择器…";
      RT.BLE.bind(free.id).then(function(info){
        free.bound=true; free.name=info.name; free.mac=info.id; free.connected=true;
        S.scanned=true; log("CH"+free.id+" 绑定 "+info.name);
        renderAll(); toast("CH"+free.id+" 已连接并订阅 notify · 可继续添加下一台");
      }).catch(function(e){
        log("CH"+free.id+" 绑定失败 "+e.message); renderAll();
        toast("连接失败："+e.message+" · 演示通道仍可用");
      });
    },
    "connect-all":function(){
      S.channels.forEach(function(c){ c.connected=true; });
      S.connected=true; S.disconnected=false;
      S.channels.filter(function(c){return c.bound;}).forEach(function(c){
        RT.BLE.reconnect(c.id).catch(function(e){ log("CH"+c.id+" 重连失败 "+e.message); });
      });
      renderAll();
      toast("7 通道就绪 · 已对 "+S.channels.filter(function(c){return c.bound;}).length+" 台真实设备重新发现服务并订阅 notify");
    },
    disconnect:function(){
      S.channels.forEach(function(c){ if(c.bound) RT.BLE.drop(c.id); c.connected=false; });
      S.connected=false; S.disconnected=true; S.lost+=1;
      renderAll(); toast("全部断开 · 卡尺不保存历史数据，断连期间无法补录");
    },
    "ch-toggle":function(el){
      var id=parseInt(el.dataset.ch,10), c=S.channels[id-1];
      if(c.bound){
        if(c.connected){ RT.BLE.drop(id); c.connected=false; S.lost+=1; }
        else RT.BLE.reconnect(id).then(function(){ c.connected=true; renderAll(); })
          .catch(function(e){ toast("CH"+id+" 重连失败："+e.message); });
      } else {
        c.connected=!c.connected;
      }
      if(!c.connected && S.activeCh===id){
        var next=online()[0]; S.activeCh=next?next.id:id; S.current=next?next.value:S.current;
      }
      renderAll(); toast("CH"+id+(c.connected?" 已重连 · 重新发现服务并订阅 notify":" 已断连 · 其余通道继续采集"));
    },
    meas:function(){ resetDebounce(S.activeCh); writeOrDemo(S.activeCh,"MEAS",function(){ feed(S.activeCh,"DAT0:m"+fmt(simVal())); }); },
    frame:function(){ resetDebounce(S.activeCh); feed(S.activeCh,"DAT0:m"+fmt(simVal())); toast("演示注入一帧 DAT0（走真实解析 → 去抖 → 落库）"); },
    burst:function(){
      resetDebounce(S.activeCh);
      var v=fmt(simVal()), s="";
      for(var i=0;i<8;i++) s+="DAT1:m"+v;   // glued frames + same value
      feed(S.activeCh,s);
      toast("DAT1 连发 8 帧（粘包）· 按 ≤0.01mm / 300ms 去抖后仅 1 条落库");
    },
    "ch-burst":function(){
      S.channels.forEach(function(c){ resetDebounce(c.id); feed(c.id,"DAT1:m"+fmt(simVal())); });
      toast("7 路并发上报 · 跨通道不去重，7 条各落一条");
    },
    record:function(){
      if(S.disconnected){ toast("断连中，按钮已置灰"); return; }
      var c=S.channels[S.activeCh-1]; if(c) c.lastFrame=null;
      feed(S.activeCh,(S.mode==="manual"?"DAT0:m":"DAT1:m")+fmt(S.current));
      toast("CH"+S.activeCh+" 已记录第 "+S.records.length+" 条 · 收到即落库");
    },
    clr:function(){ writeOrDemo(S.activeCh,"CLR"); },
    "toggle-mode":function(){
      S.mode=S.mode==="manual"?"continuous":"manual";
      $("ackFrame").textContent=S.mode==="manual"?"DAT0:OK":"无需响应";
      writeOrDemo(S.activeCh,S.mode==="manual"?"MOD:0":"MOD:1");
      renderAll();
    },
    "upload-template":function(){
      var inp=document.getElementById("csvFile"); if(inp) inp.click();
    },
    "save-export":function(){
      if(!S.expPng && !S.expCsv){ toast("至少勾选一项导出内容"); return; }
      var stamp=P.nowStamp(), scope=S.focusCh===0?"ALL":"CH"+S.focusCh, parts=[];
      var vis=visRecords();
      if(S.expCsv){
        if(S.exportSplit){
          var n=0;
          S.channels.filter(function(c){return c.connected;}).forEach(function(c){
            var rows=vis.filter(function(r){return r.ch===c.id;});
            if(!rows.length) return;
            RT.downloadCsv(P.fileName(S.batch,stamp,"CH"+c.id,"csv"),P.recordsToCsv(rows,meta()));
            n++;
          });
          parts.push("CSV×"+n);
        } else {
          RT.downloadCsv(P.fileName(S.batch,stamp,scope,"csv"),P.recordsToCsv(vis,meta()));
          parts.push("CSV");
        }
      }
      if(S.expPng){
        var svg=q("#chartBox svg");
        if(svg){
          RT.downloadPng(svg,P.fileName(S.batch,stamp,scope,"png"))
            .then(function(){ toast("图表 PNG 已保存"); })
            .catch(function(e){ toast("PNG 导出失败："+e.message); });
          parts.push("PNG");
        }
      }
      log("导出 "+parts.join(" + ")+" · "+vis.length+" 条记录");
      toast("已导出 "+parts.join(" + ")+" · "+vis.length+" 条记录（浏览器下载目录）");
    },
    diag:function(){
      RT.download("gl_diag_"+P.nowStamp()+".log",LOG.join("\n")||"（无日志）","text/plain;charset=utf-8");
      toast("诊断日志已导出 · "+LOG.length+" 行");
    },
    "settings-save":function(){
      saveCfg();
      S.lower=parseFloat($("setLower").value)||154.80;
      S.upper=parseFloat($("setUpper").value)||155.20;
      S.records.forEach(function(r){ r.verdict=P.verdict(r.value_um,lowerUm(),upperUm()); });
      renderAll(); toast("设置已保存 · 已按新公差重算判定并落库");
    },
    "reset-demo":function(){
      S.records=seedRecords(); S.current=155.15; S.dedup=0; S.lost=0; S.disconnected=false;
      S.connected=true; S.scanned=true; S.mode="manual"; S.unit="mm"; S.chartType="line";
      S.channels=defaultChannels(); S.focusCh=0; S.activeCh=1; S.exportSplit=false;
      S.expPng=true; S.expCsv=true; S.page="P2";
      $("rawFrame").textContent="DAT0:m+155.15"; $("ackFrame").textContent="DAT0:OK";
      qa(".seg button").forEach(function(b){ b.classList.remove("on"); });
      q('[data-action="unit"][data-unit="mm"]').classList.add("on");
      q('[data-action="chart"][data-type="line"]').classList.add("on");
      FEED={}; LOG=[]; log("重置演示数据 · 7 通道恢复");
      renderAll(); toast("已重置 · 本地库将写入 "+S.records.length+" 条示例数据");
    }
  };

  document.addEventListener("click",function(e){
    var el=e.target.closest("[data-action]"); if(!el || el.closest(".mini-phone")) return;
    var a=el.dataset.action;
    if(OVERRIDE[a]){ e.stopPropagation(); e.preventDefault(); OVERRIDE[a](el,e); }
  },true);

  var csvInput=document.getElementById("csvFile");
  if(csvInput) csvInput.addEventListener("change",function(){
    var f=csvInput.files && csvInput.files[0]; if(!f) return;
    var reader=new FileReader();
    reader.onload=function(){
      var parsed=P.csvParse(String(reader.result));
      var imported=P.csvToRecords(parsed,{batch:S.batch,lowerUm:lowerUm(),upperUm:upperUm()});
      var mp=$("mapPreview");
      if(mp){
        mp.classList.remove("hidden");
        mp.innerHTML="<b>字段映射预览</b><br>识别 "+parsed.headers.filter(Boolean).length+" 列 · 未识别 "+parsed.skipped.length+
          " 列（"+ (parsed.skipped.join("、")||"无") +"）整列跳过 · "+(parsed.bomRemoved?"已剥离 UTF-8 BOM":"无 BOM")+"<br>"+
          "数据行 "+parsed.rows.length+" · 可导入 "+imported.records.length+" · 无效 "+imported.badRows+" 行 · 按 日期+时间+通道+数值 去重<br>"+
          "<b>CHANNEL 列</b>用于还原通道归属（CH1–CH7）；历史 CH8–CH10 保留为「历史通道」，不丢数据";
      }
      if(imported.records.length){
        S.records=S.records.concat(imported.records);
        log("导入 CSV "+f.name+" · "+imported.records.length+" 条");
        renderAll(); toast("已导入 "+imported.records.length+" 条 · 跳过 "+parsed.skipped.length+" 列 / "+imported.badRows+" 行");
      } else toast("未发现可导入数据行（仅表头时只做模板映射预览）");
      csvInput.value="";
    };
    reader.readAsText(f,"UTF-8");
  });

  function initRuntime(){
    RT.Store.init().then(function(backend){
      log("存储后端 "+backend);
      return RT.Store.load();
    }).then(function(saved){
      if(saved && saved.length){
        S.records=saved.map(function(r){
          if(r.value_um==null) r.value_um=Math.round((r.v||0)*1000);
          if(r.v==null) r.v=P.toMm(r.value_um);
          if(!r.ch) r.ch=1;
          return r;
        });
        log("从本地库恢复 "+S.records.length+" 条");
      } else {
        S.records=seedRecords();
        return RT.Store.replaceAll(S.records).then(function(){ log("首次运行写入示例数据 "+S.records.length+" 条"); });
      }
    }).then(function(){
      renderAll();
      toast("MVP 就绪 · 存储="+RT.Store.backend+" · 蓝牙="+(RT.BLE.supported()&&RT.BLE.secure()?"可用":"演示模式"));
      /* Smoke-test entry: ?selftest=1 drives the real chain after init settles. */
      if(location.search.indexOf("selftest=1")>=0){
        var before=S.records.length;
        OVERRIDE.frame(); OVERRIDE["ch-burst"](); OVERRIDE.burst();
        setTimeout(function(){
          /* v1.1 additive checks: NAM validation, HID split/frame, legacy CSV,
             and a real HID -> parse -> dedup -> store round trip. */
          var extra={};
          extra.nam=P.validateNam("GL-BLE#0187").ok && !P.validateNam("0187").ok;
          extra.hidTokens=P.hidSplit("155.15\t155.08\t154.96\t","TAB").length;
          extra.hidFrame=P.hidToFrame("-1.2485 in")==="DAT0:i-1.2485";
          var legacyCsv="\uFEFFDATE,TIME,VALUE_MM,CHANNEL\r\n2026-11-12,09:00:00,155.15,CH10\r\n";
          var legacyRec=P.csvToRecords(P.csvParse(legacyCsv),{}).records;
          extra.legacy=legacyRec.length===1 && legacyRec[0].ch===10 && legacyRec[0].legacy===true;
          extra.cfgCard=!!document.getElementById("cfgChannel");
          extra.slots=S.channels.length;
          var beforeHid=S.records.length;
          OVERRIDE["hid-sample"]();
          setTimeout(function(){
            extra.hidRows=S.records.length-beforeHid;
            renderAll();
            var d=document.createElement("div");
            d.id="selftestResult"; d.style.display="none";
            d.setAttribute("data-before",String(before));
            d.setAttribute("data-after",String(S.records.length));
            d.setAttribute("data-dedup",String(S.dedup));
            d.setAttribute("data-store",RT.Store.backend);
            d.setAttribute("data-rows",String(document.querySelectorAll("#liveBody tr").length));
            d.setAttribute("data-extra",JSON.stringify(extra));
            var rb=document.getElementById("runtimeBar");
            d.setAttribute("data-bar",rb?rb.textContent.replace(/\s+/g," ").trim():"MISSING");
            document.body.appendChild(d);
            var clean=S.records.map(function(r){ var o={}; for(var k in r){ if(k!=="id") o[k]=r[k]; } return o; });
            RT.Store.replaceAll(clean);
          },150);
        },350);
      }
    }).catch(function(e){ log("初始化异常 "+e.message); renderAll(); });
    if(RT.BLE.supported() && RT.BLE.secure()) log("Web Bluetooth 就绪 · P1「扫描设备」逐台添加，最多 7 台");
    else log("无 Web Bluetooth 或非安全上下文 → 演示模式（解析 / 去抖 / 落库链路相同）");
    document.addEventListener("visibilitychange",function(){
      if(!document.hidden && S.page==="P2") RT.Wake.request();
    });
    window.addEventListener("pagehide",function(){
      try{ RT.Store.replaceAll(S.records); }catch(e){}
    });
  }
  initRuntime();
})();
