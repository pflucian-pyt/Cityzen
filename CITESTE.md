# BAW L7e — aplicația pentru unitatea din bord

APK pentru unitatea din bord a mașinii. Aceeași aplicație pe care ai rulat-o în
Chrome, împachetată ca aplicație Android, ca să se lege singură la adaptor și
să meargă fără telefon.

## Cum scoți APK-ul

Pui folderul într-un depozit GitHub nou și îl împingi. Actions îl compilează
singur — același fișier de lucru ca la Avatr. Iei APK-ul de la **Actions →
ultima rulare → Artifacts → BawMonitor-APK**.

Ia varianta `release`. Cea `debug` se instalează alături, cu alt nume, dacă
vrei să le ai pe amândouă.

## Cum îl pui pe unitate

Copiezi APK-ul pe un stick, îl bagi în unitate, îl deschizi din managerul de
fișiere. Developer options e deja pornit, deci instalarea din surse
necunoscute merge.

La prima pornire cere trei permisiuni: Bluetooth, localizare și notificări.
**Dă-le pe toate.** Pe Android 11, căutarea Bluetooth cere localizare chiar
dacă nu ne interesează unde suntem — fără ea scanarea întoarce zero dispozitive
și pare defect de Bluetooth, nu de permisiune.

## Înainte de orice: închide Car Scanner

Adaptorul acceptă o singură legătură. Dacă o ține altă aplicație — de pe
unitate sau de pe telefon — aplicația asta nu poate intra.

## Ce e altfel față de Avatr Monitor

**Fără Android Auto.** Acolo aplicația rula pe telefon și proiecta pe ecranul
mașinii. Aici rulează chiar pe ecranul mașinii, deci tot lanțul acela pică —
și cu el dependența de `androidx.car.app`, partea cea mai fragilă a compilării.
Au rămas patru fișiere Kotlin în loc de treisprezece.

**Fără trimitere pe server.** Etapa asta e de test: vedem ce funcționează.
Serverul, conturile și împerecherea vin după, din documentul de arhitectură.

## Cele trei file

**Bord** — consumul instantaneu ca bare, pe trei zone de timp: cursele
anterioare în stânga, cursa curentă la mijloc, ultimele cinci minute în
dreapta. Deasupra, mediile; în dreapta, puterea și energia.

**Celule** — toate cele 38 de tensiuni, cu barele pornind de la cea mai slabă
celulă, nu de la zero. De la zero, toate ar fi egale: pachetul stă între 3,24
și 3,27 V, adică sub un procent din înălțimea barei.

**Setări** — adaptorul, capacitatea bateriei, harta semnalelor și modul
culegere.

Harta semnalelor e deja în aplicație, deci la prima pornire vezi cifre, nu
liniuțe. Dacă ceva iese aiurea, o corectezi din setări — nu trebuie
reconstruit APK-ul.

## Ce verificăm, în ordine

1. **Pornește aplicația?** Dacă nu, în Descărcări apare `baw_erori.txt`.
2. **Vede adaptorul?** Apeși Conectează în setări, ar trebui să apară OBDLink CX.
3. **Se leagă?** Starea din antet trece pe „conectat · OBDLink CX".
4. **Vin cadrele?** Pe fila celule ar trebui să apară cele 38 de bare în
   câteva secunde. Dacă apar, magistrala se aude.
5. **Sunt cifrele corecte?** Compară tensiunea, procentul și viteza cu bordul
   de fabrică. Dacă nu se potrivesc, harta trebuie corectată.

Dacă pică la pasul 2 sau 3, aia e informația care contează — înseamnă că
Bluetooth-ul unității nu merge cu adaptorul, iar toată arhitectura cu unitatea
ca element central trebuie regândită.

Pentru descoperit semnale noi: pornește **modul culegere** din setări, condu,
apoi **Descarcă înregistrarea**. Fișierul are toate cadrele brute.

Dacă pică la pasul 2 sau 3, aia e informația care contează — înseamnă că
Bluetooth-ul unității nu merge cu adaptorul, iar toată arhitectura cu unitatea
ca element central trebuie regândită.

Apasă **Descarcă fișierul** la final și trimite-l, orice s-ar întâmpla. Are
jurnalul complet cu tot ce a încercat.

## Semnătura

`app/cheia-mea.jks` e aceeași cheie ca la Avatr. Fără ea, fiecare compilare pe
GitHub ar folosi altă cheie de test, Android ar refuza să pună versiunea nouă
peste cea veche, iar dezinstalarea ar șterge `localStorage` — adică inventarul
și istoricul adunate în mașină.
