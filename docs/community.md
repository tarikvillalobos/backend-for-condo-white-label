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

O adaptador chama `POST {CAMERA_PROVIDER_BASE_URL}/sessions` com `cameraRef`, `provider`, `recordingId` quando houver reprodução e `ttlSeconds: 120`. A resposta precisa conter `id`, `url` e `expiresAt`; `protocol` aceita `hls` ou `webrtc`, e `iceServers` e `maxViewers` são opcionais. Para encerrar, chama `DELETE .../sessions` com `{"id":"id-do-provedor"}`. A busca de gravações usa `POST .../recordings/search` com `cameraRef`, período, cursor e limite e deve devolver o schema `RecordingPage`. Em produção, a URL de mídia precisa ser HTTPS e expirar em no máximo dez minutos.

Para testar os fluxos sem adivinhar campos, abra `/docs`, selecione a operação e use os schemas de request e response publicados. Os erros v1 usam `application/problem+json` com `requestId` para correlação.
