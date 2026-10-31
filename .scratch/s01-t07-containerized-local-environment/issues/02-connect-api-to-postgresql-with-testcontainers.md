# 02: Conectar a API ao PostgreSQL e testar com Testcontainers

**What to build:** permitir que a API receba sua conexão PostgreSQL por configuração externa em cada ambiente e comprovar essa integração durante o build contra um PostgreSQL 17 real, efêmero e isolado. O desenvolvimento local deve ter defaults utilizáveis, enquanto produção deve exigir configuração explícita.

**Blocked by:** 01/Preparar a fundação Maven multi-módulo da API.

**Status:** done

- [x] O driver PostgreSQL está disponível em runtime e suas versões continuam gerenciadas pelo Spring Boot.
- [x] As configurações comum, local, test e prod têm responsabilidades separadas e ativação previsível.
- [x] O profile local aceita host, porta, banco, usuário e senha por variáveis de ambiente e oferece defaults de desenvolvimento.
- [x] O profile de produção não fornece credenciais padrão e falha na inicialização quando a configuração obrigatória está ausente.
- [x] Um exemplo versionado documenta todas as variáveis locais, enquanto arquivos `.env` reais são ignorados pelo Git.
- [x] O teste de contexto usa `postgres:17-alpine` por Testcontainers e fornece os detalhes JDBC ao Spring Boot por uma service connection.
- [x] O banco de teste é efêmero e não compartilha volume, porta fixa ou credenciais com o ambiente local.
- [x] `mvn verify` iniciado na raiz conecta a aplicação ao PostgreSQL e passa quando Docker está disponível.
- [x] A indisponibilidade do Docker causa uma falha explícita do teste, sem skip silencioso ou substituição por banco em memória.


## Comments

Implementado e validado em 2026-10-31: teste de contexto e mvnw.cmd verify passaram com PostgreSQL 17.11 via Testcontainers. O JAR com profile prod sem variáveis encerrou com código 1. Revisão de padrões e spec: zero achados. Docker indisponível permanece uma falha obrigatória da extensão, sem disabledWithoutDocker ou banco alternativo.
