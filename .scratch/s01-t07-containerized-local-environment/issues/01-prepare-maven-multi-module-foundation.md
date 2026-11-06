# 01: Preparar a fundação Maven multi-módulo da API

**What to build:** reorganizar a fundação do FinFlow para que o projeto raiz agregue o módulo executável da API e permaneça preparado para módulos futuros. A API deve continuar usando `br.com.finflow`, depender dos starters gerenciados pelo Spring Boot e produzir um artefato executável com nome estável.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [x] O build iniciado na raiz reconhece e compila o módulo da API.
- [x] A aplicação e seu teste existente pertencem ao módulo da API sem alteração do pacote `br.com.finflow`.
- [x] Spring Web, Spring Data JPA, Bean Validation e Actuator são consumidos por seus starters, sem versões Spring declaradas manualmente.
- [x] O módulo produz o artefato executável `finflow-api.jar`.
- [x] O empacotamento Maven sem testes conclui com sucesso e não requer Docker.


## Comments

Implementado em 2026-10-24:

- POM raiz agregador com Java 21 e módulo `finflow-api`; aplicação, recursos e teste movidos mantendo `br.com.finflow`.
- Starters Web MVC, Data JPA, Validation e Actuator com versões gerenciadas pelo parent Spring Boot existente.
- `./mvnw.cmd -B -DskipTests package` executado na raiz com sucesso, sem Docker, produzindo `finflow-api/target/finflow-api.jar`.
- Manifesto do JAR validado com `JarLauncher` e `Start-Class: br.com.finflow.FinflowApplication`.
- Teste individual `FinflowApplicationTests` e suíte completa `./mvnw.cmd -B verify` executados: o contexto falha com `Failed to determine a suitable driver class`. Driver, configuração PostgreSQL e integração Testcontainers pertencem ao ticket 02; este ticket exige empacotamento sem testes. O teste existente foi preservado sem desativação ou substituição por banco em memória.