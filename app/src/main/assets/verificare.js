/* ========================================================== verificare.js
 *
 * Un singur buton, care face tot: se leaga daca nu e legat, asculta magistrala
 * un minut, masoara pachetul, intreaba modulele de coduri de eroare, si scoate
 * o fisa de sanatate.
 *
 * Ruleaza in pagina, adica inauntrul aplicatiei. Nu e nimic de scris in Kotlin.
 *
 * Se sprijina pe functiile paginii, care sunt toate la nivelul de sus in
 * index.html si deci ajung pe window: conectat, conecteaza, cere, asculta,
 * desfaCadru, primesteCadru, scrie, ocupat. Plus Diagnoza, din diagnoza.js.
 *
 * Legarea, un singur rand in index.html:
 *     G("btnVerifica").addEventListener("click", function(){ Verificare.ruleaza(); });
 */
(function (global) {
  "use strict";

  var SECUNDE_ASCULTARE = 60;

  /* Cadrele pachetului, asa cum le-am confirmat pe masina. Sunt scrise aici, si
     nu luate din harta semnalelor, fiindca fisa de sanatate cere lucruri pe care
     harta nu le descrie: cele 29 de celule una cate una, cei 10 senzori de
     temperatura, si perechea tensiune-curent la aceeasi clipa. */
  var CEL = ["18FD90F3","18FD91F3","18FD92F3","18FD93F3","18FD94F3",
             "18FD95F3","18FD96F3","18FD97F3"];   /* 7 x 4 + 1 = 29 de celule */
  var NR_CELULE = 29;
  var TEMP1 = "18FDD0F3", TEMP2 = "18FDE0F3";     /* 8 + 2 senzori, decalaj -40 */
  var PACHET = "1803D0F3";                        /* tensiune la 4, curent la 6 */
  var STARE  = "1808D0F3";                        /* Ah la 0, SOC la 2, SOH la 3 */
  var EXTREME = "1802D0F3";                       /* cel. max 2, min 4, tmax 6, tmin 7 */
  var ABS_ = "1801D0DB", EPS_ = "1801D0D9", AIRBAG_ = "1801D0F1";

  /* Pragurile din lista de fabrica, cele care se pot verifica din mers.
     Restul stau in coduri_cityzen.json; aici sunt doar cele cu care comparam. */
  var PRAGURI = {
    celulaMax:   { val: 3700, u: "mV", text: "supratensiune celulă" },
    difTensiune: { val: 400,  u: "mV", text: "diferență între celule" },
    difTemp:     { val: 10,   u: "°C", text: "diferență de temperatură" },
    tempMax:     { val: 50,   u: "°C", text: "temperatură mare la descărcare" },
    tempMin:     { val: -10,  u: "°C", text: "temperatură mică", subZero: true },
    soc:         { val: 20,   u: "%",  text: "nivel scăzut", subZero: true },
    soh:         { val: 80,   u: "%",  text: "sănătate scăzută", subZero: true }
  };

  function le(o, i, n) {           /* numar mic-la-mare din sirul de octeti */
    var v = 0;
    for (var k = n - 1; k >= 0; k--) v = (v << 8) | o[i + k];
    return v;
  }
  function oct(hex) {
    var o = [];
    for (var i = 0; i + 1 < hex.length; i += 2) o.push(parseInt(hex.substr(i, 2), 16));
    return o;
  }
  function med(a) { return a.length ? a.reduce(function (x, y) { return x + y; }, 0) / a.length : null; }
  function panta(x, y) {          /* dreapta celor mai mici patrate, y = a*x + b */
    var n = x.length;
    if (n < 30) return null;
    var sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (var i = 0; i < n; i++) { sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i]; }
    var num = n * sxy - sx * sy, nm = n * sxx - sx * sx;
    if (!nm) return null;
    return { a: num / nm, b: (sy - (num / nm) * sx) / n };
  }
  function j(m) { try { global.scrie(m); } catch (e) {} }

  /* ---------------------------------------------------------- strangerea */

  function asteaptaLegatura() {
    if (global.conectat) return Promise.resolve(true);
    j("verificare: nu ești legat, pornesc conectarea");
    try { global.conecteaza(); } catch (e) { return Promise.reject(e); }
    return new Promise(function (rez, resp) {
      var t0 = Date.now();
      var ceas = setInterval(function () {
        if (global.conectat) { clearInterval(ceas); rez(true); }
        else if (Date.now() - t0 > 90000) { clearInterval(ceas); resp(new Error("nu s-a legat în 90 de secunde")); }
      }, 500);
    });
  }

  /* Ascultare continua, fara opririle de la fiecare trei secunde ale buclei
     obisnuite. Fiecare rand valid e si trimis mai departe catre pagina, ca
     ecranul sa ramana viu in timpul verificarii. */
  function asculta(secunde, culege) {
    return new Promise(function (rez) {
      var pana = Date.now() + secunde * 1000;
      var pas = function () {
        if (Date.now() >= pana || !global.conectat) { rez(); return; }
        global.asculta(Math.min(5000, pana - Date.now()), function (rand) {
          var c = global.desfaCadru(rand);
          if (!c) return;
          culege(c);
          try { global.primesteCadru(c); } catch (e) {}
        }).catch(function () {}).then(pas);
      };
      pas();
    });
  }

  /* ---------------------------------------------------------- masuratorile */

  function masoara(strans) {
    var r = { cadre: strans.length, celule: [], note: [] };

    var cel = [], curentLaCelule = [], temp = [], pachet = [], stare = [], extreme = [];
    var sasiu = { abs: null, eps: null, airbag: null, cadreAirbag: 0 };
    var i, k;

    for (i = 0; i < NR_CELULE; i++) cel.push([]);

    /* curentul, pastrat cu clipa lui, ca sa il putem lipi de fiecare celula */
    for (i = 0; i < strans.length; i++) {
      var c = strans[i], o = c.o, t = c.t;
      if (c.id === PACHET && o.length >= 8) pachet.push({ t: t, v: le(o, 4, 2) * 0.1, i: le(o, 6, 2) * 0.1 - 1000 });
    }
    pachet.sort(function (a, b) { return a.t - b.t; });

    function curentLa(t) {
      if (!pachet.length) return null;
      var lo = 0, hi = pachet.length - 1;
      while (lo < hi) { var m = (lo + hi) >> 1; if (pachet[m].t < t) lo = m + 1; else hi = m; }
      return Math.abs(pachet[lo].t - t) < 400 ? pachet[lo].i : null;
    }

    for (i = 0; i < strans.length; i++) {
      var x = strans[i], oo = x.o;
      var p = CEL.indexOf(x.id);
      if (p >= 0 && oo.length >= 8) {
        var cur = curentLa(x.t);
        for (k = 0; k < 4; k++) {
          var idx = p * 4 + k;
          if (idx >= NR_CELULE) break;
          var mv = le(oo, k * 2, 2);
          if (mv < 1000 || mv > 4500) continue;
          cel[idx].push(mv);
          if (cur !== null) curentLaCelule.push({ cel: idx, i: cur, v: mv });
        }
      } else if (x.id === TEMP1 && oo.length >= 8) {
        for (k = 0; k < 8; k++) temp.push(oo[k] - 40);
      } else if (x.id === TEMP2 && oo.length >= 2) {
        for (k = 0; k < 2; k++) temp.push(oo[k] - 40);
      } else if (x.id === STARE && oo.length >= 8) {
        stare.push({ ah: le(oo, 0, 2) * 0.01, soc: oo[2], soh: oo[3], celMed: le(oo, 6, 2) });
      } else if (x.id === EXTREME && oo.length >= 8) {
        extreme.push({ max: le(oo, 2, 2), min: le(oo, 4, 2), tmax: oo[6] - 40, tmin: oo[7] - 40 });
      } else if (x.id === ABS_ && oo.length >= 1) {
        sasiu.abs = (oo[0] & 0x03) === 1 ? "DEFECT" : "în regulă";
      } else if (x.id === EPS_ && oo.length >= 2) {
        sasiu.eps = (oo[0] || oo[1]) ? "DEFECT" : "în regulă";
      } else if (x.id === AIRBAG_) {
        sasiu.cadreAirbag++;
        sasiu.airbag = (oo[0] & 1) ? "DEFECT" : "în regulă";
      }
    }

    /* celulele, una cate una */
    var toate = [], slabe = [];
    for (i = 0; i < NR_CELULE; i++) {
      if (!cel[i].length) continue;
      var m = med(cel[i]);
      var xs = [], ys = [];
      for (k = 0; k < curentLaCelule.length; k++) {
        if (curentLaCelule[k].cel === i) { xs.push(curentLaCelule[k].i); ys.push(curentLaCelule[k].v); }
      }
      var pp = panta(xs, ys);
      var ob = { nr: i + 1, medie: m, min: Math.min.apply(null, cel[i]), max: Math.max.apply(null, cel[i]),
                 esantioane: cel[i].length, rezistenta: pp ? -pp.a : null };
      r.celule.push(ob); toate.push(m);
      if (ob.rezistenta !== null) slabe.push(ob);
    }

    if (r.celule.length) {
      var mm = med(toate);
      r.celule.forEach(function (c) { c.abatere = c.medie - mm; });
      r.celulaMin = Math.min.apply(null, r.celule.map(function (c) { return c.min; }));
      r.celulaMax = Math.max.apply(null, r.celule.map(function (c) { return c.max; }));
      r.imprastiere = Math.round(r.celulaMax - r.celulaMin);
    }
    if (slabe.length) {
      slabe.sort(function (a, b) { return b.rezistenta - a.rezistenta; });
      r.rezistentaMediana = slabe[Math.floor(slabe.length / 2)].rezistenta;
      r.ceaMaiSlaba = slabe[0];
      r.ceaMaiBuna = slabe[slabe.length - 1];
      r.rezistentaPachet = slabe.reduce(function (s, c) { return s + c.rezistenta; }, 0);
    }
    if (temp.length) {
      r.tempMin = Math.min.apply(null, temp);
      r.tempMax = Math.max.apply(null, temp);
      r.difTemp = r.tempMax - r.tempMin;
      r.senzoriTemp = temp.length;
    }
    if (pachet.length) {
      var pv = panta(pachet.map(function (x) { return x.i; }), pachet.map(function (x) { return x.v; }));
      r.tensiuneMin = Math.min.apply(null, pachet.map(function (x) { return x.v; }));
      r.tensiuneMax = Math.max.apply(null, pachet.map(function (x) { return x.v; }));
      r.curentMax = Math.max.apply(null, pachet.map(function (x) { return x.i; }));
      r.curentMin = Math.min.apply(null, pachet.map(function (x) { return x.i; }));
      if (pv) { r.rezistentaDinPachet = -pv.a * 1000; r.tensiuneLaGol = pv.b; }
    }
    if (stare.length) {
      r.soc = stare[stare.length - 1].soc;
      r.soh = stare[stare.length - 1].soh;
      r.ah = stare[stare.length - 1].ah;
      if (r.soc > 0) r.capacitate = r.ah * 100 / r.soc;
    }
    if (extreme.length) r.extremeBms = extreme[extreme.length - 1];
    r.sasiu = sasiu;
    if (!sasiu.cadreAirbag) r.note.push("cadrul de airbag (1801D0F1) nu a apărut deloc — starea lui nu se poate citi");
    if (r.celule.length && r.celule.length < NR_CELULE)
      r.note.push("au venit doar " + r.celule.length + " celule din " + NR_CELULE + " — ascultarea a fost scurtă");
    return r;
  }

  /* ------------------------------------------------------------ cantarirea */

  function verdicte(m) {
    var v = [];
    function pune(nume, masurat, prag, u, mesaj) {
      if (masurat === undefined || masurat === null) return;
      var rau = prag.subZero ? masurat <= prag.val : masurat >= prag.val;
      var marja = prag.subZero ? masurat - prag.val : prag.val - masurat;
      v.push({ nume: nume, masurat: masurat, prag: prag.val, u: u, bine: !rau, marja: marja, text: mesaj });
    }
    pune("Celulă maximă", m.celulaMax, PRAGURI.celulaMax, "mV", PRAGURI.celulaMax.text);
    pune("Diferență între celule", m.imprastiere, PRAGURI.difTensiune, "mV", PRAGURI.difTensiune.text);
    pune("Temperatură maximă", m.tempMax, PRAGURI.tempMax, "°C", PRAGURI.tempMax.text);
    pune("Temperatură minimă", m.tempMin, PRAGURI.tempMin, "°C", PRAGURI.tempMin.text);
    pune("Diferență de temperatură", m.difTemp, PRAGURI.difTemp, "°C", PRAGURI.difTemp.text);
    pune("Nivel de încărcare", m.soc, PRAGURI.soc, "%", PRAGURI.soc.text);
    pune("Stare de sănătate", m.soh, PRAGURI.soh, "%", PRAGURI.soh.text);

    /* Subtensiunea are prag dupa temperatura, asa cum scrie in lista de fabrica. */
    if (m.celulaMin !== undefined && m.tempMax !== undefined) {
      var p = m.tempMax > 20 ? 3100 : (m.tempMax > 0 ? 3000 : 2900);
      v.push({ nume: "Celulă minimă", masurat: m.celulaMin, prag: p, u: "mV",
               bine: m.celulaMin > p, marja: m.celulaMin - p,
               text: "subtensiune la " + (m.tempMax > 20 ? "peste 20 °C" : (m.tempMax > 0 ? "0–20 °C" : "sub 0 °C")) });
    }
    return v;
  }

  /* --------------------------------------------------------------- raportul */

  function text(R) {
    var l = [], m = R.masuratori;
    l.push("FIȘA MAȘINII · " + new Date(R.cand).toLocaleString("ro-RO"));
    l.push("ascultare " + R.secunde + " s · " + m.cadre + " cadre");
    l.push("");
    l.push("PACHETUL");
    if (m.soc !== undefined) l.push("  încărcare " + m.soc + "% · sănătate raportată " + m.soh + "% · " + m.ah.toFixed(2) + " Ah rămași");
    if (m.capacitate) l.push("  capacitate implicată " + m.capacitate.toFixed(1) + " Ah");
    if (m.tensiuneLaGol) l.push("  tensiune la gol " + m.tensiuneLaGol.toFixed(1) + " V · sub sarcină până la " + m.tensiuneMin.toFixed(1) + " V");
    if (m.curentMax !== undefined) l.push("  curent între " + m.curentMin.toFixed(0) + " și " + m.curentMax.toFixed(0) + " A");
    if (m.celule.length) l.push("  " + m.celule.length + " celule · " + m.celulaMin + "–" + m.celulaMax + " mV · împrăștiere " + m.imprastiere + " mV");
    if (m.senzoriTemp) l.push("  " + m.senzoriTemp + " citiri de temperatură · " + m.tempMin + "–" + m.tempMax + " °C");
    l.push("");

    if (m.rezistentaMediana) {
      l.push("REZISTENȚA INTERNĂ  (din căderea de tensiune sub sarcină)");
      l.push("  mediană " + m.rezistentaMediana.toFixed(3) + " mΩ pe celulă · " + m.rezistentaPachet.toFixed(1) + " mΩ însumat");
      if (m.rezistentaDinPachet) l.push("  verificare pe pachet: " + m.rezistentaDinPachet.toFixed(1) + " mΩ");
      l.push("  cea mai slabă: celula " + m.ceaMaiSlaba.nr + " · " + m.ceaMaiSlaba.rezistenta.toFixed(3) + " mΩ");
      l.push("  cea mai bună:  celula " + m.ceaMaiBuna.nr + " · " + m.ceaMaiBuna.rezistenta.toFixed(3) + " mΩ");
      var raspandire = 100 * (m.ceaMaiSlaba.rezistenta - m.ceaMaiBuna.rezistenta) / m.ceaMaiBuna.rezistenta;
      l.push("  împrăștiere între celule: " + raspandire.toFixed(0) + "%" + (raspandire > 30 ? "  ← de urmărit" : ""));
      l.push("");
    }

    l.push("FAȚĂ DE PRAGURILE DIN FABRICĂ");
    R.verdicte.forEach(function (v) {
      l.push("  " + (v.bine ? "ok  " : "ATENȚIE  ") + v.nume + ": " + v.masurat + " " + v.u +
             "  (prag " + v.prag + " " + v.u + ", " + v.text + ")");
    });
    l.push("");

    l.push("ȘASIU");
    l.push("  ABS: " + (m.sasiu.abs || "nu s-a văzut"));
    l.push("  servodirecție: " + (m.sasiu.eps || "nu s-a văzut"));
    l.push("  airbag: " + (m.sasiu.airbag || "nu s-a văzut"));
    l.push("");

    if (R.diagnoza) {
      l.push(global.Diagnoza.text(R.diagnoza));
    } else {
      l.push("DIAGNOZĂ: nu s-a putut face" + (R.eroareDiagnoza ? " (" + R.eroareDiagnoza + ")" : ""));
    }
    if (m.note.length) { l.push(""); l.push("DE ȘTIUT"); m.note.forEach(function (n) { l.push("  " + n); }); }
    return l.join("\n");
  }

  /* ------------------------------------------------------------------ totul */

  var Verificare = {
    secunde: SECUNDE_ASCULTARE,

    ruleaza: function (optiuni) {
      optiuni = optiuni || {};
      var sec = optiuni.secunde || Verificare.secunde;
      var faraDiagnoza = optiuni.faraDiagnoza === true;
      var R = { cand: new Date().toISOString(), secunde: sec };
      var strans = [];
      var eraOcupat = global.ocupat;

      return asteaptaLegatura()
        .then(function () {
          /* oprim bucla obisnuita, ca sa nu se bata pe adaptor cu noi */
          global.ocupat = true;
          j("verificare: ascult magistrala " + sec + " de secunde");
          return asculta(sec, function (c) {
            strans.push({ id: c.id, o: oct(c.oct), t: Date.now() });
          });
        })
        .then(function () {
          j("verificare: am strâns " + strans.length + " cadre · le măsor");
          R.masuratori = masoara(strans);
          R.verdicte = verdicte(R.masuratori);
          if (faraDiagnoza || !global.Diagnoza) { R.diagnoza = null; return null; }
          j("verificare: întreb modulele de coduri de eroare");
          return global.Diagnoza.citeste()
            .then(function (d) { R.diagnoza = d; })
            .catch(function (e) { R.diagnoza = null; R.eroareDiagnoza = e && e.message ? e.message : String(e); });
        })
        .then(function () {
          global.ocupat = eraOcupat === true;
          R.text = text(R);
          var rele = R.verdicte.filter(function (v) { return !v.bine; }).length;
          var coduri = R.diagnoza ? R.diagnoza.total : 0;
          j("verificare gata · " + (rele ? rele + " măsurători peste prag" : "toate măsurătorile în regulă") +
            " · " + (coduri ? coduri + " cod(uri) de eroare" : "niciun cod de eroare"));
          return R;
        })
        .catch(function (e) {
          global.ocupat = eraOcupat === true;
          j("verificarea a eșuat: " + (e && e.message ? e.message : e));
          throw e;
        });
    },

    text: text
  };

  global.Verificare = Verificare;
})(window);
