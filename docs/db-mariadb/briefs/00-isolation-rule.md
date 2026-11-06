# Isolation rule (copy verbatim into every sub-agent prompt)

> Never open, list, grep, glob or summarize anything under `docs/db/` or `docs/db-postgresql/` (in the primary tree or in any
> worktree). Exclude both from every search (search only the paths you need; `git diff ... -- . ':!docs'`). They hold an older
> study and the PostgreSQL study, including earlier MariaDB numbers that must not influence this study.
> Do not use commit messages, PR texts or reference docs as the rationale for the branch's MariaDB SQL or DDL; judge it only by its
> code and by measurements. `docs/db-mariadb/reference/postgres-rationale.md` may be read **only** if your brief explicitly allows
> it. If you accidentally read something forbidden, stop and report exactly what you read.

Allowed sources: the code and DDL in the worktrees, `docs/adr/0005-query-priorities-and-jdbc-dialect-optimization.md` (in the
branch worktree), everything under `docs/db-mariadb/` (except the reference file, see above), MariaDB documentation and general
knowledge.
