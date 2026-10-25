# Preplatok alebo nedoplatok

Slovenská natívna Android aplikácia na lokálne sledovanie elektriny, vody a plynu. Bez účtu, servera, reklám a platených API. Android 8.0+ (API 26).

## Zostavenie APK

Otvorte tento priečinok v Android Studio, nainštalujte Android SDK 36 a použite JDK 17. Gradle Wrapper je súčasťou projektu.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Na Linuxe/macOS použite `chmod +x gradlew` a `./gradlew`. APK nájdete v `app/build/outputs/apk/debug/app-debug.apk`. Debug APK je podpísané vývojovým kľúčom a možno ho inštalovať na vlastný telefón. Pre publikovanie vytvorte vlastný release kľúč v Android Studio cez Generate Signed Bundle / APK; kľúč neukladajte do repozitára. Projekt neobsahuje podpisový kľúč pre distribúciu.

GitHub Actions workflow zostaví APK a spustí jednotkové testy a lint. Manuálne ho spustíte cez Actions → Android APK → Run workflow. Náklady GitHub Actions závisia od vášho plánu GitHub; aplikácia žiadnu cloudovú službu nevyžaduje.

## Použitie

1. V Nastaveniach vyberte energiu, konečnú jednotkovú cenu vrátane DPH a distribúcie, stály mesačný poplatok, plán záloh a začiatok obdobia. Voda má cenu za m³ (napr. vodné + stočné). Plyn má cenu za kWh a používateľom zadaný faktor kWh/m³ z vyúčtovania. Predvolený faktor je 1 a treba ho nastaviť podľa spôsobu fakturácie.
2. Pridajte prvý kumulatívny stav k začiatku obdobia a aspoň jeden ďalší odpočet. Výber dátumu umožňuje spätné odpočty. Stav nesmie klesať a pre jednu energiu je povolený jeden odpočet denne.
3. Zapisujte skutočne zaplatené zálohy. Plán záloh nie je automatická platba.
4. Pri fotke vyberte rozpoznané číslo alebo vyrežte displej a spustite OCR znovu. Skontrolujte číslice i desatinnú čiarku. Uloženie OCR vyžaduje zaškrtnutie potvrdenia a následné stlačenie tlačidla uložiť.
5. Pred odinštalovaním či obnovou údajov exportujte zálohu do súboru JSON.

## Výpočet a hranice presnosti

- Spotreba = posledný stav − prvý stav v zvolenom období. Pri chýbajúcom odpočte k začiatku obdobia aplikácia výslovne uvádza kratší interval.
- Náklady = spotreba × cena × prevodný faktor + stály poplatok pomerne podľa skutočných dní v kalendárnych mesiacoch. Faktor elektriny a vody je 1.
- Interval nákladov aj zaplatených záloh je **[prvý deň odpočtu, posledný deň odpočtu)**. Platba v posledný deň patrí do nasledujúceho intervalu. Pri oboch dátumoch je to v rozhraní vysvetlené.
- Plán záloh počíta mesačné platby splatné prvého dňa mesiaca v tom istom intervale. Nemá vplyv na skutočný zostatok.
- Zostatok = zapísané platby v intervale − náklady. Kladný výsledok je priebežný preplatok, záporný nedoplatok. Nejde o predikciu ročného vyúčtovania.
- Počítanie peňazí používa BigDecimal; zaokrúhlenie nákladov na centy HALF_UP. Graf zobrazuje priemernú dennú spotrebu medzi odpočtami, najviac 12 posledných intervalov.
- Jedna sadzba na energiu pre sledované obdobie. Zmena sadzby prepočíta celé obdobie. Nie sú implementované viacpásmové tarify, viac meračov rovnakej energie, automatické sadzby dodávateľov ani výmena/pretočenie merača. Pri zmene sadzby začnite nové obdobie s príslušným odpočtom. Pri výmene merača exportujte zálohu a vytvorte novú evidenciu (obnovou prázdnej vlastnej zálohy alebo vymazaním údajov aplikácie).

## OCR a súkromie

ML Kit Text Recognition 16.0.1 má latinský model pribalený do APK, dostupný aj pri prvom spustení bez internetu. Nepoužíva sa dynamický model ani vzdialené API. Aplikácia z manifestu odstraňuje oprávnenia INTERNET a ACCESS_NETWORK_STATE.

Automatický návrh výrezu používa ohraničenie najvýraznejšieho číselného riadka z OCR (heuristika podľa výšky a počtu číslic). Nie je to samostatný naučený detektor displeja a môže vybrať sériové číslo. Ručný výrez funguje ťahaním rámika alebo posuvníkmi. Výrez môže byť opakovane rozpoznaný. OCR neprepisuje písmená na číslice a nikdy samo neukladá odpočet. Neposkytuje sa vymyslené percento istoty. Všetky návrhy sa považujú za neoverené.

Sedemsegmentové LCD, mechanické valčeky, odlesky a červené desatinné číslice sú náročné. Bez sady reálnych fotografií nemožno zaručiť presnosť. Manuálne zadanie je vždy dostupné. Fotoaparát sa otvára cez systémovú aplikáciu; galéria cez systémový výber, bez plošného prístupu k fotografiám. Veľké obrázky sa zmenšia, EXIF orientácia sa opraví. Fotografie sú iba v pamäti/privátnej cache, nie v databáze alebo zálohe; staré fotky z kamery sa čistia pri ďalšom fotografovaní po 24 hodinách.

SQLite databáza je v súkromnom úložisku aplikácie. Systémové cloudové zálohovanie je vypnuté. Manuálny JSON export obsahuje všetky odpočty, platby a ceny bez fotografií, bez šifrovania. Umiestnenie vyberá používateľ (aj jeho cloudový poskytovateľ dokumentov, ak ho zvolí). Import najprv validuje formát, čísla, chronológiu a duplicity, potom po potvrdení používateľa nahradí databázu v transakcii.

## Architektúra

- Kotlin 2.1.20, Jetpack Compose Material 3, Android Gradle Plugin 8.11.1, Gradle 8.13, JDK 17.
- `Domain.kt`: dátový model, prísne číselné parsovanie a finančné výpočty.
- `Store.kt`: lokálna SQLite databáza a validovaný JSON export/import.
- `AppModel.kt`: stav obrazoviek, operácie mimo UI vlákna.
- `Ocr.kt`: dekódovanie, EXIF, lokálne rozpoznávanie a výrez.
- `MainActivity.kt`: slovenské obrazovky prehľadu, histórie, nastavení a potvrdenia odpočtu.
- `app/src/test`: jednotkové testy výpočtov, hraníc intervalov a číselného parsovania.
- `app/src/androidTest`: integračný test databázy a obnovy na Android zariadení. Spustenie: `./gradlew connectedDebugAndroidTest`.

## Kontrola pred distribúciou

Na reálnom telefóne overte kameru, galériu s otočenými/veľkými obrázkami, prvé OCR v režime lietadlo, ručný výrez, obnovu zálohy, veľké systémové písmo a rôzne typy meračov. Automatické testy nepotvrdzujú presnosť OCR na vašom merači. Release podpísanie a publikovanie v Google Play sú samostatné kroky.

Technické zdroje: [ML Kit Android OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes).
