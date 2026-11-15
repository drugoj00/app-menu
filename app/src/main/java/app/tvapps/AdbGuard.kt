package app.tvapps

/**
 * Правило 20 (16.11.2026): любая установка и удаление пакета идёт только в текущего пользователя (`--user N`), иначе Samsung кладёт копию ещё и
 * в «Двойное приложение» (пользователь 95), и значки задваиваются. [violates] ловит команды `pm`/`cmd package` без `--user`; [Adb.shell] такие не выполняет.
 * Шаги сессии (install-write, install-commit, install-abandon) пользователя не задают: он назначен при install-create.
 */
object AdbGuard {
    private val verb = Regex("""(?:^|[;&|\s])(?:pm|cmd\s+package)\s+(?:install|install-create|install-existing|install-multiple|install-multi-package|uninstall)(?:\s|$)""")

    fun violates(cmd: String): Boolean = verb.containsMatchIn(cmd) && !Regex("""--user\s+\d+""").containsMatchIn(cmd)
}
