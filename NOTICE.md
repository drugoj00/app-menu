# NOTICE / Attribution

The update-checking feature of App Menu ("Updates") is based on the ideas, protocols and data of **APKUpdater**:

- Original project: **APKUpdater** by rumboalla - https://github.com/rumboalla/apkupdater (GPL-3.0): sources F-Droid, IzzyOnDroid, Aptoide, GitHub, GitLab, APKPure; the list of known GitHub repositories (`app/src/main/assets/github_apps.json` is derived from its `GitHubApp.kt`).
- Fork: **APKUpdater (fork)** by DmitryN71 - https://github.com/DmitryN71/apkupdater (GPL-3.0): fixes and extensions of the sources (GitLab release links, APKPure rate-limit handling).
- Fork **APKUpdater OSS** by NotDev (EikeiDev/apkupdateross, GPL-3.0) was read for the RuStore protocol; RuStore in App Menu is off by default and marked as not tested live (no client-signature bypass).

The code in App Menu is written separately; only protocols, endpoints and the repository list are reused. App Menu is licensed GPL-3.0, like the projects above.

Источники обновлений основаны на идеях и протоколах APKUpdater (rumboalla, DmitryN71, NotDev), GPL-3.0.

- Идеи источника Codeberg (Forgejo/Gitea API releases) и общий подход к источникам - Obtainium (ImranR98, GPL-3.0), https://github.com/ImranR98/Obtainium. Код написан заново.

- Идея гибких настроек виджета часов и погоды (размеры, цвета, семисегментный стиль, прогноз «по часам / неделя») навеяна программой Tablet Clock (MIT), тема 4PDA 533885. Код и ресурсы Tablet Clock не использованы: всё написано заново, семисегментные цифры рисуются самой программой.
- Погодные данные: Open-Meteo.com (CC BY 4.0), MET Norway (api.met.no, CC BY 4.0 / NLOD), wttr.in; по желанию пользователя с его ключом OpenWeatherMap, WeatherAPI.com, Tomorrow.io.

- Обои и видео-обои из интернета: Wikimedia Commons (у каждого файла своя свободная лицензия, показываются автор и лицензия), NASA Image and Video Library / NASA APOD (записи без поля copyright), Openverse (только CC0 и CC-BY), по желанию пользователя с его ключом Pexels, Unsplash и Pixabay (ссылка на автора и источник по условиям сервисов). Ключей разработчика в программе нет.
- Wallpapers and video wallpapers from the internet: Wikimedia Commons (each file has its own free license; author and license are shown), NASA Image and Video Library / NASA APOD (entries without a copyright field), Openverse (CC0 and CC-BY only); with the user's own key Pexels, Unsplash and Pixabay (credit to the author and the source as the services require). The app contains no developer keys.
- Погода со своим ключом пользователя: Visual Crossing, Weatherbit, Meteoblue, AccuWeather (ключ вводится самим пользователем, по умолчанию пусто, ответы кэшируются).

## Sounds of the hourly signal (clock on the Home screen, 20.0)

The recordings in `app/src/main/res/raw/` are used under Creative Commons licenses (source: Wikimedia Commons); they are separate works and keep their own licenses:

- `cuckoo_call.wav`: a fragment of "Schlag Kuckucksuhr Beispiel 01" (audio of a cuckoo clock) by **HNH**, CC BY-SA 3.0 - https://commons.wikimedia.org/wiki/File:Schlag_Kuckucksuhr_Beispiel_01.ogv (trimmed, filtered, volume adjusted).
- `bell_strike.wav`: a fragment of "Samariter Church bell I (Es) 01" by **Pete w. View**, CC BY-SA 4.0 - https://commons.wikimedia.org/wiki/File:Samariter_Church_bell_I_(Es)_01.ogg (trimmed, faded, volume adjusted).

