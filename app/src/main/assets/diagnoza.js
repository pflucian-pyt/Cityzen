/* ============================================================ diagnoza.js
 *
 * Citirea codurilor de eroare stocate in modulele masinii.
 *
 * Pe Cityzen, ascultarea obisnuita si diagnoza nu pot merge in acelasi timp.
 * Ascultarea sta pe ATMA, cu filtrele deschise, pe 29 de biti. Diagnoza cere
 * antete fixate, control de flux si — pentru 7E0 — protocolul pe 11 biti. Deci
 * modulul asta OPRESTE ascultarea, intreaba modulele, si la final pune adaptorul
 * inapoi exact cum l-a gasit.
 *
 * Nu sterge nimic. Serviciul de stergere (14 pe UDS, 04 pe OBD) nu e scris aici
 * si nici nu trebuie adaugat fara o discutie serioasa: pe masina asta, un cod
 * sters inseamna si pierderea istoricului de care avem nevoie, iar unele coduri
 * de nivel 3 se sterg singure doar dupa ce defectul chiar dispare.
 *
 * Legarea, din index.html, dupa ce adaptorul e conectat:
 *
 *    Diagnoza.leaga({
 *      cere:    cere,                  // functia paginii, cea cu timp de asteptare
 *      jurnal:  scrie,                 // scrie un rand in jurnalul paginii
 *      coduri:  CODURI_CITYZEN         // obiectul din coduri_cityzen.json, sau null
 *    });
 *    Diagnoza.citeste().then(function(raport){ ... });
 */
