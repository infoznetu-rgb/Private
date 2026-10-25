# Preplatok alebo nedoplatok

Slovenská natívna Android aplikácia na lokálne sledovanie elektriny, vody a plynu. Bez účtu, servera, reklám a platených API. Android 8.0+ (API 26).

## Verzia 1.1 — jednoduché fotografovanie

Úvod obsahuje Elektrinu, Vodu, Plyn a veľké tlačidlo **Odfotiť merač**. Kamera CameraX má rámček, zaostrenie klepnutím a priebežné orientačné upozornenia na neostrosť a silné svetlé miesta. Galéria aj ručné zadanie sú dostupné jedným klepnutím. Po spracovaní celej fotografie sa ukáže veľké číslo a tlačidlá **Potvrdiť** a **Opraviť**. Ručný výrez je nepovinná pomocná možnosť.

V časti Ceny a zálohy je fotografovanie alebo výber **fotografie faktúry**. Nepodporuje sa PDF import. Lokálne OCR vyhľadáva označené jednotkové ceny, zálohy a obdobie. Viac cien zostáva výberom, nie automatickým súčtom; DPH ani distribúcia sa neodhadujú. Používateľ musí údaje skontrolovať a osobitne potvrdiť. Celková suma faktúry sa nepovažuje za cenu energie. Platba sa importom faktúry nevytvára. Pri plyne sa rozlišuje cena za kWh a m³, ceny za MWh sa explicitne prepočítajú na kWh.

Pokročilé nastavenia, spätné platby a JSON záloha sú pod **Ďalšie možnosti**. Graf je v Histórii. SQLite databáza sa migruje z verzie 1 na 2 pridaním konca obdobia a jednotky ceny; odpočty, platby a ceny zostávajú zachované. Staré JSON zálohy sa naďalej dajú obnoviť. Neinštalujte aktualizáciu odinštalovaním starej aplikácie; pri rozdielnom podpisovom kľúči najprv exportujte zálohu.

## Zostavenie APK

Otvorte tento priečinok v Android Studio, nainštalujte Android SDK 36 a použite JDK 17. Gradle Wrapper je súčasťou projektu.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Na Linuxe/macOS použite `chmod +x gradlew` a `./gradlew`. APK nájdete v `app/build/outputs/apk/debug/app-debug.apk`. Debug APK je podpísané vývojovým kľúčom a možno ho inštalovať na vlastný telefón. Pre publikovanie vytvorte vlastný release kľúč v Android Studio cez Generate Signed Bundle / APK; kľúč neukladajte do repozitára. Projekt neobsahuje podpisový kľúč pre distribúciu.

GitHub Actions workflow zostaví testovacie APK a spustí jednotkové testy a lint. Druhý job spustí Android testy s hardvérovou akceleráciou na emulátore API 29: databáza a migrácia, syntetické OCR vzory, potvrdenie a oprava v UI, faktúra a reálna snímka z emulovaného fotoaparátu. Výsledky a snímka úvodu sú uložené ako artefakty. Manuálne spustenie: Actions → Android APK → Run workflow. Náklady GitHub Actions závisia od vášho plánu GitHub; aplikácia žiadnu cloudovú službu nevyžaduje.

## Použitie

1. V Nastaveniach vyberte energiu, konečnú jednotkovú cenu vrátane DPH a distribúcie, stály mesačný poplatok, plán záloh a začiatok obdobia. Voda má cenu za m³ (napr. vodné + stočné). Plyn má cenu za kWh a používateľom zadaný faktor kWh/m³ z vyúčtovania. Predvolený faktor je 1 a treba ho nastaviť podľa spôsobu fakturácie.
2. Pridajte prvý kumulatívny stav k začiatku obdobia a aspoň jeden ďalší odpočet. Výber dátumu umožňuje spätné odpočty. Stav nesmie klesať a pre jednu energiu je povolený jeden odpočet denne.
3. Zapisujte skutočne zaplatené zálohy. Plán záloh nie je automatická platba.
4. Odfotografujte celý merač. Skontrolujte veľké číslo i desatinnú čiarku a stlačte Potvrdiť, alebo Opraviť. Pri zlyhaní sa číslo nehádže do poľa odhadom: zostane ručné zadanie alebo nová snímka. Nepovinný výrez pomáha pri viacerých displejoch.
5. Pred odinštalovaním či obnovou údajov exportujte zálohu do súboru JSON.

## Výpočet a hranice presnosti

- Spotreba = posledný stav − prvý stav v zvolenom období. Pri chýbajúcom odpočte k začiatku obdobia aplikácia výslovne uvádza kratší interval.
- Náklady = spotreba × cena × prevodný faktor + stály poplatok pomerne podľa skutočných dní v kalendárnych mesiacoch. Faktor elektriny a vody je 1; pri cene plynu za m³ sa prevod na kWh nepoužíva. Voliteľný koniec obdobia ohraničí odpočty zahrnuté vo výpočte.
- Interval nákladov aj zaplatených záloh je **[prvý deň odpočtu, posledný deň odpočtu)**. Platba v posledný deň patrí do nasledujúceho intervalu. Pri oboch dátumoch je to v rozhraní vysvetlené.
- Plán záloh počíta mesačné platby splatné prvého dňa mesiaca v tom istom intervale. Nemá vplyv na skutočný zostatok.
- Zostatok = zapísané platby v intervale − náklady. Kladný výsledok je priebežný preplatok, záporný nedoplatok. Nejde o predikciu ročného vyúčtovania.
- Počítanie peňazí používa BigDecimal; zaokrúhlenie nákladov na centy HALF_UP. Graf zobrazuje priemernú dennú spotrebu medzi odpočtami, najviac 12 posledných intervalov.
- Jedna sadzba na energiu pre sledované obdobie. Zmena sadzby prepočíta celé obdobie. Nie sú implementované viacpásmové tarify, viac meračov rovnakej energie, automatické sadzby dodávateľov ani výmena/pretočenie merača. Pri zmene sadzby začnite nové obdobie s príslušným odpočtom. Pri výmene merača exportujte zálohu a vytvorte novú evidenciu (obnovou prázdnej vlastnej zálohy alebo vymazaním údajov aplikácie).

