# Reservas v1

O contrato está em [openapi.yaml](openapi.yaml), nas seções Reservations e Admin · Reservas. Um morador lista espaços em `GET /v1/memberships/{membershipId}/spaces`, consulta disponibilidade em `/spaces/{spaceId}/availability` e cria a reserva em `POST /v1/memberships/{membershipId}/reservations`. A equipe administra espaços e reservas em `/v1/admin/condominiums/{condominiumId}`.

## Regras de agenda

Cada espaço define horários por dia da semana, duração dos slots, antecedência mínima, horizonte de agendamento, limite de reservas futuras, capacidade, prazo de cancelamento e necessidade de aprovação. A API interpreta os horários no fuso IANA configurado para o condomínio; `startsAt` e `endsAt` são instantes UTC. O período deve caber em um único dia local e respeitar os slots e o horário de abertura.

Reservas confirmadas e pendentes ocupam o período. Bloqueios administrativos de espaço também impedem agendamento. A transação serializa alterações no escopo do condomínio e revalida conflitos ao criar ou aprovar, para impedir dupla reserva sob concorrência. O morador pode cancelar conforme o prazo configurado; a equipe pode aprovar, rejeitar ou cancelar pelas rotas administrativas.

## Operações principais

- `GET /v1/memberships/{membershipId}/spaces/{spaceId}/availability` mostra intervalos disponíveis segundo as regras atuais.
- `POST /v1/memberships/{membershipId}/reservations` cria reserva `pending` quando há aprovação ou `confirmed` caso contrário.
- `POST /v1/memberships/{membershipId}/reservations/{reservationId}/cancel` aplica o prazo de cancelamento.
- `POST /v1/admin/condominiums/{condominiumId}/spaces/{spaceId}/blocks` bloqueia um intervalo; `DELETE .../blocks/{blockId}` libera o bloqueio.
- `POST .../reservations/{reservationId}/approve`, `/reject` e `/cancel` executam as decisões da equipe.

Listas usam cursor e snapshot. Respostas 409 `RESERVATION_CONFLICT` indicam conflito de intervalo ou estado; 422 cobre regras de horário e validação. Use o schema de cada operação no OpenAPI para os campos exatos, cabeçalhos de idempotência, permissões e ETag.
back only one active event; simultaneous attempts to reuse it conflict.

Editing an event validates its link again. Omit `reservationId` or send `null` to
remove the link. Cancelling an event frees its link for another event but retains
the reservation. Reservation and event cancellation are separate staff actions;
a reservation status change does not automatically cancel a published event.
Event responses expose the linked ID without embedding private booking details.
