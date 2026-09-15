/*
 * punte.js — imitatie de Web Bluetooth peste Bluetooth-ul nativ Android.
 *
 * De ce exista fisierul acesta: WebView-ul de pe Android NU are Web Bluetooth.
 * Interfata Avatr Monitor atinge Bluetooth-ul in exact opt locuri, toate prin
 * "navigator.bluetooth". Aici reconstruim doar acele opt lucruri, sprijinite pe
 * Kotlin. Rezultatul: index.html ramane neschimbat, byte cu byte, deci tot ce a
 * fost testat in masina ramane valabil.
 *
 * Se incarca INAINTE de scriptul paginii. Nu se atinge de nimic altceva.
 */
(function () {
  "use strict";

  if (!window.Punte) {
    console.warn("punte.js: interfata Kotlin lipseste, las Web Bluetooth cum e");
    return;
  }

  var N = window.Punte;          // obiectul injectat din Kotlin
  var urmatorId = 1;
  var inAsteptare = {};          // idCerere -> {rezolva, respinge}

  function cerere(pornire) {
    return new Promise(function (rezolva, respinge) {
      var id = urmatorId++;
      inAsteptare[id] = { rezolva: rezolva, respinge: respinge };
      try {
        pornire(id);
      } catch (e) {
        delete inAsteptare[id];
        respinge(e);
      }
    });
  }

  // ---------- ce apeleaza Kotlin la noi ----------

  var punteInterna = {
    ok: function (id, text) {
      var c = inAsteptare[id];
      if (!c) return;
      delete inAsteptare[id];
      var d = null;
      if (text) { try { d = JSON.parse(text); } catch (e) { d = text; } }
      c.rezolva(d);
    },
    eroare: function (id, mesaj) {
      var c = inAsteptare[id];
      if (!c) return;
      delete inAsteptare[id];
      c.respinge(new Error(mesaj || "eroare necunoscuta"));
    },
    notificare: function (uuid, base64) {
      var c = caracteristici[uuid];
      if (!c) return;
      c._trimite(base64);
    },
    deconectat: function () {
      conectat = false;
      if (dispozitiv) dispozitiv._emite("gattserverdisconnected");
    }
  };
  window.__punte = punteInterna;

  // ---------- ascultatori de evenimente, minim ----------

  function CuEvenimente() { this._asc = {}; }
  CuEvenimente.prototype.addEventListener = function (nume, fn) {
    (this._asc[nume] = this._asc[nume] || []).push(fn);
  };
  CuEvenimente.prototype.removeEventListener = function (nume, fn) {
    var l = this._asc[nume]; if (!l) return;
    var i = l.indexOf(fn); if (i >= 0) l.splice(i, 1);
  };
  CuEvenimente.prototype._emite = function (nume, ev) {
    var l = this._asc[nume]; if (!l) return;
    ev = ev || {};
    ev.type = nume;
    if (!ev.target) ev.target = this;
    for (var i = 0; i < l.length; i++) {
      try { l[i].call(this, ev); } catch (e) { console.error(e); }
    }
  };

  // ---------- caracteristica ----------

  var caracteristici = {};   // uuid -> Caracteristica
  var conectat = false;
  var dispozitiv = null;
  var serviciiCerute = [];   // ce a cerut pagina prin optionalServices/filters
  var seConecteaza = false;  // o incercare e in desfasurare chiar acum

  // Servicii pe care Web Bluetooth nu le arata NICIODATA unei pagini, oricat ar
  // cere: accesul generic si atributul generic. Chrome le tine ascunse prin
  // lista lui de interdictii. Le scoatem si noi mereu, nu doar cand stim
  // filtrul paginii — altfel, pe calea de reconectare automata (unde
  // requestDevice nu se mai apeleaza, deci filtrul e gol) serviciul 1800 ar
  // reveni in fata cu "Device Name" scriibila, si comenzile OBD ar pleca iar in gol.
  var interzise = [
    "00001800-0000-1000-8000-00805f9b34fb",
    "00001801-0000-1000-8000-00805f9b34fb"
  ];

  function base64Bytes(s) {
    var brut = atob(s);
    var b = new Uint8Array(brut.length);
    for (var i = 0; i < brut.length; i++) b[i] = brut.charCodeAt(i);
    return b;
  }

  function bytesBase64(u8) {
    var s = "";
    for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
    return btoa(s);
  }

  /**
   * Aceeasi caracteristica trebuie sa fie mereu ACELASI obiect.
   *
   * Chrome intoarce mereu aceeasi instanta pentru un uuid; eu faceam obiecte
   * noi la fiecare descoperire. Iar pagina, la o reconectare, isi pastreaza
   * referintele vechi (le pune doar daca sunt goale). Asa, notificarile ajungeau
   * la obiectul nou, unde nu ascultase nimeni, iar pagina nu mai primea nimic —
   * exact tacerea de saizeci de secunde de pe drumul din 7 septembrie, cu ATZ,
   * ATE0 si toata initializarea expirand una dupa alta.
   */
  var registru = {};

  function obtineCaracteristica(d) {
    var veche = registru[d.uuid];
    if (veche) {
      // aceeasi instanta, cu proprietatile improspatate; ascultatorii rămân
      veche.properties = {
        read: !!d.read, write: !!d.write,
        writeWithoutResponse: !!d.writeNoResp,
        notify: !!d.notify, indicate: !!d.indicate
      };
      caracteristici[d.uuid] = veche;
      return veche;
    }
    return new Caracteristica(d);
  }

  function Caracteristica(d) {
    CuEvenimente.call(this);
    this.uuid = d.uuid;
    this.properties = {
      read: !!d.read,
      write: !!d.write,
      writeWithoutResponse: !!d.writeNoResp,
      notify: !!d.notify,
      indicate: !!d.indicate
    };
    this.value = null;
    caracteristici[this.uuid] = this;
    registru[this.uuid] = this;
  }
  Caracteristica.prototype = Object.create(CuEvenimente.prototype);

  Caracteristica.prototype._trimite = function (base64) {
    var b = base64Bytes(base64);
    // pagina citeste ev.target.value ca DataView: .byteLength si .getUint8()
    this.value = new DataView(b.buffer);
    this._emite("characteristicvaluechanged", { target: this });
  };

  Caracteristica.prototype.startNotifications = function () {
    var self = this;
    return cerere(function (id) { N.porneste_notificari(id, self.uuid); })
      .then(function () { return self; });
  };

  Caracteristica.prototype.stopNotifications = function () {
    var self = this;
    return cerere(function (id) { N.opreste_notificari(id, self.uuid); })
      .then(function () { return self; });
  };

  function scrieBytes(self, date, cuRaspuns) {
    // insemnam clipa: partea Kotlin se uita la ea ca sa vada daca bucla
    // paginii mai bate, sau a fost incetinita de ecranul stins
    try { window.__ultimaComanda = Date.now(); } catch (e) { }
    var u8 = date instanceof Uint8Array ? date
           : date && date.buffer ? new Uint8Array(date.buffer)
           : new Uint8Array(date);
    var b64 = bytesBase64(u8);
    return cerere(function (id) { N.scrie(id, self.uuid, b64, cuRaspuns); });
  }

  Caracteristica.prototype.writeValue = function (d) { return scrieBytes(this, d, true); };
  Caracteristica.prototype.writeValueWithResponse = function (d) { return scrieBytes(this, d, true); };
  Caracteristica.prototype.writeValueWithoutResponse = function (d) { return scrieBytes(this, d, false); };

  // ---------- serviciu ----------

  function Serviciu(d) {
    this.uuid = d.uuid;
    this.isPrimary = true;
    this._c = (d.caracteristici || []).map(function (x) { return obtineCaracteristica(x); });
  }
  Serviciu.prototype.getCharacteristics = function () {
    var c = this._c;
    return Promise.resolve(c);
  };
  Serviciu.prototype.getCharacteristic = function (uuid) {
    var g = caracteristici[String(uuid).toLowerCase()];
    return g ? Promise.resolve(g) : Promise.reject(new Error("caracteristica lipseste"));
  };

  // ---------- serverul GATT ----------

  function Server(disp) {
    this.device = disp;
    this._servicii = null;
  }
  Object.defineProperty(Server.prototype, "connected", {
    get: function () { return conectat; }
  });
  Server.prototype.connect = function () {
    var self = this;
    var adresa = self.device.id || "";

    // Asa cere standardul: connect() pe un dispozitiv deja legat se rezolva pe
    // loc, fara sa atinga nimic. Eu taiam legatura nativa si o refaceam de
    // fiecare data — iar pagina isi porneste reconectarea din 30 in 30 de
    // secunde, chiar cand totul merge.
    //
    // Urmarea, vazuta pe drumul din 7 septembrie: la 18:54:16 raspunsuri de
    // 90 ms, la 18:54:19 pagina reconecteaza, si de acolo ATZ, ATE0, ATL0 —
    // toata initializarea — expira una dupa alta timp de SAIZECI de secunde.
    // Un adaptor BLE inchis si redeschis pe loc rămâne mut o vreme. Deci
    // reconectarea de prisos era chiar cauza tacerilor.
    if (conectat && dispozitiv && dispozitiv.id === self.device.id) {
      console.log("punte.js: sunt deja legat, las legatura in pace");
      dispozitiv = self.device;
      return Promise.resolve(self);
    }
    seConecteaza = true;
    return cerere(function (id) { N.conecteaza(id, adresa); }).then(function () {
      conectat = true;
      seConecteaza = false;
      // dispozitivul activ poate veni din getDevices, nu doar din requestDevice
      dispozitiv = self.device;
      self._servicii = null;
      // NU golim aici lista de caracteristici. Pagina isi porneste uneori
      // reconectarea desi legatura merge. Daca o golim atunci, scrierile de
      // dupa dau "caracteristica lipseste", cinci la rand, si pagina declara
      // cadere — o legatura buna omorata de o reconectare de prisos.
      // Lista veche rămâne bună până cand descoperirea noua o inlocuieste.
      return self;
    }).catch(function (e) { seConecteaza = false; throw e; });
  };
  Server.prototype.disconnect = function () {
    conectat = false;
    N.deconecteaza();
  };
  Server.prototype.getPrimaryServices = function () {
    var self = this;
    if (self._servicii) return Promise.resolve(self._servicii);
    return cerere(function (id) { N.descopera_servicii(id); }).then(function (lista) {
      var tot = lista || [];
      tot.forEach(function (s) { console.log("serviciu vazut: " + s.uuid); });

      // Aici era greseala care facea aplicatia sa se conecteze si sa nu citeasca
      // nimic. Web Bluetooth nu arata paginii decat serviciile cerute prin
      // "optionalServices". Android insa raporteaza tot, inclusiv serviciile
      // standard 1800, 1801 si 180a, iar 1800 vine de obicei primul si are
      // caracteristica "Device Name", care pe multe adaptoare se poate scrie.
      // Pagina lua prima caracteristica scriibila din lista, deci comenzile OBD
      // plecau spre numele dispozitivului: scrise cu succes, fara nici un
      // raspuns. Filtram exact cum ar filtra Chrome.
      var pastrate = tot.filter(function (s) {
        var scos = interzise.indexOf(s.uuid) >= 0;
        if (scos) console.log("sar peste serviciul standard " + s.uuid);
        return !scos;
      });
      tot = pastrate;

      if (serviciiCerute.length) {
        var doarCerute = tot.filter(function (s) {
          return serviciiCerute.indexOf(s.uuid) >= 0;
        });
        if (doarCerute.length) {
          pastrate = doarCerute;
          console.log("pastrez " + doarCerute.length + " din " + tot.length +
                      " servicii, dupa filtrul paginii");
        } else {
          // niciunul dintre cele cerute nu exista: mai bine tot decat nimic,
          // dar spunem limpede, ca sa se vada in jurnal
          console.warn("niciun serviciu cerut nu a fost gasit; las lista intreaga");
        }
      }

      // schimbul se face dintr-o singura mișcare: pana in clipa asta,
      // scrierile merg cu lista veche
      var noua = {};
      var inainte = caracteristici;
      caracteristici = noua;
      self._servicii = pastrate.map(function (s) { return new Serviciu(s); });
      if (!Object.keys(noua).length) caracteristici = inainte;
      return self._servicii;
    });
  };
  Server.prototype.getPrimaryService = function (uuid) {
    return this.getPrimaryServices().then(function (sv) {
      var t = String(uuid).toLowerCase();
      for (var i = 0; i < sv.length; i++) if (sv[i].uuid === t) return sv[i];
      throw new Error("serviciul " + uuid + " lipseste");
    });
  };

  // ---------- dispozitivul ----------

  function Dispozitiv(d) {
    CuEvenimente.call(this);
    this.name = d.nume || null;
    this.id = d.id || null;
    this.gatt = new Server(this);
  }
  Dispozitiv.prototype = Object.create(CuEvenimente.prototype);

  // ---------- navigator.bluetooth ----------

  navigator.bluetooth = {
    getAvailability: function () { return Promise.resolve(true); },
    requestDevice: function (opt) {
      // Alegerea dispozitivului o face omul, dintr-o lista afisata de Kotlin,
      // deci "acceptAllDevices" si "filters" nu ne spun nimic acolo. Dar lista
      // de servicii cerute conteaza mult mai tarziu, la descoperire: pagina se
      // sprijina pe ea ca sa nu vada serviciile standard ale dispozitivului.
      serviciiCerute = [];
      var adauga = function (u) {
        if (typeof u === "string") serviciiCerute.push(u.toLowerCase());
      };
      if (opt) {
        (opt.optionalServices || []).forEach(adauga);
        (opt.filters || []).forEach(function (f) { (f.services || []).forEach(adauga); });
      }
      if (serviciiCerute.length)
        console.log("servicii cerute de pagina: " + serviciiCerute.join(", "));

      seConecteaza = true;
      return cerere(function (id) { N.cere_dispozitiv(id); }).then(function (d) {
        // vezi mai jos: lista de caracteristici nu se goleste pe speranta
        // NU stingem "conectat" aici. Pagina isi verifica singura legatura, in
        // bucla ei, cu gatt.connected. Daca o stingem cand incepe o cerere
        // noua, bucla vede fals si declara "GATT inchis" — desi nimic nu s-a
        // rupt. Pe drumul din 7 septembrie asa au ieșit 19 caderi false din 25.
        dispozitiv = new Dispozitiv(d);
        return dispozitiv;
      }).catch(function (e) { seConecteaza = false; throw e; });
    },
    getDevices: function () {
      // Pagina se foloseste de asta ca sa se relege singura la pornire, fara
      // buton. In Chrome lista vine din dispozitivele carora li s-a dat voie
      // odata; aici vine din cele imperecheate in telefon plus ultimul folosit.
      if (!N.dispozitive_cunoscute) {
        return Promise.resolve(dispozitiv ? [dispozitiv] : []);
      }
      return cerere(function (id) { N.dispozitive_cunoscute(id); })
        .then(function (lista) {
          return (lista || []).map(function (d) { return new Dispozitiv(d); });
        })
        .catch(function () { return dispozitiv ? [dispozitiv] : []; });
    },
    addEventListener: function () {},
    removeEventListener: function () {}
  };

  // ---------- descarcarea fisierelor ----------
  //
  // Pagina exporta sesiunea asa: face un blob, creeaza o legatura invizibila cu
  // atributul "download" si o apasa. Intr-un browser iese un fisier in
  // Descarcari. In WebView nu se intampla nimic, si nici o eroare nu apare —
  // exact felul de defect care se observa abia cand ai nevoie de date.
  //
  // Prindem apasarea, citim blobul si dam octetii la Kotlin, care scrie
  // fisierul adevarat. Pagina nu se schimba cu nimic.

  if (window.Fisiere) {
    var apasareOriginala = HTMLAnchorElement.prototype.click;

    HTMLAnchorElement.prototype.click = function () {
      var nume = this.getAttribute("download");
      var adresa = this.getAttribute("href") || "";
      var alBlobului = adresa.indexOf("blob:") === 0 || adresa.indexOf("data:") === 0;

      if (!nume || !alBlobului) {
        return apasareOriginala.apply(this, arguments);
      }

      fetch(adresa)
        .then(function (r) { return r.blob(); })
        .then(function (b) {
          return new Promise(function (rezolva, respinge) {
            var c = new FileReader();
            c.onload = function () {
              // rezultatul e "data:tip;base64,XXXX" — luam doar partea de dupa virgula
              var s = String(c.result);
              rezolva({ b64: s.slice(s.indexOf(",") + 1), tip: b.type || "" });
            };
            c.onerror = function () { respinge(new Error("nu pot citi blobul")); };
            c.readAsDataURL(b);
          });
        })
        .then(function (d) { window.Fisiere.salveaza(nume, d.b64, d.tip); })
        .catch(function (e) {
          console.error("descarcare esuata: " + e.message);
          // lasam browserul sa incerce oricum, ca sa nu pierdem exportul
          try { apasareOriginala.call(this); } catch (x) {}
        }.bind(this));
    };
  }


  // ---------- reconectare automata ----------
  //
  // Pagina, cand vede prea multe comenzi fara raspuns, cheama cazut(): opreste
  // bucla, scrie "deconectat" si pune butonul pe "Reconecteaza". De acolo
  // asteapta o apasare de om. In masina, in mers, exact ce nu ai cum sa faci.
  //
  // Aici pandim butonul si il apasam noi. Se foloseste chiar calea de
  // reconectare a paginii, cea deja incercata, nu una noua. Iar alegerea
  // adaptorului nu deranjeaza cu nicio lista, fiindca partea de Kotlin ia
  // automat adaptorul dovedit bun.
  //
  // Pauzele cresc: 3, 6, 12, 24, apoi din 30 in 30 de secunde. Asa nu se
  // insista degeaba cand masina e stinsa si adaptorul doarme, dar nici nu se
  // pierde vremea cand e doar o incetinire trecatoare.

  function pandesteButonul() {
    if (typeof document === "undefined" || !document.getElementById) return;
    var buton = document.getElementById("btnConect");
    if (!buton) return;

    // Prima pauza era de 3 secunde, iar pagina are nevoie de vreo zece ca sa
    // treaca prin ATZ si initializare. Apasarea sosea in mijlocul conectarii,
    // pagina declara cadere, iar pandarul apasa iar: o cadere adevarata
    // devenea un sir. Acum incepem de la opt secunde.
    var pauze = [8000, 15000, 30000, 60000, 120000];
    var MAX_INCERCARI = 12;      // dupa atat, adaptorul are altceva
    var treapta = 0;
    var incercari = 0;
    var programat = null;

    function cazuta() {
      return buton.textContent.indexOf("Reconect") >= 0 && !buton.disabled;
    }

    function incearca() {
      programat = null;
      if (!cazuta()) { treapta = 0; incercari = 0; return; }
      // cat timp o conectare e in desfasurare, nu ne bagam peste ea
      if (seConecteaza) { programeaza(); return; }
      if (incercari >= MAX_INCERCARI) {
        console.warn("punte.js: " + incercari + " reconectari fara izbanda, " +
                     "ma opresc — apasa tu butonul");
        return;
      }
      incercari++;
      // ritm rapid inainte: daca legatura doar incetinise, poate fi de ajuns
      try { if (N.revigoreaza) N.revigoreaza(); } catch (e) { }
      console.log("reconectare automata, incercarea " + (treapta + 1));
      // Lasam urma in jurnalul paginii. Fara ea, in fisierul JSON nu se poate
      // deosebi o apasare a noastra de reconectarea pornita de pagina, si am
      // pierdut o rundă intreaga cautand cine cheama ce.
      try {
        var j = document.getElementById("jurnal");
        if (j) j.textContent += "\n[punte] apas Reconectează, încercarea " +
                                (treapta + 1) + "\n";
      } catch (e) { }
      buton.click();
      if (treapta < pauze.length - 1) treapta++;
      programeaza();
    }

    function programeaza() {
      if (programat) return;
      programat = setTimeout(incearca, pauze[treapta]);
    }

    // butonul isi schimba textul cand pagina declara caderea
    new MutationObserver(function () {
      if (cazuta()) programeaza();
    }).observe(buton, { childList: true, characterData: true, subtree: true,
                        attributes: true, attributeFilter: ["disabled"] });

    // plasa de siguranta: daca observatorul scapa schimbarea, o vedem oricum
    setInterval(function () { if (cazuta()) programeaza(); }, 5000);

    console.log("punte.js: reconectarea automata e pornita");
  }

  // in WebView exista mereu; garda e pentru bancurile de proba, care ruleaza
  // fisierul fara pagina in jur
  if (typeof document !== "undefined") {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", pandesteButonul);
    } else {
      pandesteButonul();
    }
  }

  console.log("punte.js: Web Bluetooth imitat peste Bluetooth-ul Android");
})();
