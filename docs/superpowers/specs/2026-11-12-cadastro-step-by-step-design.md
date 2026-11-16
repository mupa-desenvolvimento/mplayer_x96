# Cadastro do dispositivo em passo a passo (com tags) — desenho

**Data:** 2026-11-12 · **Tamanho:** mudança pequena/média, em fluxo que já existe (`DeviceRegistrationActivity`)

## Intenção

Hoje o cadastro é uma tela com 4 campos empilhados. Quem instala o box na loja (controle remoto ou teclado USB, tela
de TV) precisa de algo mais guiado: **uma pergunta por vez**, no visual do ARGOS Remote (imagem de referência do
usuário): fundo azul escuro, logo no topo, cartão branco arredondado, barra de progresso, "1 de N", pergunta em
negrito, campo com borda roxa, botão roxo "Continuar →" e uma nota pequena embaixo.

A **última pergunta** lista as tags da empresa, para o dispositivo já nascer classificado.

## Objetivos / não-objetivos

- Objetivo: 4 passos (código da empresa → apelido → nº da loja/filial → tags), validação por passo, Voltar volta um passo,
  tudo operável só com D-pad/Enter.
- Objetivo: gravar as tags escolhidas em `device_tags` depois de cadastrar.
- Não-objetivo: mudar o RPC `create_dispositivo`, o servidor, ou o que acontece depois do cadastro (abre o player).
- Anti-objetivo: o cadastro **nunca** pode falhar ou travar por causa das tags (são opcionais e best-effort).

## Decisões do usuário

- Layout como na imagem; uma pergunta por vez; tags na última pergunta.
- **Gravar as tags pelo app em `device_tags`** (escolhido entre 3 abordagens, abaixo).

## Abordagens consideradas

1. **App grava em `device_tags`** (escolhida). Sem mudança no servidor; depende da permissão de escrita da chave do app.
2. Rota de registro com `tag_ids` no servidor: mais correto e seguro, mas depende da equipe da Mupa Connect.
3. Só escolher e guardar para depois: entrega rápida, sem efeito real.

## Desenho

### Passos
| # | Pergunta | Validação | Observação |
|---|---|---|---|
| 0 | "Qual é o ID do dispositivo?" | não vazio | **só** aparece se a identificação automática falhar |
| 1 | "Qual é o código da sua empresa?" | empresa existe (`companies.code`) | mostra o nome da empresa encontrada |
| 2 | "Como quer chamar este dispositivo?" | não vazio | |
| 3 | "Qual é o número da loja/filial?" | não vazio | |
| 4 | "Quais tags este dispositivo deve ter?" | opcional | lista da empresa; botão vira "Cadastrar dispositivo" |

Progresso "n de 4" (ou "n de 5" com o passo 0). O ID continua visível como marca d'água.

### Tela
Um único layout (`activity_device_registration.xml` reescrito): fundo em gradiente `#0B1220 → #0A1628`, logo `ic_mupa_logo`,
cartão branco (raio 24dp, largura máx. ~520dp), barra de progresso fina, "n de N" à direita, pergunta (22sp, negrito,
`#0B1220`), campo com contorno roxo `#6C5CE7`, botão roxo com gradiente `#6D5DF6 → #4F46E5`, nota cinza embaixo
("Seus dados são usados só pra configurar este painel"). O conteúdo do cartão troca por passo com animação curta
(fade/slide ~180 ms). O overlay "Reconectando..." e o botão de Wi-Fi continuam como estão.

### Tags (passo 4)
- Busca `tags?tenant_id=eq.<tenant da empresa>&is_active=eq.true`, ordenadas por categoria e nome. **Só as do tenant**:
  a composição do servidor ignora as globais (`tenant_id` nulo).
- Chips selecionáveis com a cor da tag, foco visível pro D-pad (Enter alterna).
- Pré-seleciona a tag de loja que corresponde à filial informada (`slug = loja-<n>`), se existir.
- Estados: carregando, vazio ("A empresa ainda não tem tags — dá pra seguir"), erro de rede (permite seguir sem tags).

### Gravação
1. `create_dispositivo` (como hoje) → `get_dispositivo_por_serial` (valida e salva cache).
2. **`device_uuid`**: o RPC não devolve; sai de `device.device_uuid` na resposta de `device-api-v2/manifest`.
3. `POST device_tags?on_conflict=device_id,tag_id` com `Prefer: resolution=ignore-duplicates`, uma linha por tag escolhida.
4. Falha na etapa 2 ou 3: o cadastro **conclui** e a tela de sucesso avisa "Tags não vinculadas — vincule no painel".

### Limitação conhecida (precisa ser dita à equipe)
O servidor só monta/atualiza a playlist `[Tags]` quando o dispositivo **não tem playlist** (`syncTagsAndPlaylistsEdge`), e a
primeira consulta do manifesto (que usamos para obter o `device_uuid`) já dispara essa composição **antes** de as tags
extras estarem vinculadas. Resultado: as tags escolhidas aparecem no painel imediatamente, mas só mudam a playlist quando
o servidor rodar a composição de novo (por exemplo, no próximo cadastro/edição na mesma empresa). O servidor já vincula
sozinho a tag da loja e as tags padrão. A solução definitiva é a abordagem 2.

## Estrutura

- `RegistrationWizard` (Kotlin puro): passos, validação, progresso, seleção de tags, pré-seleção da loja — testável sem Android.
- `TagsService`: `listByTenant(tenantId)`. `DeviceTagsService`: `resolveDeviceUuid(serial)` + `link(uuid, tagIds)`.
- `DeviceRegistrationViewModel`: usa o wizard e orquestra a gravação. `DeviceRegistrationActivity`: renderiza o passo atual.

## Testes

- Unitários do `RegistrationWizard` (avanço, bloqueios, Voltar, passo 0 condicional, pré-seleção da loja, tags opcionais).
- Na TV de bancada: percorrer os passos só com D-pad, Voltar, erro de empresa inexistente, offline.
- **Escrita em `device_tags` não foi testada** (para não sujar o banco): validar no primeiro cadastro real.

## Decisões minhas (a conferir)

- Tags são **opcionais**; sem nenhuma selecionada o cadastro conclui normalmente.
- Texto do rodapé do cartão e das perguntas (acima).
- O passo 0 só entra quando preciso; o ID normal fica automático.
