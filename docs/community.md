# Comunidade e operação v1

O [OpenAPI](openapi.yaml) define os schemas, as permissões e os códigos de cada operação. O morador acessa recursos pelo prefixo `/v1/memberships/{membershipId}`; a equipe usa `/v1/admin/condominiums/{condominiumId}` ou `/v1/ops`. O servidor confirma o vínculo ativo e aplica o escopo de condomínio e de módulo em cada chamada.

## Comunicação e atendimento

Avisos, eventos, notificações, documentos e contatos têm rotas próprias. Notificações internas persistem independentemente da entrega externa; `readAt` é gravado quando o usuário lê. Avisos podem ser agendados, e um worker processa a entrega. Chamados e ocorrências mantêm estado, comentários e histórico auditável; comentários internos exigem acesso de equipe. Relatórios e exportações podem ser processados em segundo plano e baixados por URL assinada.

## Acesso e portaria

Convites de visitantes, chegadas e credenciais de acesso têm janelas de validade e estado. A portaria usa rotas `/v1/ops/access` para validar e registrar ações; leitores usam credenciais de dispositivo quando o contrato permitir. Revogação, uso único e vínculo ativo são checados pelo servidor. Um comando de acesso ou uma validação digital não prova entrada física sem evento confiável do equipamento.

## Moradores e bens

Pets, alertas, veículos, manutenção e reservas seguem o contexto do morador e as permissões do contrato. A equipe administra registros do condomínio nas rotas `/v1/admin/condominiums/{condominiumId}`. Fotos e documentos privados usam uploads e URLs assinadas; dados de proprietário e de saúde animal não devem ser copiados para notificações públicas.

## Câmeras e integrações

O cadastro de câmeras, a lista de gravações e sessões de vídeo dependem de um provedor real. Configure `CAMERA_PROVIDER_BASE_URL` e, se exigido pelo provedor, `CAMERA_PROVIDER_TOKEN`. O servidor solicita sessões temporárias e valida URL, prazo e protocolo retornados. Sem provedor, as operações de mídia respondem com erro explícito. O volume local de arquivos do Compose atende um único host; veja [operations.md](operations.md) para implantação distribuída.

Para testar os fluxos sem adivinhar campos, abra `/docs`, selecione a operação e use os schemas de request e response publicados. Os erros v1 usam `application/problem+json` com `requestId` para correlação.
Push/SMS delivery requires provider setup; an inbox entry is not proof of external
delivery. Permissions: `notifications.read`, `notifications.manage`.

## Cameras and provider boundaries

- `GET /cameras`, `POST /cameras`, `PUT /cameras/{id}`:
  `{name,area,enabled,unitId?}`.
- `POST /cameras/{id}/sessions` requires `cameras.view` and the camera audience.
- `GET /cameras/{id}/recordings` separately requires `cameras.recordings`.

Both provider operations return `501 integration_unavailable` until a real
provider adapter is implemented. They never return invented live URLs or
equipment credentials. Management requires `cameras.manage`.

## Validation and boundaries

Titles generally allow 160 characters, long descriptions 10,000, and attachment
lists at most 10 URLs. External URLs require HTTPS and reject embedded credentials.
Invalid bodies produce 400, unauthorized operations 403, out-of-scope identifiers
404, and invalid state transitions 409. Read-own permission does not authorize
reading another member's resources or adding internal staff comments.
These modules store metadata and auditable staff operations; provider-specific
delivery, live video, gate actuation, and external attachment access policies are
separate integration responsibilities.
