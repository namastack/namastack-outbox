# Isolation rule (copy verbatim into every sub-agent prompt)

> Never open, list, grep, glob or summarize anything under `docs/db/`, `docs/db-sql/` or `docs/db-mariadb/` (in the primary tree,
> in any worktree or in any git branch/commit, e.g. `git show <ref>:docs/db-mariadb/...`). Exclude all three from every search
> (search only the paths you need; `git diff ... -- . ':!docs'`). They hold an older study, the PostgreSQL study and the MariaDB
> study, whose numbers and conclusions must not influence this MySQL study.
> Do not use commit messages, PR texts or reference docs as the rationale for the branch's MySQL SQL or DDL; judge it only by its
> code and by measurements. `docs/db-mysql/reference/postgres-rationale.md` may be read **only** if your brief explicitly allows
> it. If you accidentally read something forbidden, stop and report exactly what you read.
> Priority 3 (query groups RET and ADMIN) is never run: no timing, plan, equivalence or repository-level call of a P3 query.

Allowed sources: the code and DDL in the worktrees, `docs/adr/0005-query-priorities-and-jdbc-dialect-optimization.md` (in the
branch worktree), everything under `docs/db-mysql/` (except the reference file, see above), MySQL documentation, MySQL Connector/J
documentation or source, and general knowledge.