(function (global) {
  "use strict";

  var leg = null;
  var CATALOG = null;

  /* Modulele pe care le intrebam. Ordinea conteaza: 7E0 a raspuns deja in
     sesiunile de culegere, deci incepem cu el si nu pierdem timp daca restul tac.
     Cele pe doua caractere sunt adrese pe 29 de biti — cheia lor e ultimul octet
     al identificatorului difuzat: F3 bateria, F8 motorul, D0 vehiculul. */
  var TINTE = [
    { a: "7E0", nume: "vehicul (VCU)",        mod: "VCU" },
    { a: "7E1", nume: "invertor (MCU)",       mod: "MCU" },
    { a: "7E2", nume: "necunoscut",           mod: "?"   },
    { a: "7E3", nume: "baterie (BMS)",        mod: "BMS" },
    { a: "F3",  nume: "baterie (BMS)",        mod: "BMS" },
    { a: "F8",  nume: "motor și invertor",    mod: "MCU" },
    { a: "D0",  nume: "vehicul (VCU)",        mod: "VCU" },
    { a: "17",  nume: "bord",                 mod: "?"   },
    { a: "DB",  nume: "ABS",                  mod: "?"   },
    { a: "D9",  nume: "servodirecție",        mod: "?"   }
  ];

  var MOTIVE = {
    "11": "serviciu nerecunoscut",
    "12": "subfuncție nerecunoscută",
    "13": "lungime greșită a cererii",
    "22": "condiții nepotrivite acum",
    "31": "cerere în afara limitelor",
    "33": "acces refuzat, cere autentificare",
    "78": "răspunsul întârzie, modulul lucrează",
    "7F": "serviciul nu merge în sesiunea curentă"
  };

  /* starea celor patru biti care ne intereseaza din octetul de stare al unui cod */
  function stareCod(s) {
    var b = [];
    if (s & 0x01) b.push("prezent acum");
    if (s & 0x08) b.push("neconfirmat");
    if (s & 0x20) b.push("de la ultima ștergere");
    if (s & 0x40) b.push("confirmat");
    return b.length ? b.join(", ") : "stocat";
  }

  function curata(r) {
    return String(r || "").replace(/[\r\n>]+/g, " ").replace(/\s+/g, " ").trim();
  }

  /* Octetii raspunsului, in ordine.
   *
   * Nu se poate strange tot ce e hexazecimal intr-un sir: cu ATH1 pornit, antetul
   * pe 11 biti are TREI caractere (7E8), iar lipit de restul strica alinierea pe
   * octeti si tot ce urmeaza se citeste decalat cu o jumatate de octet. De aceea
   * mergem pe simboluri: ATS1 pune spatii, deci pastram doar simbolurile de exact
   * doua caractere hexazecimale si aruncam antetele, de 3 sau de 8 caractere. */
  function octeti(r) {
    var out = [];
    String(r || "").split(/[\r\n]+/).forEach(function (rand) {
      rand.trim().split(/\s+/).forEach(function (s) {
        s = s.toUpperCase();
        if (/^[0-9A-F]{2}$/.test(s)) out.push(s);
      });
    });
    return out;
  }
  function hexDoar(r) { return octeti(r).join(""); }
  function jos(m) { if (leg && leg.jurnal) { try { leg.jurnal(m); } catch (e) {} } }

  function peUnsprezece(t) { return t.length === 3; }

  /* Pregatirea adresarii, copiata din unealta de culegere fiindca acolo a fost
     verificata pe masina: pe 11 biti raspunsul vine pe tinta+8, pe 29 de biti
     cererea pleaca pe 18DA<tinta>F1 si raspunsul vine pe 18DAF1<tinta>. */
  function catre(t) {
    if (peUnsprezece(t)) {
      var r11 = (parseInt(t, 16) + 8).toString(16).toUpperCase();
      return leg.cere("ATSP6", 2000)
        .then(function () { return leg.cere("ATCAF1"); })
        .then(function () { return leg.cere("ATSH " + t, 700); })
        .then(function () { return leg.cere("ATCRA " + r11, 700); })
        .then(function () { return leg.cere("ATFCSH " + t, 700); })
        .then(function () { return leg.cere("ATFCSD 300000", 700); })
        .then(function () { return leg.cere("ATFCSM1", 700); });
    }
    var cer = "18DA" + t + "F1", rasp = "18DAF1" + t;
    return leg.cere("ATSP7", 2000)
      .then(function () { return leg.cere("ATCAF1"); })
      .then(function () { return leg.cere("ATSH " + cer, 700); })
      .then(function () { return leg.cere("ATCRA " + rasp, 700); })
      .then(function () { return leg.cere("ATFCSH " + cer, 700); })
      .then(function () { return leg.cere("ATFCSD 300000", 700); })
      .then(function () { return leg.cere("ATFCSM1", 700); });
  }

  function refuz(r, cmd) {
    var x = hexDoar(r);
    var srv = cmd.replace(/\s/g, "").substring(0, 2);
    var i = x.indexOf("7F" + srv);
    if (i < 0) return null;
    var nrc = x.substr(i + 4, 2);
    return { cod: nrc, text: MOTIVE[nrc] || ("cod " + nrc) };
  }

  /* Raspunsul pozitiv la 19 02 arata asa:
        59 02 <masca disponibila> [3 octeti de cod + 1 octet de stare] x N
     Adaptorul poate rupe raspunsul pe mai multe randuri, cu antet in fata, deci
     taiem tot ce e inainte de 5902 si mergem din patru in patru. */
  function parseaza1902(r) {
    var x = hexDoar(r);
    var i = x.indexOf("5902");
    if (i < 0) return null;
    var c = x.substring(i + 6);
    var out = [];
    for (var k = 0; k + 8 <= c.length; k += 8) {
      var a = c.substr(k, 2), b = c.substr(k + 2, 2), d = c.substr(k + 4, 2), s = c.substr(k + 6, 2);
      if (a === "00" && b === "00" && d === "00") continue;
      out.push({ brut: a + b + d, stare: parseInt(s, 16) });
    }
    return out;
  }

  /* OBD clasic: 43 <cate coduri> apoi perechi de doi octeti.
     Octetul de numarare se sare — altfel toate codurile ies decalate cu un octet. */
  function parseaza03(r) {
    var o = octeti(r);
    var i = o.indexOf("43");
    if (i < 0) return null;
    var c = o.slice(i + 2);
    var out = [];
    for (var k = 0; k + 2 <= c.length; k += 2) {
      var v = c[k] + c[k + 1];
      if (v === "0000") continue;
      var n0 = parseInt(v.charAt(0), 16);
      out.push({ brut: v, text: "PCBU".charAt(n0 >> 2) + (n0 & 3) + v.substring(1) });
    }
    return out;
  }

  /* Codurile din lista de fabrica sunt numere zecimale mici (110, 191, 46), nu
     coduri OBD pe trei octeti. Nu stim inca in ce fel le imbraca modulele, asa ca
     incercam mai multe citiri ale aceluiasi cod si aratam ce se potriveste, cu
     rezerva de rigoare. */
  function potrivire(brut, modul) {
    if (!CATALOG || !CATALOG.coduri) return null;
    var n = parseInt(brut, 16);
    var candidati = [n & 0xFF, n & 0xFFFF, (n >> 8) & 0xFF, n & 0x3FF];
    var gasite = [];
    CATALOG.coduri.forEach(function (c) {
      if (c.cod === null) return;
      if (candidati.indexOf(c.cod) < 0) return;
      if (modul && modul !== "?" && c.modul !== modul) return;
      if (gasite.some(function (g) { return g.cod === c.cod && g.nivel === c.nivel; })) return;
      gasite.push(c);
    });
    return gasite.length ? gasite : null;
  }

  /* o intrebare, cu reincercare daca modulul cere ragaz (0x78) */
  function intreaba(cmd, ms, incercari) {
    incercari = incercari === undefined ? 2 : incercari;
    return leg.cere(cmd, ms || 3000).then(function (r) {
      var n = refuz(r, cmd);
      if (n && n.cod === "78" && incercari > 0) return intreaba(cmd, ms, incercari - 1);
      return r;
    });
  }

  function unModul(t) {
    var rez = { adresa: t.a, nume: t.nume, modul: t.mod, raspunde: false, coduri: [], note: [] };
    return catre(t.a)
      .then(function () { return intreaba("1902FF", 4000); })
      .then(function (r) {
        var lista = parseaza1902(r);
        if (lista) { rez.raspunde = true; return lista; }
        var n = refuz(r, "1902");
        if (n) {
          rez.raspunde = true;
          rez.note.push("a refuzat masca FF: " + n.text);
          return intreaba("190209", 4000).then(function (r2) {
            var l2 = parseaza1902(r2);
            if (l2) return l2;
            var n2 = refuz(r2, "1902");
            if (n2) rez.note.push("a refuzat și masca 09: " + n2.text);
            return null;
          });
        }
        return null;
      })
      .then(function (lista) {
        if (lista) {
          lista.forEach(function (c) {
            c.stareText = stareCod(c.stare);
            c.potrivire = potrivire(c.brut, t.mod);
            rez.coduri.push(c);
          });
          return null;
        }
        /* pe 11 biti mai incercam si diagnoza clasica, ca plasa de siguranta */
        if (!peUnsprezece(t.a)) return null;
        return intreaba("03", 3000).then(function (r) {
          var l = parseaza03(r);
          if (l && l.length) {
            rez.raspunde = true;
            rez.note.push("a răspuns doar la diagnoza clasică, modul 03");
            l.forEach(function (c) {
              c.stareText = "stocat";
              c.potrivire = potrivire(c.brut, t.mod);
              rez.coduri.push(c);
            });
          } else if (l) {
            rez.raspunde = true;
          }
          return null;
        });
      })
      .then(function () { return rez; })
      .catch(function (e) { rez.note.push("eroare: " + (e && e.message ? e.message : e)); return rez; });
  }

  var Diagnoza = {
    leaga: function (o) {
      leg = o;
      CATALOG = o.coduri || null;
      return this;
    },

    /* Pune adaptorul in starea de ascultare, exact cea din pornirea aplicatiei.
       Se cheama si la final, ca diagnoza sa nu lase magistrala pe 11 biti. */
    inapoiLaAscultare: function () {
      return leg.cere("ATZ", 3500)
        .then(function () { return leg.cere("ATE0"); })
        .then(function () { return leg.cere("ATL0"); })
        .then(function () { return leg.cere("ATS1"); })
        .then(function () { return leg.cere("ATH1"); })
        .then(function () { return leg.cere("ATSP7", 2500); })
        .then(function () { return leg.cere("ATCM 00000000", 900); })
        .then(function () { return leg.cere("ATCF 00000000", 900); });
    },

    citeste: function (tinteAlese) {
      if (!leg || !leg.cere) return Promise.reject(new Error("Diagnoza nu e legată"));
      var lista = tinteAlese || TINTE;
      var raport = { cand: new Date().toISOString(), module: [], total: 0 };
      jos("diagnoză: întreb " + lista.length + " module · ascultarea stă pe loc");

      var lant = leg.cere("ATZ", 3500)
        .then(function () { return leg.cere("ATE0"); })
        .then(function () { return leg.cere("ATL0"); })
        .then(function () { return leg.cere("ATS1"); })
        .then(function () { return leg.cere("ATH1"); })
        .then(function () { return leg.cere("ATCAF1"); });

      lista.forEach(function (t) {
        lant = lant.then(function () {
          return unModul(t).then(function (r) {
            raport.module.push(r);
            raport.total += r.coduri.length;
            if (r.raspunde) {
              jos("  " + r.adresa + " (" + r.nume + "): " +
                  (r.coduri.length ? r.coduri.length + " cod(uri)" : "niciun cod stocat") +
                  (r.note.length ? " · " + r.note.join(" · ") : ""));
            } else {
              jos("  " + r.adresa + " (" + r.nume + "): tăcere");
            }
          });
        });
      });

      return lant
        .then(function () { return Diagnoza.inapoiLaAscultare(); })
        .then(function () {
          jos("diagnoză terminată · " + raport.total + " cod(uri) în total · adaptorul e înapoi pe ascultare");
          return raport;
        })
        .catch(function (e) {
          jos("diagnoza a eșuat: " + (e && e.message ? e.message : e));
          return Diagnoza.inapoiLaAscultare().then(function () { throw e; });
        });
    },

    /* Raportul, ca text de citit sau de trimis mai departe. */
    text: function (raport) {
      var l = ["DIAGNOZĂ " + raport.cand, ""];
      raport.module.forEach(function (m) {
        if (!m.raspunde && !m.coduri.length) return;
        l.push(m.adresa + " · " + m.nume);
        if (!m.coduri.length) l.push("   niciun cod stocat");
        m.coduri.forEach(function (c) {
          l.push("   " + (c.text || c.brut) + "  [" + c.stareText + "]");
          if (c.potrivire) {
            c.potrivire.forEach(function (p) {
              l.push("      posibil " + p.cod + " nivel " + p.nivel + ": " + p.nume +
                     (p.prag ? "  (" + p.prag + ")" : ""));
            });
          }
        });
        m.note.forEach(function (n) { l.push("   nota: " + n); });
        l.push("");
      });
      if (!raport.total) l.push("Niciun cod stocat în modulele care au răspuns.");
      else l.push("Potrivirile cu lista de fabrică sunt propuneri, nu certitudini: " +
                  "încă nu știm în ce fel împachetează modulele codul de bord.");
      return l.join("\n");
    },

    tinte: TINTE
  };

  global.Diagnoza = Diagnoza;
})(window);
