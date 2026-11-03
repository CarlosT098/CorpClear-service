# Agent Guidelines & Rules

> [!IMPORTANT]
> **REGRA GERAL OBRIGATÓRIA / CRITICAL RULE:**
> **NÃO DEVE FAZER COMMIT (`git commit`)!**
> Todos os agentes de IA estão **estritamente proibidos** de criar commits ou executar `git commit`, `git push`, etc.
> O commit é realizado **manualmente e exclusivamente pelo usuário**.
> Os agentes devem apenas modificar/criar arquivos no repositório e deixá-los no working tree para revisão manual.
> Consulte os detalhes em `docs/agents/git-rules.md`.

---

## Agent skills

### Git & Commit Policy

- **Regra:** Nunca commitar alterações. Veja `docs/agents/git-rules.md`.

### Issue tracker

Issues and specs are tracked as local Markdown files under `.scratch/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the default Matt Pocock label vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository. See `docs/agents/domain.md`.
