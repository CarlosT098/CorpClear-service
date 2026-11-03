# Git & Commit Rules for AI Agents

## Regra Fundamental: Proibido Fazer Commit

> [!CAUTION]
> **NÃO FAÇA COMMIT (`git commit`)!**
> Todos os agentes de IA que operam neste projeto estão estritamente proibidos de criar commits ou enviar alterações para repositórios remotos.

---

### Diretrizes Obrigatórias

1. **Controle Exclusivo do Usuário:**
   - O desenvolvedor/usuário faz commits **exclusivamente de forma manual**.
   - Agentes **NUNCA** devem executar `git commit`, `git merge`, `git cherry-pick`, `git rebase` ou `git push`.

2. **O que os agentes DEVEM fazer:**
   - Criar, editar e organizar arquivos de código, testes, documentação e configurações.
   - Executar comandos de validação (compilação, testes, linters) quando solicitado.
   - Utilizar comandos de leitura do Git (como `git status` ou `git diff`) apenas para verificar o estado local, caso necessário.
   - Deixar todas as alterações salvas no working tree prontas para que o usuário revise.

3. **Comportamento ao finalizar tarefas:**
   - Resumir de forma clara quais arquivos foram criados ou modificados.
   - Não tentar commitar ou sugerir comandos para commitar automaticamente sem autorização prévia.
