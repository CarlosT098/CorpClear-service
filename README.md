# CorpClear Service

API de despesas e reembolsos corporativos em desenvolvimento. A Sprint 1 fornece a base Spring Boot, PostgreSQL e verificação de saúde; os endpoints de negócio serão implementados nas próximas sprints.

## Pré-requisitos

- Docker Engine ou Docker Desktop em execução, com containers Linux e Docker Compose v2.
- Para executar a API no host ou os testes: JDK 21 e `JAVA_HOME` configurado. O Maven Wrapper baixa o Maven; não é necessário instalá-lo separadamente.
- Acesso à internet no primeiro build para baixar dependências e imagens.
- Portas 8080 (API) e 5432 (PostgreSQL) disponíveis, ou valores alternativos nas variáveis abaixo.

Execute os comandos na raiz do repositório.

## Iniciar com Docker Compose

```sh
docker compose up --build -d
docker compose ps
docker compose logs -f api
```

Não é necessário criar `.env`: há valores locais padrão. A API aguarda o PostgreSQL ficar saudável antes de iniciar. Aguarde o log de inicialização da aplicação e consulte:

```sh
curl http://localhost:8080/actuator/health
```

No PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

O retorno saudável tem HTTP `200`, `status: UP` e, no profile `local`, `components.db.status: UP`. A saúde inclui a conexão com PostgreSQL. Se o banco ficar indisponível após a inicialização, o endpoint retorna HTTP `503` e `DOWN`; após o banco recuperar, volta a `200/UP`. A detecção pode aguardar o timeout de conexão. Falhas do banco durante a inicialização também podem impedir a API de iniciar.

Apenas o endpoint Actuator `health` é exposto. Detalhes e componentes são mostrados somente em `local`; os demais profiles retornam apenas o estado agregado.

## Configuração

Para personalizar o Compose, copie `.env.example` para `.env` (`Copy-Item .env.example .env` no PowerShell ou `cp .env.example .env` no Linux/macOS) e ajuste os valores. O arquivo local é ignorado pelo Git.

| Variável | Padrão local | Uso |
| --- | --- | --- |
| `API_PORT` | `8080` | Porta publicada da API no Compose; no host use `SERVER_PORT` para alterar a porta Spring Boot. |
| `POSTGRES_HOST` | `localhost` | Host do banco na execução pela IDE/Maven. No Compose a API usa `postgres`. |
| `POSTGRES_PORT` | `5432` | Porta do banco no host. Entre containers a API sempre usa `5432`. |
| `POSTGRES_DB` | `corpclear` | Nome do banco. |
| `POSTGRES_USER` | `corpclear` | Usuário do banco. |
| `POSTGRES_PASSWORD` | `corpclear_local` | Senha ilustrativa de desenvolvimento. |
| `SPRING_PROFILES_ACTIVE` | `local` | Profile da execução no host. O Compose fixa `local`. |

Se alterar `API_PORT`, ajuste a URL usada para consultar o health. Credenciais inicializam um volume novo; alterá-las no `.env` não altera usuários ou senhas de um banco já persistido.

O Spring Boot **não carrega `.env` automaticamente** na IDE/Maven. Nesse modo, exporte as variáveis no terminal ou configure-as na IDE. O profile `prod` exige `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` e `POSTGRES_PASSWORD` externos, sem defaults. O Compose fornecido é para desenvolvimento local.

## Executar pela IDE ou Maven

Inicie somente o banco (se a API estiver rodando pelo Compose, pare-a antes):

```sh
docker compose stop api
docker compose up -d postgres
```

Com os valores padrão, execute no Windows:

```powershell
.\mvnw.cmd -pl corpclear-service-api spring-boot:run
```

No Linux/macOS:

```sh
sh mvnw -pl corpclear-service-api spring-boot:run
```

Na IDE, importe o POM raiz como projeto Maven, selecione JDK 21 e execute `com.corpclear.CorpclearServiceApplication` no módulo `corpclear-service-api`. O profile padrão é `local`.

Se o banco foi personalizado no Compose, configure os mesmos valores no processo da API. Por exemplo, para a porta publicada `15432`, no PowerShell use `$env:POSTGRES_PORT = '15432'`; no Linux/macOS use `export POSTGRES_PORT=15432`. A API no host conecta a `localhost:15432`; a API em container continua conectando a `postgres:5432`.

## Testes e CI

Com JDK 21 e Docker em execução:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

No Linux/macOS:

```sh
sh mvnw --batch-mode --no-transfer-progress verify
```

Com Maven instalado, o equivalente é `mvn verify`. Execute na raiz para verificar todos os módulos. Os testes criam PostgreSQL 17 efêmero via Testcontainers, independente do Compose e de seu volume. Verificam conexão JDBC e saúde HTTP durante queda e recuperação do banco. Docker indisponível causa falha, sem pular os testes.

O workflow [Verify](.github/workflows/verify.yml) executa em todo push e pull request, com Ubuntu, Java 21 Temurin, cache Maven e `mvn verify`. O runner fornece Docker para Testcontainers. O build da imagem Docker empacota sem testes; a validação Maven é executada separadamente.

## Encerrar e reiniciar

Pare a API iniciada pela IDE/Maven com o botão de parada ou `Ctrl+C`. Para encerrar o ambiente Compose preservando os dados:

```sh
docker compose down
```

Para iniciá-lo novamente, use `docker compose up -d`; após mudanças no código, use `docker compose up --build -d`.

**Reset opcional: o comando abaixo apaga permanentemente os dados do volume PostgreSQL deste ambiente.** Use somente quando quiser começar com um banco vazio:

```sh
docker compose down -v
```

Para diagnosticar problemas, consulte `docker compose ps` e `docker compose logs api postgres`. Conflitos de portas podem ser resolvidos ajustando `API_PORT`/`POSTGRES_PORT`. Se os testes não encontrarem Docker, confirme que `docker info` funciona no mesmo terminal.
