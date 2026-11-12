# NOTICE / Attribution

The update-checking feature of App Menu ("Updates") is based on the ideas, protocols and data of **APKUpdater**:

- Original project: **APKUpdater** by rumboalla — https://github.com/rumboalla/apkupdater (GPL-3.0): sources F-Droid, IzzyOnDroid, Aptoide, GitHub, GitLab, APKPure; the list of known GitHub repositories (`app/src/main/assets/github_apps.json` is derived from its `GitHubApp.kt`).
- Fork: **APKUpdater (fork)** by DmitryN71 — https://github.com/DmitryN71/apkupdater (GPL-3.0): fixes and extensions of the sources (GitLab release links, APKPure rate-limit handling).
- Fork **APKUpdater OSS** by NotDev (EikeiDev/apkupdateross, GPL-3.0) was read for the RuStore protocol; RuStore in App Menu is off by default and marked as not tested live (no client-signature bypass).

The code in App Menu is written separately; only protocols, endpoints and the repository list are reused. App Menu is licensed GPL-3.0, like the projects above.

Источники обновлений основаны на идеях и протоколах APKUpdater (rumboalla, DmitryN71, NotDev), GPL-3.0.

- Идеи источника Codeberg (Forgejo/Gitea API releases) и общий подход к источникам — Obtainium (ImranR98, GPL-3.0), https://github.com/ImranR98/Obtainium. Код написан заново.

- Идея гибких настроек виджета часов и погоды (размеры, цвета, семисегментный стиль, прогноз «по часам / неделя») навеяна программой Tablet Clock (MIT), тема 4PDA 533885. Код и ресурсы Tablet Clock не использованы: всё написано заново, семисегментные цифры рисуются самой программой.
- Погодные данные: Open-Meteo.com (CC BY 4.0), MET Norway (api.met.no, CC BY 4.0 / NLOD), wttr.in; по желанию пользователя с его ключом OpenWeatherMap, WeatherAPI.com, Tomorrow.io.