## OCR a súkromie

ML Kit Text Recognition 16.0.1 má latinský model pribalený do APK, dostupný aj pri prvom spustení bez internetu. Nepoužíva sa dynamický model ani vzdialené API. Aplikácia z manifestu odstraňuje oprávnenia INTERNET a ACCESS_NETWORK_STATE.

Spracovanie používa celú fotografiu, zvýšený kontrast, automatické oblasti z ohraničenia OCR aj nezávislé skupiny tmavých komponentov/obdĺžnikových displejov, pôvodné a čiernobiele výrezy. Varianty sa vyhodnocujú postupne, bez internetu. Rovnaké číselné návrhy sa zoskupujú; počet variantov nie je percento istoty. Jednotky m³/kWh/MWh sa kontrolujú proti energii. Výslovne rozpoznané registre 1.8.1, 1.8.2 a 2.8.x sa nepotvrdzujú ako celkový odber 1.8.0. Výrobné čísla sa odmietajú podľa kontextu. Neznáma jednotka či viac rôznych čísel vyžaduje kontrolu.

Pre sedemsegmentové číslice existuje aj konzervatívny geometrický dekóder. Prijíma iba presný vzor so zreteľne zapnutými alebo vypnutými segmentmi; nevyberá najbližšiu číslicu a pri neistých/úzkych/neúplných znakoch vracia zlyhanie. Desatinnú čiarku nevkladá podľa očakávanej spotreby ani tarify. Ručný výrez je pomocná možnosť, nie povinný krok. OCR nikdy samo neukladá odpočet; posledné potvrdenie robí používateľ.

Sedemsegmentové LCD, mechanické valčeky, odlesky a červené desatinné číslice sú náročné. V repozitári neboli reálne fotografie meračov/faktúr. Android testy používajú výslovne syntetické bitmapy; z nich nemožno uvádzať úspešnosť na reálnych meračoch. Fyzické telefóny a reálne snímky treba overiť osobitne. Upozornenie na neostrosť/odlesk je heuristika, nie záruka kvality. CameraX potrebuje povolenie fotoaparátu; odmietnutie ponechá galériu a ručné zadanie. Galéria je systémový výber bez plošného prístupu k fotografiám. Veľké obrázky sa zmenšia, EXIF orientácia sa opraví. Fotografie sú iba v pamäti/privátnej cache, nie v databáze alebo zálohe; staré fotky z kamery sa čistia pri ďalšom fotografovaní po 24 hodinách.

SQLite databáza je v súkromnom úložisku aplikácie. Systémové cloudové zálohovanie je vypnuté. Manuálny JSON export obsahuje všetky odpočty, platby a ceny bez fotografií, bez šifrovania. Umiestnenie vyberá používateľ (aj jeho cloudový poskytovateľ dokumentov, ak ho zvolí). Import najprv validuje formát, čísla, chronológiu a duplicity, potom po potvrdení používateľa nahradí databázu v transakcii.

## Architektúra

- Kotlin 2.1.20, Jetpack Compose Material 3, Android Gradle Plugin 8.11.1, Gradle 8.13, JDK 17.
- `Domain.kt`: dátový model, prísne číselné parsovanie a finančné výpočty.
- `Store.kt`: lokálna SQLite databáza a validovaný JSON export/import.
- `AppModel.kt`: stav obrazoviek, operácie mimo UI vlákna.
- `Ocr.kt`: dekódovanie, EXIF, lokálne rozpoznávanie a výrez.
- `ImageAnalysis.kt`: heuristika kvality, kontrast, prahovanie, automatické oblasti a segmenty.
- `RecognitionRules.kt`: prísne pravidlá číslic/jednotiek/registrov a parser faktúry.
- `SimpleApp.kt`, `CameraScreen.kt`: jednoduchý úvod, vedená kamera a výslovné potvrdenie odpočtu/faktúry.
- `MainActivity.kt`: téma, história/grafy a pokročilé možnosti.
- `app/src/test`: jednotkové testy výpočtov, hraníc intervalov a číselného parsovania.
- `app/src/androidTest`: integračný test databázy a obnovy na Android zariadení. Spustenie: `./gradlew connectedDebugAndroidTest`.

## Kontrola pred distribúciou

Na reálnom telefóne overte kameru, galériu s otočenými/veľkými obrázkami, prvé OCR v režime lietadlo, ručný výrez, obnovu zálohy, veľké systémové písmo a rôzne typy meračov. Automatické testy nepotvrdzujú presnosť OCR na vašom merači. Release podpísanie a publikovanie v Google Play sú samostatné kroky.

Technické zdroje: [ML Kit Android OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes).
